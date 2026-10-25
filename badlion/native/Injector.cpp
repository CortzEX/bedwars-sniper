// Badlion Client JVM injector (x64).
//
// Finds Badlion's javaw.exe by reading each candidate's command line out of its
// PEB, then loads blagent.dll into it with a remote thread.
//
// Usage
//   inject.exe                        auto-detect Badlion, use ..\blagent.dll
//   inject.exe --list                 every javaw.exe, with a Badlion flag
//   inject.exe --pid 1234             explicit process
//   inject.exe --dll C:\x\blagent.dll explicit payload
//   inject.exe --wait 60              seconds to wait for LoadLibrary to return

#include <windows.h>
#include <winternl.h>
#include <tlhelp32.h>

#include <cstdio>
#include <cwchar>
#include <cwctype>
#include <cstdlib>
#include <string>
#include <vector>

namespace {

using NtCreateThreadExFn = NTSTATUS(NTAPI*)(PHANDLE, ACCESS_MASK, PVOID, HANDLE,
                                            PVOID, PVOID, ULONG, SIZE_T, SIZE_T,
                                            SIZE_T, PVOID);

constexpr DWORD kOpenFlags = PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION |
                             PROCESS_VM_OPERATION | PROCESS_VM_WRITE |
                             PROCESS_VM_READ;

// x64-only LAYOUT constants. PEB.ProcessParameters sits at 0x20 and
// RTL_USER_PROCESS_PARAMETERS.CommandLine at 0x70 across Windows 7..11. These
// are the only two struct offsets read out of the remote process; everything
// else is a documented API.
constexpr SIZE_T kPebProcessParameters = 0x20;
constexpr SIZE_T kParamsCommandLine = 0x70;

std::wstring exeDir()
{
    wchar_t buf[MAX_PATH]{};
    const DWORD n = GetModuleFileNameW(nullptr, buf, MAX_PATH);
    if (n == 0 || n == MAX_PATH) return L".";
    wchar_t* slash = wcsrchr(buf, L'\\');
    if (slash != nullptr) *slash = L'\0';
    return buf;
}

bool containsIgnoreCase(const std::wstring& haystack, const wchar_t* needle)
{
    const size_t nlen = wcslen(needle);
    if (nlen == 0 || haystack.size() < nlen) return false;

    std::wstring lower = haystack;
    for (wchar_t& c : lower) c = static_cast<wchar_t>(towlower(c));
    return lower.find(needle) != std::wstring::npos;
}

std::wstring commandLineOf(HANDLE proc)
{
    PROCESS_BASIC_INFORMATION pbi{};
    ULONG returned = 0;
    const NTSTATUS st = NtQueryInformationProcess(proc, ProcessBasicInformation,
                                                  &pbi, sizeof(pbi), &returned);
    if (st < 0 || pbi.PebBaseAddress == nullptr) return {};

    PVOID params = nullptr;
    SIZE_T read = 0;
    if (!ReadProcessMemory(proc,
                           reinterpret_cast<const BYTE*>(pbi.PebBaseAddress) + kPebProcessParameters,
                           &params, sizeof(params), &read) ||
        params == nullptr) {
        return {};
    }

    UNICODE_STRING cmdLine{};
    if (!ReadProcessMemory(proc,
                           reinterpret_cast<const BYTE*>(params) + kParamsCommandLine,
                           &cmdLine, sizeof(cmdLine), &read) ||
        cmdLine.Buffer == nullptr || cmdLine.Length == 0) {
        return {};
    }

    // Length is in bytes and excludes the terminator; cap it so a corrupt
    // header cannot make us allocate unbounded memory.
    const SIZE_T bytes = cmdLine.Length < 0x8000 ? cmdLine.Length : 0x8000;
    std::wstring out(bytes / sizeof(wchar_t), L'\0');
    if (!ReadProcessMemory(proc, cmdLine.Buffer, &out[0], bytes, &read)) return {};
    return out;
}

struct Candidate {
    DWORD pid;
    std::wstring exe;
    std::wstring cmd;
    bool badlion;
};

std::vector<Candidate> listJavaw()
{
    std::vector<Candidate> out;

    const HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return out;

    PROCESSENTRY32W pe{};
    pe.dwSize = sizeof(pe);

    for (BOOL ok = Process32FirstW(snap, &pe); ok; ok = Process32NextW(snap, &pe)) {
        if (_wcsicmp(pe.szExeFile, L"javaw.exe") != 0) continue;

        // Query-only rights are enough to read the PEB; the heavier mask is
        // requested later, once we know this is the process we want.
        HANDLE proc = OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, FALSE,
                                  pe.th32ProcessID);
        std::wstring cmd;
        if (proc != nullptr) {
            cmd = commandLineOf(proc);
            CloseHandle(proc);
        }

        Candidate c;
        c.pid = pe.th32ProcessID;
        c.exe = pe.szExeFile;
        c.cmd = std::move(cmd);
        c.badlion = containsIgnoreCase(c.cmd, L"badlion");
        out.push_back(std::move(c));
    }

    CloseHandle(snap);
    return out;
}

FARPROC remoteLoadLibrary(HANDLE proc)
{
    // kernel32.dll is mapped at the same base in every process of a boot, so a
    // locally resolved address is valid inside the target. Same-boot only, which
    // is why nothing here is persisted.
    (void)proc;

    HMODULE local = GetModuleHandleW(L"kernel32.dll");
    if (local == nullptr) return nullptr;
    return GetProcAddress(local, "LoadLibraryW");
}

bool inject(DWORD pid, const std::wstring& dllPath, DWORD waitSeconds)
{
    HANDLE proc = OpenProcess(kOpenFlags, FALSE, pid);
    if (proc == nullptr) {
        const DWORD err = GetLastError();
        wprintf(L"[!] OpenProcess(%lu) failed: %lu\n", pid, err);
        if (err == ERROR_ACCESS_DENIED) {
            wprintf(L"    5 = access denied. Badlion Anti-Cheat protects the game\n"
                    L"    process handle; inject at launch instead of into a live game.\n");
        }
        return false;
    }

    const SIZE_T bytes = (dllPath.size() + 1) * sizeof(wchar_t);
    void* remote = VirtualAllocEx(proc, nullptr, bytes,
                                  MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (remote == nullptr) {
        wprintf(L"[!] VirtualAllocEx failed: %lu\n", GetLastError());
        CloseHandle(proc);
        return false;
    }

    SIZE_T written = 0;
    if (!WriteProcessMemory(proc, remote, dllPath.c_str(), bytes, &written) ||
        written != bytes) {
        wprintf(L"[!] WriteProcessMemory failed: %lu\n", GetLastError());
        VirtualFreeEx(proc, remote, 0, MEM_RELEASE);
        CloseHandle(proc);
        return false;
    }

    const FARPROC loadLibrary = remoteLoadLibrary(proc);
    if (loadLibrary == nullptr) {
        wprintf(L"[!] cannot resolve LoadLibraryW\n");
        VirtualFreeEx(proc, remote, 0, MEM_RELEASE);
        CloseHandle(proc);
        return false;
    }

    HANDLE thread = nullptr;

    // NtCreateThreadEx first: it accepts an explicit access mask and does not
    // touch the loader lock the same way CreateRemoteThread does, so it survives
    // more of the hooking that ships with the client.
    const auto ntCreate = reinterpret_cast<NtCreateThreadExFn>(
        GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "NtCreateThreadEx"));
    if (ntCreate != nullptr) {
        const NTSTATUS st = ntCreate(&thread, THREAD_ALL_ACCESS, nullptr, proc,
                                     reinterpret_cast<PVOID>(loadLibrary), remote,
                                     0, 0, 0, 0, nullptr);
        if (st < 0) thread = nullptr;
    }

    if (thread == nullptr) {
        thread = CreateRemoteThread(proc, nullptr, 0,
                                    reinterpret_cast<LPTHREAD_START_ROUTINE>(loadLibrary),
                                    remote, 0, nullptr);
    }

    if (thread == nullptr) {
        wprintf(L"[!] remote thread creation failed: %lu\n", GetLastError());
        VirtualFreeEx(proc, remote, 0, MEM_RELEASE);
        CloseHandle(proc);
        return false;
    }

    const DWORD waitMs = waitSeconds == 0 ? 0 : waitSeconds * 1000u;
    const DWORD wait = WaitForSingleObject(thread, waitMs);
    DWORD remoteRet = 0;
    GetExitCodeThread(thread, &remoteRet);

    CloseHandle(thread);
    VirtualFreeEx(proc, remote, 0, MEM_RELEASE);
    CloseHandle(proc);

    if (wait != WAIT_OBJECT_0) {
        wprintf(L"[!] LoadLibrary did not return within %lus\n", waitSeconds);
        return false;
    }
    if (remoteRet == 0) {
        wprintf(L"[!] remote LoadLibrary returned NULL - payload failed to load\n");
        return false;
    }
    return true;
}

void usage()
{
    wprintf(L"inject.exe [--pid N] [--dll PATH] [--wait SECONDS] [--list]\n");
}

} // namespace

int wmain(int argc, wchar_t** argv)
{
    std::wstring dllPath;
    DWORD pid = 0;
    DWORD waitSeconds = 30;
    bool list = false;

    for (int i = 1; i < argc; ++i) {
        const std::wstring a = argv[i];
        if (a == L"--dll" && i + 1 < argc) {
            dllPath = argv[++i];
        } else if (a == L"--pid" && i + 1 < argc) {
            pid = static_cast<DWORD>(wcstoul(argv[++i], nullptr, 10));
        } else if (a == L"--wait" && i + 1 < argc) {
            waitSeconds = static_cast<DWORD>(wcstoul(argv[++i], nullptr, 10));
        } else if (a == L"--list") {
            list = true;
        } else {
            usage();
            return 2;
        }
    }

    if (dllPath.empty()) dllPath = exeDir() + L"\\blagent.dll";

    if (list) {
        for (const Candidate& c : listJavaw()) {
            wprintf(L"%s %6lu  %s\n", c.badlion ? L"[BL]" : L"[  ]", c.pid,
                    c.cmd.empty() ? L"<command line unreadable>" : c.cmd.c_str());
        }
        return 0;
    }

    if (pid == 0) {
        std::vector<Candidate> all = listJavaw();

        std::vector<DWORD> hits;
        for (const Candidate& c : all) {
            if (c.badlion) hits.push_back(c.pid);
        }

        if (hits.size() > 1) {
            wprintf(L"[!] %zu Badlion JVMs running - pick one with --pid:\n",
                    hits.size());
            for (const Candidate& c : all) {
                if (c.badlion) wprintf(L"      %6lu  %s\n", c.pid, c.cmd.c_str());
            }
            return 3;
        }
        if (hits.empty()) {
            wprintf(L"[!] no javaw.exe with \"badlion\" in its command line.\n"
                    L"    run with --list to see what was found; the client may\n"
                    L"    have been started through a launcher wrapper.\n");
            for (const Candidate& c : all) {
                wprintf(L"      %6lu  %s\n", c.pid,
                        c.cmd.empty() ? L"<unreadable>" : c.cmd.c_str());
            }
            return 4;
        }
        pid = hits.front();
    }

    const DWORD attrs = GetFileAttributesW(dllPath.c_str());
    if (attrs == INVALID_FILE_ATTRIBUTES || (attrs & FILE_ATTRIBUTE_DIRECTORY) != 0) {
        wprintf(L"[!] payload not found: %s\n", dllPath.c_str());
        return 5;
    }

    wprintf(L"[*] pid   %lu\n[*] dll   %s\n", pid, dllPath.c_str());

    if (!inject(pid, dllPath, waitSeconds)) return 1;

    wprintf(L"[+] injected. agent log is next to the dll (agent.log)\n");
    return 0;
}
