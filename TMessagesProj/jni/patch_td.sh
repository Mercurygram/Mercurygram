#!/bin/bash
set -e

grep -q -- "--icf=safe" td/CMake/TdSetUpCompiler.cmake || exit 0

# Remove unsupported --icf=safe from Android linker flags
patch -d td -p1 < patches/td/0001-remove-icf-safe-from-linker.patch

echo "td: icf=safe patch applied"
