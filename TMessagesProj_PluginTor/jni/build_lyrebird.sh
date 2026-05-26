#!/bin/bash
# MG: Build the Tor lyrebird pluggable-transport client (the obfs4proxy
# successor) as a standalone Android executable, shipped as
# jni/<abi>/libobfs4proxy.so so AGP extracts it into nativeLibraryDir (the only
# dir Android lets an app exec from). The Tor daemon (run in-process via
# tor_run_main) forks/execs it through
# `--ClientTransportPlugin "obfs4 exec <nativeLibDir>/libobfs4proxy.so"`.
#
# lyrebird registers the obfs4 transport (plus obfs2/obfs3/scramblesuit/meek);
# Mercurygram only wires the obfs4 name in the Tor argv. Unlike Snowflake
# (zero-config), obfs4 needs user-supplied bridge lines, obtained out-of-band
# (e.g. Telegram's @GetBridgesBot) and pasted in the settings screen.
#
# lyrebird is pure Go; this cross-compiles ./cmd/lyrebird from the
# jni/lyrebird submodule with the NDK clang as the CGO C compiler. Output lands
# directly in jni/<abi>/ (the module's jniLibs.srcDirs root, build.gradle:92),
# NOT through CMake: it is a standalone binary, not a linked shared library.
#
# REPRODUCIBILITY (F-Droid ships bit-for-bit reproducible builds, see
# .claude/rules/jni-native.md). Same neutralisation as build_snowflake.sh:
#   -trimpath            strip absolute module/GOPATH/GOROOT paths from the binary
#   -ldflags "-buildid=" clear the non-deterministic build id
#   -ldflags "-s -w"     drop symbol + DWARF tables (smaller, path-free)
#   -buildvcs=false      never stamp VCS revision/dirty state into the binary
#   GOFLAGS=-mod=mod     with a pinned Go toolchain + go.sum this is hermetic
#   CGO_ENABLED=1        Android needs cgo for the runtime's TLS/net paths
#   SOURCE_DATE_EPOCH=0  inert for Go itself, kept for parity with the C scripts
#   GOTOOLCHAIN=<pin>    the Go compiler version DETERMINES the output bytes, so
#                        it is pinned here (see build_snowflake.sh header for the
#                        full rationale). Shared with build_snowflake.sh so a
#                        single golang-go bootstrap on the F-Droid buildserver
#                        covers both transports. lyrebird's go.mod floor
#                        (go 1.22.0) is below this pin, so it stays valid; bump
#                        alongside the snowflake pin.
#
# Verify a built .so is byte-identical across two runs (and ideally two hosts)
# with `scripts/check-reproducibility.sh` before committing.
#
# Requires: NDK env var (same as build_tor.sh) and a Go >=1.21 on PATH (only as
# the GOTOOLCHAIN bootstrap; the actual compile uses the pinned version below).
# Run after the lyrebird submodule is initialised (patchNativeSources / git
# submodule update).
set -e
_quiet_redir() { if [ "${QUIET_BUILD:-0}" = "1" ]; then "$@" > /dev/null; else "$@"; fi; }

if [ -z "$NDK" ]; then
    echo "Error: NDK env var not set. Run: export NDK=/path/to/ndk"
    exit 1
fi
if ! command -v go > /dev/null 2>&1; then
    echo "Error: go toolchain not found on PATH." >&2
    exit 1
fi

ANDROID_API=24
BUILD_PLATFORM=linux-x86_64
LLVM_BIN="${NDK}/toolchains/llvm/prebuilt/${BUILD_PLATFORM}/bin"
JNI_DIR="$(cd "$(dirname "$0")" && pwd)"
SOURCE_DIR="${JNI_DIR}/lyrebird"
# ./cmd/lyrebird is lyrebird's standalone PT executable (package main).
CLIENT_PKG="./cmd/lyrebird"

export CGO_ENABLED=1
export SOURCE_DATE_EPOCH=0
export GOFLAGS=-mod=mod
# Pinned Go compiler (see header). Shared with build_snowflake.sh.
export GOTOOLCHAIN=go1.24.6

function build_one {
    local ARCH_NAME="$1"      # Android ABI dir name
    local GOARCH="$2"         # Go GOARCH
    local ANDROID_TRIPLE="$3" # NDK clang triple prefix
    local GOARM="$4"          # only for armeabi-v7a
    local OUT_DIR="${JNI_DIR}/${ARCH_NAME}"
    local OUT="${OUT_DIR}/libobfs4proxy.so"
    local STAMP_DIR="${SOURCE_DIR}/build/${ARCH_NAME}"
    local NDK_VERSION GO_VERSION
    NDK_VERSION=$(basename "${NDK}")
    GO_VERSION=$(go env GOVERSION)

    # Rebuild only if the binary or the toolchain/NDK stamp is missing/stale.
    if [ -f "${OUT}" ] && [ -f "${STAMP_DIR}/.ndk-${NDK_VERSION}-${GO_VERSION}" ]; then
        return
    fi

    if [ ! -d "${SOURCE_DIR}/cmd/lyrebird" ]; then
        echo "Error: lyrebird submodule not initialised (${SOURCE_DIR}/cmd/lyrebird missing)." >&2
        echo "Run: git submodule update --init ${SOURCE_DIR}" >&2
        exit 1
    fi

    echo "Building lyrebird for ${ARCH_NAME}..."
    mkdir -p "${OUT_DIR}" "${STAMP_DIR}"

    (
        cd "${SOURCE_DIR}"
        export GOOS=android
        export GOARCH="${GOARCH}"
        [ -n "${GOARM}" ] && export GOARM="${GOARM}"
        export CC="${LLVM_BIN}/${ANDROID_TRIPLE}${ANDROID_API}-clang"
        # -w -s: no DWARF/symtab; -buildid=: deterministic id. -trimpath +
        # -buildvcs=false strip host paths and VCS state. -checklinkname=0
        # kept for parity with build_snowflake.sh (no-op when the source has
        # no //go:linkname to unexported std symbols; harmless otherwise).
        _quiet_redir go build \
            -trimpath \
            -buildvcs=false \
            -ldflags "-buildid= -s -w -checklinkname=0" \
            -o "${OUT}" \
            "${CLIENT_PKG}"
    )

    touch "${STAMP_DIR}/.ndk-${NDK_VERSION}-${GO_VERSION}"
    echo "Done: ${OUT}"
}

build_abi() {
    case "$1" in
        arm64-v8a)   build_one "arm64-v8a"   "arm64" "aarch64-linux-android"    "" ;;
        armeabi-v7a) build_one "armeabi-v7a" "arm"   "armv7a-linux-androideabi" "7" ;;
        x86)         build_one "x86"         "386"   "i686-linux-android"       "" ;;
        x86_64)      build_one "x86_64"      "amd64" "x86_64-linux-android"     "" ;;
        *) echo "Unknown ABI: $1" >&2; exit 1 ;;
    esac
}

if [ $# -eq 0 ]; then
    build_abi arm64-v8a
    build_abi armeabi-v7a
    build_abi x86
    build_abi x86_64
else
    for abi in "$@"; do
        build_abi "$abi"
    done
fi

echo "lyrebird build complete."
