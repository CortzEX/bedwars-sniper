@echo off
rem Builds blsnip-loader.jar. Needs a JDK 8: javac on PATH, or JAVA_HOME set.
rem No Minecraft, no Forge, no gradle — the jar has zero compile-time game
rem dependencies because every game class is resolved at runtime.
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
mkdir build\stubs

javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d build\classes src\fx\sniper\bl\*.java
if errorlevel 1 (
  echo [!] javac failed
  exit /b 1
)

rem The menu is compiled against stand-ins for the 1.8.9 class names, never against
rem the game jar. The stubs are classpath only and are not packaged.
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d build\stubs screen\stubs\*.java
if errorlevel 1 (
  echo [!] javac failed on the stubs
  exit /b 1
)

rem SnipeScreen lives in the default package: it extends the game's GuiScreen, and
rem obfuscated game classes are unpackaged, so a packaged class could not extend one.
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 ^
  -cp "build\stubs" -d build\classes ^
  screen\src\SnipeScreen.java
if errorlevel 1 (
  echo [!] javac failed on the menu
  exit /b 1
)

copy /y notch.properties build\classes\notch.properties >nul

jar cfm build\blsnip-loader.jar manifest.txt -C build\classes .
if errorlevel 1 (
  echo [!] jar failed
  exit /b 1
)

echo.
echo built build\blsnip-loader.jar
jar tf build\blsnip-loader.jar
