#!/bin/bash
# MG: Build the Tor Snowflake pluggable-transport client as a standalone
# Android executable, shipped as jni/<abi>/libsnowflake.so so AGP extracts it
# into nativeLibraryDir (the only dir Android lets an app exec from). The Tor
# daemon (run in-process via tor_run_main) forks/execs it through
# `--ClientTransportPlugin "snowflake exec <nativeLibDir>/libsnowflake.so ..."`.
#
# Snowflake is pure Go; this cross-compiles ./client from the jni/snowflake
# submodule with the NDK clang as the CGO C compiler. Output lands directly in
# jni/<abi>/ (the module's jniLibs.srcDirs root, build.gradle:92), NOT through
# CMake: it is a standalone binary, not a linked shared library.
#
# REPRODUCIBILITY (F-Droid ships bit-for-bit reproducible builds, so every
# native artifact must be byte-identical across hosts). Go embeds build paths,
# a build id, and module
# metadata by default, all host-dependent. Neutralised here with:
#   -trimpath            strip absolute module/GOPATH/GOROOT paths from the binary
#   -ldflags "-buildid=" clear the non-deterministic build id
#   -ldflags "-s -w"     drop symbol + DWARF tables (smaller, path-free)
#   -buildvcs=false      never stamp VCS revision/dirty state into the binary
#   GOFLAGS=-mod=mod     with a pinned Go toolchain + go.sum this is hermetic;
#                        switch to -mod=vendor if the submodule vendors deps
#   CGO_ENABLED=1        Android needs cgo for the runtime's TLS/net paths
#   SOURCE_DATE_EPOCH=0  inert for Go itself, kept for parity with the C scripts
#   GOTOOLCHAIN=<pin>    the Go compiler version DETERMINES the output bytes, so
#                        it is pinned here rather than left to whatever `go` the
#                        build host ships. Go >=1.21 honours this directive and
#                        transparently fetches the exact pinned toolchain (Go
#                        verifies it against the content hash baked into the go
#                        command), so the F-Droid buildserver, CI, a developer,
#                        and an independent verifier all compile with the same
#                        compiler regardless of their distro's Go package. The
#                        buildserver only needs SOME Go >=1.21 to bootstrap this
#                        (Debian trixie's golang-go qualifies; the recipe's
#                        sudo: apt line must install golang-go). Proven
#                        byte-identical across two clean builds (go clean -cache
#                        between) with this pin. Bump to the latest 1.24.x when
#                        the snowflake submodule's go.mod floor moves.
#
# Verify a built .so is byte-identical across two runs (and ideally two hosts)
# with `scripts/check-reproducibility.sh` before committing.
#
# Requires: NDK env var (same as build_tor.sh) and a Go >=1.21 on PATH (only as
# the GOTOOLCHAIN bootstrap; the actual compile uses the pinned version below).
# Run after the snowflake submodule is initialised (patchNativeSources / git
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
SOURCE_DIR="${JNI_DIR}/snowflake"
# ./client is snowflake's standalone PT executable (package main).
CLIENT_PKG="./client"

export CGO_ENABLED=1
export SOURCE_DATE_EPOCH=0
export GOFLAGS=-mod=mod
# Pinned Go compiler (see header). Bump alongside the snowflake submodule.
export GOTOOLCHAIN=go1.24.6

function build_one {
    local ARCH_NAME="$1"      # Android ABI dir name
    local GOARCH="$2"         # Go GOARCH
    local ANDROID_TRIPLE="$3" # NDK clang triple prefix
    local GOARM="$4"          # only for armeabi-v7a
    local OUT_DIR="${JNI_DIR}/${ARCH_NAME}"
    local OUT="${OUT_DIR}/libsnowflake.so"
    local STAMP_DIR="${SOURCE_DIR}/build/${ARCH_NAME}"
    local NDK_VERSION GO_VERSION
    NDK_VERSION=$(basename "${NDK}")
    GO_VERSION=$(go env GOVERSION)

    # Rebuild only if the binary or the toolchain/NDK stamp is missing/stale.
    if [ -f "${OUT}" ] && [ -f "${STAMP_DIR}/.ndk-${NDK_VERSION}-${GO_VERSION}" ]; then
        return
    fi

    if [ ! -d "${SOURCE_DIR}/client" ]; then
        echo "Error: snowflake submodule not initialised (${SOURCE_DIR}/client missing)." >&2
        echo "Run: git submodule update --init ${SOURCE_DIR}" >&2
        exit 1
    fi

    echo "Building snowflake for ${ARCH_NAME}..."
    mkdir -p "${OUT_DIR}" "${STAMP_DIR}"

    (
        cd "${SOURCE_DIR}"
        export GOOS=android
        export GOARCH="${GOARCH}"
        [ -n "${GOARM}" ] && export GOARM="${GOARM}"
        export CC="${LLVM_BIN}/${ANDROID_TRIPLE}${ANDROID_API}-clang"
        # -w -s: no DWARF/symtab; -buildid=: deterministic id. -trimpath +
        # -buildvcs=false strip host paths and VCS state.
        # -checklinkname=0: snowflake pulls github.com/wlynxg/anet, which uses
        # //go:linkname to reach net.zoneCache. Go 1.23+ rejects linkname to
        # unexported std symbols by default ("invalid reference to
        # net.zoneCache"); this escape hatch restores the pre-1.23 behaviour.
        # Same workaround Tor Browser / Orbot use for the snowflake client.
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

echo "snowflake build complete."
