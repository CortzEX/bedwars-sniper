#!/usr/bin/env bash
# Offline smoke test: fake 1.8.9 client, real loader, asserted output.
set -euo pipefail
cd "$(dirname "$0")"

rm -rf build
mkdir -p build/classes

javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
  -d build/classes \
  $(find ../src src -name '*.java' | sort)

java -Xmx256m -cp build/classes Fake189
