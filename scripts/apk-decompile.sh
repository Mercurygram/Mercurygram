#!/usr/bin/env bash
# apk-decompile.sh — decompile a cached Telegram APK with jadx. Part of the
# local APK-disassembly / pre-source diff tooling (see apk-fetch.sh).
#
# Output:
#   build/decompiled/<versionName>/jadx/sources/    Java source
#   build/decompiled/<versionName>/jadx/resources/  Resources (jadx view)
#   build/decompiled/<versionName>/manifest.txt     Decompile manifest
#
# Idempotent: re-runs are no-ops unless --force is given.
#
# Usage:
#   scripts/apk-decompile.sh                 # decompile most recent cached APK
#   scripts/apk-decompile.sh path/to/apk     # decompile specific APK
#   scripts/apk-decompile.sh --force         # overwrite existing output

set -euo pipefail

FORCE=0
APK=''
while [ $# -gt 0 ]; do
    case "$1" in
        --force) FORCE=1 ;;
        -h|--help)
            sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        -*) echo "unknown arg: $1" >&2; exit 2 ;;
        *) APK="$1" ;;
    esac
    shift
done

repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

if [ -z "$APK" ]; then
    if [ ! -f build/apk-cache/latest.json ]; then
        echo "no cached APK; run scripts/apk-fetch.sh first" >&2
        exit 1
    fi
    APK=$(sed -n 's/.*"path": *"\([^"]*\)".*/\1/p' build/apk-cache/latest.json)
fi
[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }

sha=$(sha256sum "$APK" | cut -d' ' -f1)
meta="build/apk-cache/$sha.meta"
vn=''
vc=''
if [ -f "$meta" ]; then
    vn=$(sed -n 's/^versionName=//p' "$meta")
    vc=$(sed -n 's/^versionCode=//p' "$meta")
fi
if [ -z "$vn" ]; then
    sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}
    aapt2=$(find "$sdk_root/build-tools" -maxdepth 2 -name aapt2 2>/dev/null | sort -V | tail -1 || true)
    [ -n "$aapt2" ] || { echo "aapt2 not found, cannot resolve version" >&2; exit 1; }
    badging=$("$aapt2" dump badging "$APK" 2>/dev/null | sed -n '1p')
    vn=$(sed -nE "s/.*versionName='([^']+)'.*/\1/p;q" <<<"$badging")
    vc=$(sed -nE "s/.*versionCode='([^']+)'.*/\1/p;q" <<<"$badging")
fi
[ -n "$vn" ] || { echo "could not resolve versionName for $APK" >&2; exit 1; }

out="build/decompiled/$vn/jadx"
if [ -d "$out" ] && [ "$FORCE" -eq 0 ]; then
    echo "already decompiled: $out (use --force to redo)" >&2
    exit 0
fi

if ! command -v jadx >/dev/null 2>&1; then
    echo "jadx not found in PATH. Install: https://github.com/skylot/jadx" >&2
    exit 1
fi

rm -rf "$out"
mkdir -p "$out"

echo "decompiling $APK -> $out" >&2
# Telegram APK has ~25k classes — bump JVM heap and cap thread count to keep
# peak RSS manageable. Caller may override JAVA_OPTS / JADX_THREADS.
export JAVA_OPTS="${JAVA_OPTS:--Xmx8g}"
threads="${JADX_THREADS:-4}"
jadx \
    --threads-count "$threads" \
    --no-imports \
    --show-bad-code \
    --respect-bytecode-access-modifiers \
    --output-dir "$out" \
    "$APK" \
    >"$out/jadx.log" 2>&1 || {
    rc=$?
    echo "jadx exited $rc (continuing — partial output may still be useful)" >&2
    echo "last 40 lines of log:" >&2
    tail -40 "$out/jadx.log" >&2 || true
}

{
    printf 'apk_path=%s\n' "$APK"
    printf 'apk_sha256=%s\n' "$sha"
    printf 'versionName=%s\n' "$vn"
    printf 'versionCode=%s\n' "$vc"
    printf 'jadx_version=%s\n' "$(jadx --version 2>/dev/null || echo unknown)"
    printf 'decompiled_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "build/decompiled/$vn/manifest.txt"

echo "done: $out" >&2
printf '%s\n' "$out"
