#!/usr/bin/env bash
# apk-fetch.sh — download the official Telegram Android APK from telegram.org,
# pin its SHA-256, and probe versionCode/versionName via aapt2.
#
# Output:
#   build/apk-cache/<sha256>.apk
#   build/apk-cache/<sha256>.meta   (sha256, url, fetched_at, versionCode, versionName)
#   build/apk-cache/latest.json     (pointer to most recent fetch)
#
# First step of the local APK-disassembly / pre-source diff tooling: cache the
# official binary so apk-decompile.sh and apk-extract-resources.sh can run.
#
# Usage:
#   scripts/apk-fetch.sh                # fetch unless cached
#   scripts/apk-fetch.sh --force        # always re-fetch
#   scripts/apk-fetch.sh --url <url>    # alternative APK URL (e.g. a CDN mirror)

set -euo pipefail

URL='https://telegram.org/dl/android/apk'
FORCE=0
while [ $# -gt 0 ]; do
    case "$1" in
        --force) FORCE=1 ;;
        --url) URL="$2"; shift ;;
        -h|--help)
            sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
    shift
done

repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

cache_dir=build/apk-cache
mkdir -p "$cache_dir"

tmp=$(mktemp -p "$cache_dir" .fetch.XXXXXX.apk)
trap 'rm -f "$tmp"' EXIT

cond_args=()
if [ "$FORCE" -eq 0 ] && [ -f "$cache_dir/latest.json" ]; then
    # Server returns 304 → curl writes nothing → tmp stays empty (-s 0).
    # Saves ~80 MB on every iteration when the APK hasn't changed.
    cond_args=(--time-cond "$cache_dir/latest.json")
fi

echo "fetching: $URL" >&2
curl -fLsS "${cond_args[@]}" -o "$tmp" "$URL"

if [ ! -s "$tmp" ]; then
    echo "not modified — keeping cached APK" >&2
    rm -f "$tmp"
    trap - EXIT
    cat "$cache_dir/latest.json"
    sed -n 's/.*"path": *"\([^"]*\)".*/\1/p' "$cache_dir/latest.json"
    exit 0
fi

sha=$(sha256sum "$tmp" | cut -d' ' -f1)
dest="$cache_dir/$sha.apk"
meta="$cache_dir/$sha.meta"

if [ -f "$dest" ] && [ "$FORCE" -eq 0 ]; then
    echo "cached: $dest" >&2
    rm -f "$tmp"
    trap - EXIT
else
    mv "$tmp" "$dest"
    trap - EXIT
fi

# aapt2 ships with Android build-tools. Locate it.
aapt2=${AAPT2:-}
if [ -z "$aapt2" ]; then
    sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}
    aapt2=$(find "$sdk_root/build-tools" -maxdepth 2 -name aapt2 2>/dev/null | sort -V | tail -1 || true)
fi
if [ -z "$aapt2" ] || [ ! -x "$aapt2" ]; then
    echo "warning: aapt2 not found, skipping version probe" >&2
    aapt2=
fi

vc=''
vn=''
if [ -n "$aapt2" ]; then
    # Read first line only — versionCode and versionName both live there.
    # Avoid `... | head -1` because pipefail + SIGPIPE upstream = false failure.
    badging=$("$aapt2" dump badging "$dest" 2>/dev/null | sed -n '1p')
    vc=$(sed -nE "s/.*versionCode='([^']+)'.*/\1/p;q" <<<"$badging")
    vn=$(sed -nE "s/.*versionName='([^']+)'.*/\1/p;q" <<<"$badging")
fi

{
    printf 'sha256=%s\n' "$sha"
    printf 'url=%s\n' "$URL"
    printf 'fetched_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'versionCode=%s\n' "$vc"
    printf 'versionName=%s\n' "$vn"
    printf 'path=%s\n' "$dest"
} > "$meta"

cat > "$cache_dir/latest.json" <<EOF
{
    "sha256": "$sha",
    "url": "$URL",
    "versionCode": "$vc",
    "versionName": "$vn",
    "path": "$dest"
}
EOF

echo "saved:   $dest" >&2
echo "sha256:  $sha" >&2
echo "version: ${vn:-?} (code ${vc:-?})" >&2
printf '%s\n' "$dest"
