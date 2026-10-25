@echo off
setlocal

if "%JAVA_HOME%"=="" (
    for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-8*") do set "JAVA_HOME=%%~fD"
)
if "%JAVA_HOME%"=="" (
    echo [!] JAVA_HOME is not set and no Temurin 8 was found in Program Files.
    echo     set JAVA_HOME=C:\path\to\jdk8  and run this again.
    exit /b 1
)

echo [*] JAVA_HOME=%JAVA_HOME%

where cmake >nul 2>&1
if errorlevel 1 (
    echo [!] cmake is not on PATH. Install it, or run this from a
    echo     "x64 Native Tools Command Prompt for VS 2022".
    exit /b 1
)

cmake -S "%~dp0." -B "%~dp0build" -A x64 -DCMAKE_BUILD_TYPE=Release -DJAVA_HOME="%JAVA_HOME%"
if errorlevel 1 exit /b 1

cmake --build "%~dp0build" --config Release
if errorlevel 1 exit /b 1

echo.
echo [+] built:
dir /b "%~dp0build\bin"
