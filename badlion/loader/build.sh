#!/usr/bin/env bash
# Builds blsnip-loader.jar. Needs a JDK 8 (or any JDK that still accepts
# -source 8). No Minecraft, no Forge, no gradle: everything is resolved at
# runtime, so the jar has zero compile-time game dependencies.
set -euo pipefail
cd "$(dirname "$0")"

rm -rf build
mkdir -p build/classes build/stubs

javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
  -d build/classes \
  $(find src -name '*.java' | sort)

# The menu is compiled against stand-ins for the 1.8.9 class names, never against
# the game jar. The stubs are classpath only and are not packaged.
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
  -d build/stubs \
  $(find screen/stubs -name '*.java' | sort)

# SnipeScreen lives in the default package: it extends the game's GuiScreen, and
# obfuscated game classes are unpackaged, so a packaged class could not extend one.
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
  -cp "build/stubs" \
  -d build/classes \
  screen/src/SnipeScreen.java

# Must sit at the jar root: Notch loads it as "/notch.properties".
cp notch.properties build/classes/notch.properties

jar cfm build/blsnip-loader.jar manifest.txt -C build/classes .

echo "built build/blsnip-loader.jar"
unzip -l build/blsnip-loader.jar
