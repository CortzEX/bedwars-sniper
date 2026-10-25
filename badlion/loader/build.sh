#!/usr/bin/env bash
# Builds blsnip-loader.jar. Needs a JDK 8 (or any JDK that still accepts
# -source 8). No Minecraft, no Forge, no gradle: everything is resolved at
# runtime, so the jar has zero compile-time game dependencies.
set -euo pipefail
cd "$(dirname "$0")"

rm -rf build
mkdir -p build/classes

javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
  -d build/classes \
  $(find src -name '*.java' | sort)

# Must sit at the jar root: Notch loads it as "/notch.properties".
cp notch.properties build/classes/notch.properties

jar cfm build/blsnip-loader.jar manifest.txt -C build/classes .

echo "built build/blsnip-loader.jar"
unzip -l build/blsnip-loader.jar
