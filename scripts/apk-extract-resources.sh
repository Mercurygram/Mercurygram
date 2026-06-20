#!/usr/bin/env bash
# apk-extract-resources.sh — extract resources from a cached APK with apktool
# for cross-checking jadx's resource output. Skips smali; resources only.
# Part of the local APK-disassembly / pre-source diff tooling (see apk-fetch.sh).
#
# Output:
#   build/decompiled/<versionName>/apktool/res/   Decoded resources
#   build/decompiled/<versionName>/apktool/AndroidManifest.xml
#
# Usage:
#   scripts/apk-extract-resources.sh                # most recent cached APK
#   scripts/apk-extract-resources.sh path/to/apk
#   scripts/apk-extract-resources.sh --force

set -euo pipefail

FORCE=0
APK=''
while [ $# -gt 0 ]; do
    case "$1" in
        --force) FORCE=1 ;;
        -h|--help) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*) echo "unknown arg: $1" >&2; exit 2 ;;
        *) APK="$1" ;;
    esac
    shift
done

repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

if [ -z "$APK" ]; then
    [ -f build/apk-cache/latest.json ] || { echo "no cached APK; run scripts/apk-fetch.sh first" >&2; exit 1; }
    APK=$(sed -n 's/.*"path": *"\([^"]*\)".*/\1/p' build/apk-cache/latest.json)
fi
[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }

sha=$(sha256sum "$APK" | cut -d' ' -f1)
meta="build/apk-cache/$sha.meta"
vn=''
if [ -f "$meta" ]; then
    vn=$(sed -n 's/^versionName=//p' "$meta")
fi
[ -n "$vn" ] || { echo "versionName not in $meta; re-run apk-fetch.sh first" >&2; exit 1; }

out="build/decompiled/$vn/apktool"
if [ -d "$out" ] && [ "$FORCE" -eq 0 ]; then
    echo "already extracted: $out (use --force to redo)" >&2
    exit 0
fi

if ! command -v apktool >/dev/null 2>&1; then
    echo "apktool not found in PATH. Install: https://apktool.org/" >&2
    exit 1
fi

rm -rf "$out"

# Resources ARE decoded (no -r): we need strings.xml et al. in human form.
apktool d -s -f -q -o "$out" "$APK"

echo "done: $out" >&2
printf '%s\n' "$out"
