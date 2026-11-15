#!/bin/bash
# Host-side tests for the firmware core. Run from anywhere; paths resolve from this file.
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

MINGW="C:/Users/jheraldkim/AppData/Local/Microsoft/WinGet/Packages/BrechtSanders.WinLibs.POSIX.UCRT_Microsoft.Winget.Source_8wekyb3d8bbwe/mingw64/bin"
if [ -d "$MINGW" ]; then PATH="$MINGW:$PATH"; fi

for f in firmware/test/test_core.cpp firmware/AquaSense_ESP32/aquasense_core.cpp; do
  if [ ! -f "$f" ]; then echo "ERROR: missing source file: $f" >&2; exit 2; fi
done

mkdir -p build
g++ -std=c++17 -Wall -I firmware/AquaSense_ESP32 \
  firmware/test/test_core.cpp firmware/AquaSense_ESP32/aquasense_core.cpp \
  -o build/test_core.exe
./build/test_core.exe
