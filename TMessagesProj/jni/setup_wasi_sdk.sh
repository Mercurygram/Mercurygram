#!/usr/bin/env bash
# Install the pinned wasi-sdk used by jni/build_wasm_core.sh.
#
# Upstream links the call core wasm module into libtmessages.so as a prebuilt
# byte array (voip/ReferenceCoreEmbedded.cpp). We build that module from its
# sources instead, which needs the same wasi-sdk release upstream uses: a
# different clang or wasi-libc emits a different module for identical sources.
# The release and its checksum are pinned by build.gradle. Idempotent: exits
# immediately once the pinned release is unpacked.
set -Eeuo pipefail

VERSION="${1:?usage: setup_wasi_sdk.sh <version> <sha256>}"
SHA256="${2:?usage: setup_wasi_sdk.sh <version> <sha256>}"
ROOT="${WASI_SDK_ROOT:-/var/tmp/mg-wasi-sdk}"
DIR="$ROOT/wasi-sdk-$VERSION"

if [ -x "$DIR/bin/clang++" ]; then
    echo "wasi-sdk $VERSION ready in $DIR"
    exit 0
fi

case "$(uname -m)" in
    x86_64) arch=x86_64 ;;
    aarch64) arch=arm64 ;;
    *) echo "unsupported host: $(uname -m)" >&2; exit 1 ;;
esac
if [ "$arch" != x86_64 ]; then
    # Only the x86_64 checksum is pinned.
    echo "wasi-sdk: only x86_64 Linux hosts are supported" >&2
    exit 1
fi

name="wasi-sdk-$VERSION-$arch-linux"
url="https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-${VERSION%%.*}/$name.tar.gz"
mkdir -p "$ROOT"
tmp="$(mktemp -d "$ROOT/.dl.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT
curl -sSfL -o "$tmp/sdk.tar.gz" "$url"
echo "$SHA256  $tmp/sdk.tar.gz" | sha256sum -c -
tar -xzf "$tmp/sdk.tar.gz" -C "$tmp"
rm -rf "$DIR"
mv "$tmp/$name" "$DIR"

echo "wasi-sdk $VERSION ready in $DIR"
