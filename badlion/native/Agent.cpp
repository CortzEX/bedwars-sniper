// Badlion Client agent payload (x64).
//
// Loaded into javaw.exe by inject.exe. Does exactly one thing: hand control to
// the Java half.
//
//   1. wait for jvm.dll and for the VM to exist
//   2. wrap blsnip-loader.jar in a URLClassLoader whose parent is the
//      application class loader, so the loader can see Minecraft's classes
//   3. call fx.sniper.bl.Bootstrap.install()
//
// All game-side work (reflection, polling, chat) lives on the Java side. This
// file touches nothing obfuscated, so it never needs a mapping update.
//
// The jar is looked up next to the DLL, or at %BLSNIP_JAR% when set. Log goes to
// agent.log next to the DLL.

#include <windows.h>
#include <jni.h>

#include <cstdarg>
#include <cstdio>
#include <string>

namespace {

using GetCreatedJavaVMsFn = jint(JNICALL*)(JavaVM**, jsize, jsize*);

HMODULE g_self = nullptr;
HANDLE g_log = INVALID_HANDLE_VALUE;

void logOpen(const std::wstring& dir)
{
    const std::wstring path = dir + L"\\agent.log";
    g_log = CreateFileW(path.c_str(), FILE_APPEND_DATA, FILE_SHARE_READ, nullptr,
                        OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
}

void logf(const char* fmt, ...)
{
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    const int n = vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);

    if (n <= 0) return;
    const DWORD len = static_cast<DWORD>(n < static_cast<int>(sizeof(buf)) ? n : sizeof(buf) - 1);

    if (g_log != INVALID_HANDLE_VALUE) {
        DWORD written = 0;
        SYSTEMTIME st{};
        GetLocalTime(&st);
        char stamp[40];
        const int sn = sprintf_s(stamp, "[%02u:%02u:%02u] ", st.wHour, st.wMinute, st.wSecond);
        if (sn > 0) WriteFile(g_log, stamp, static_cast<DWORD>(sn), &written, nullptr);
        WriteFile(g_log, buf, len, &written, nullptr);
        WriteFile(g_log, "\r\n", 2, &written, nullptr);
        FlushFileBuffers(g_log);
    }
    OutputDebugStringA(buf);
    OutputDebugStringA("\n");
}

std::wstring moduleDir(HMODULE mod)
{
    wchar_t buf[MAX_PATH]{};
    const DWORD n = GetModuleFileNameW(mod, buf, MAX_PATH);
    if (n == 0 || n == MAX_PATH) return L".";
    wchar_t* slash = wcsrchr(buf, L'\\');
    if (slash != nullptr) *slash = L'\0';
    return buf;
}

std::wstring readEnv(const wchar_t* name)
{
    const DWORD need = GetEnvironmentVariableW(name, nullptr, 0);
    if (need == 0) return {};
    std::wstring out(need, L'\0');
    const DWORD got = GetEnvironmentVariableW(name, &out[0], need);
    if (got == 0) return {};
    out.resize(got);
    return out;
}

// Collapse a pending Java exception into the log and say whether one existed.
bool drainException(JNIEnv* env, const char* where)
{
    if (!env->ExceptionCheck()) return false;

    jthrowable thrown = env->ExceptionOccurred();
    env->ExceptionClear();

    const char* text = "<unprintable>";
    std::string owned;
    if (thrown != nullptr) {
        jclass throwableCls = env->FindClass("java/lang/Throwable");
        if (throwableCls != nullptr) {
            jmethodID toStr = env->GetMethodID(throwableCls, "toString",
                                               "()Ljava/lang/String;");
            if (toStr != nullptr) {
                jstring s = static_cast<jstring>(
                    env->CallObjectMethod(thrown, toStr));
                if (s != nullptr) {
                    const char* chars = env->GetStringUTFChars(s, nullptr);
                    if (chars != nullptr) {
                        owned = chars;
                        env->ReleaseStringUTFChars(s, chars);
                        text = owned.c_str();
                    }
                }
            }
            env->DeleteLocalRef(throwableCls);
        }
        env->DeleteLocalRef(thrown);
    }
    logf("[!] %s: %s", where, text);
    env->ExceptionClear();
    return true;
}

// Returns a global-ref'd Bootstrap class, or nullptr.
jclass loadBootstrap(JNIEnv* env, const std::wstring& jarPath)
{
    // Build the jar URL from a File so Windows drive letters and spaces are
    // handled by java.io rather than by string concatenation here.
    jclass fileCls = env->FindClass("java/io/File");
    if (fileCls == nullptr) { drainException(env, "File"); return nullptr; }
    jmethodID fileCtor = env->GetMethodID(fileCls, "<init>", "(Ljava/lang/String;)V");
    jmethodID toUri = env->GetMethodID(fileCls, "toURI", "()Ljava/net/URI;");
    if (fileCtor == nullptr || toUri == nullptr) { drainException(env, "File methods"); return nullptr; }

    jstring jPath = env->NewString(reinterpret_cast<const jchar*>(jarPath.c_str()),
                                   static_cast<jsize>(jarPath.size()));
    jobject file = env->NewObject(fileCls, fileCtor, jPath);
    jobject uri = env->CallObjectMethod(file, toUri);
    if (drainException(env, "File.toURI")) return nullptr;

    jclass uriCls = env->FindClass("java/net/URI");
    jmethodID toUrl = env->GetMethodID(uriCls, "toURL", "()Ljava/net/URL;");
    jobject url = env->CallObjectMethod(uri, toUrl);
    if (drainException(env, "URI.toURL")) return nullptr;

    jclass urlCls = env->FindClass("java/net/URL");
    jobjectArray urls = env->NewObjectArray(1, urlCls, nullptr);
    env->SetObjectArrayElement(urls, 0, url);

    // Parent loader must be the one that already holds Minecraft's classes.
    jclass threadCls = env->FindClass("java/lang/Thread");
    jmethodID current = env->GetStaticMethodID(threadCls, "currentThread",
                                               "()Ljava/lang/Thread;");
    jobject thread = env->CallStaticObjectMethod(threadCls, current);
    jmethodID ctxLoader = env->GetMethodID(threadCls, "getContextClassLoader",
                                           "()Ljava/lang/ClassLoader;");
    jobject parent = env->CallObjectMethod(thread, ctxLoader);
    if (drainException(env, "Thread.getContextClassLoader")) return nullptr;

    jclass uclCls = env->FindClass("java/net/URLClassLoader");
    jmethodID uclCtor = env->GetMethodID(
        uclCls, "<init>", "([Ljava/net/URL;Ljava/lang/ClassLoader;)V");
    jobject loader = env->NewObject(uclCls, uclCtor, urls, parent);
    if (drainException(env, "new URLClassLoader")) return nullptr;

    jclass classCls = env->FindClass("java/lang/Class");
    jmethodID forName = env->GetStaticMethodID(
        classCls, "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
    jstring clsName = env->NewStringUTF("fx.sniper.bl.Bootstrap");
    jobject found = env->CallStaticObjectMethod(classCls, forName, clsName,
                                               JNI_TRUE, loader);
    if (drainException(env, "Class.forName(fx.sniper.bl.Bootstrap)")) return nullptr;

    return static_cast<jclass>(env->NewGlobalRef(found));
}

DWORD WINAPI worker(LPVOID)
{
    const std::wstring dir = moduleDir(g_self);
    logOpen(dir);
    logf("[*] agent attached, dir=%ls", dir.c_str());

    std::wstring jarPath = readEnv(L"BLSNIP_JAR");
    if (jarPath.empty()) jarPath = dir + L"\\blsnip-loader.jar";
    logf("[*] loader jar: %ls", jarPath.c_str());

    // jvm.dll exists in the process before main() runs, but an injection at
    // process-creation time can land ahead of it.
    HMODULE jvmDll = nullptr;
    for (int i = 0; i < 900 && jvmDll == nullptr; ++i) {
        jvmDll = GetModuleHandleW(L"jvm.dll");
        if (jvmDll == nullptr) Sleep(100);
    }
    if (jvmDll == nullptr) { logf("[!] jvm.dll never appeared"); return 1; }

    const auto getVm = reinterpret_cast<GetCreatedJavaVMsFn>(
        GetProcAddress(jvmDll, "JNI_GetCreatedJavaVMs"));
    if (getVm == nullptr) { logf("[!] JNI_GetCreatedJavaVMs missing"); return 1; }

    JavaVM* vm = nullptr;
    jsize count = 0;
    for (int i = 0; i < 900; ++i) {
        count = 0;
        vm = nullptr;
        if (getVm(&vm, 1, &count) == JNI_OK && count > 0 && vm != nullptr) break;
        vm = nullptr;
        Sleep(100);
    }
    if (vm == nullptr) { logf("[!] no Java VM was ever created"); return 1; }
    logf("[*] VM %p", static_cast<void*>(vm));

    JNIEnv* env = nullptr;
    jint rc = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8);
    if (rc == JNI_EDETACHED) {
        // Daemon: attaching must never be the reason the game refuses to exit.
        rc = vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr);
    }
    if (rc != JNI_OK || env == nullptr) {
        logf("[!] cannot attach to the VM (rc=%d)", static_cast<int>(rc));
        return 1;
    }

    jclass bootstrap = loadBootstrap(env, jarPath);
    if (bootstrap == nullptr) {
        logf("[!] bootstrap class not reachable from %ls", jarPath.c_str());
        vm->DetachCurrentThread();
        return 1;
    }

    jmethodID install = env->GetStaticMethodID(bootstrap, "install", "()V");
    if (install == nullptr) {
        drainException(env, "Bootstrap.install signature");
        env->DeleteGlobalRef(bootstrap);
        vm->DetachCurrentThread();
        return 1;
    }

    env->CallStaticVoidMethod(bootstrap, install);
    const bool failed = drainException(env, "Bootstrap.install");

    env->DeleteGlobalRef(bootstrap);
    vm->DetachCurrentThread();

    logf(failed ? "[!] install threw, agent idle" : "[+] install() returned, agent running");
    return failed ? 1 : 0;
}

} // namespace

BOOL APIENTRY DllMain(HMODULE self, DWORD reason, LPVOID)
{
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(self);
        g_self = self;

        // Nothing but a thread spawn happens under the loader lock.
        HANDLE t = CreateThread(nullptr, 0, worker, self, 0, nullptr);
        if (t != nullptr) CloseHandle(t);
    }
    return TRUE;
}
