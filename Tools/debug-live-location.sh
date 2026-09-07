#!/usr/bin/env bash
# Follow Mercurygram live-location diagnostics.
# Usage: ./Tools/debug-live-location.sh [package]
set -euo pipefail
PKG="${1:-it.belloworld.mercurygram.beta}"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"

echo "== devices =="
"$ADB" devices -l

echo
echo "== package / user 10 =="
"$ADB" shell pm path --user 10 "$PKG" || "$ADB" shell pm path "$PKG" || true

echo
echo "== LocationSharingService =="
"$ADB" shell dumpsys activity services "$PKG" 2>/dev/null | grep -A5 LocationSharing || true

echo
echo "== logcat mg-loc (Ctrl+C to stop) =="
"$ADB" logcat -c
"$ADB" logcat -s mg-loc:D AndroidRuntime:E '*:S'
