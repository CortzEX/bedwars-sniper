@echo off
rem Offline smoke test: fake 1.8.9 client, real loader, asserted output.
rem Needs a JDK 8: javac/java on PATH, or JAVA_HOME set.
setlocal

cd /d "%~dp0"

where javac >nul 2>nul
if errorlevel 1 (
  if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
)
where javac >nul 2>nul
if errorlevel 1 (
  echo [!] javac not found. Install JDK 8 and set JAVA_HOME, or put its bin on PATH.
  exit /b 1
)

if exist build rmdir /s /q build
mkdir build\classes

javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d build\classes ..\src\fx\sniper\bl\*.java src\*.java src\com\mojang\authlib\*.java
if errorlevel 1 (
  echo [!] javac failed
  exit /b 1
)

java -Xmx256m -cp build\classes Fake189
if errorlevel 1 exit /b 1

echo.
echo smoke run left its work files in %~dp0build\classes
