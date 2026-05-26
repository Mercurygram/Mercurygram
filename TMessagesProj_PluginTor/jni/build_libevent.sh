#!/bin/bash
# MG: Build libevent (event loop) for Android using autotools cross-compilation.
# Required by jni/build_tor.sh — tor links against libevent_pthreads + libevent.
# Output: libevent/build/${ANDROID_ABI}/lib/libevent.a (+ libevent_pthreads.a, libevent_core.a)
#
# SCAFFOLD STATUS: written in the same shape as build_boringssl.sh.
# The configure flags are the standard
# libevent-on-android recipe but have NOT been verified on Mercurygram's NDK
# pin (r27.2.12479018) in this session. Expect to iterate on:
#   - `--disable-openssl` (we link tor against the in-tree boringssl, libevent
#     does not need its own openssl)
#   - `--enable-static --disable-shared` (tor wants static libevent)
#   - cross-compile cache file vs. plain CC/AR/RANLIB env (some libevent
#     versions need CONFIG_SITE for the cross sysroot)
#
# Requires: NDK env var pointing to the NDK install (same as build_boringssl.sh).
set -e
_quiet_redir() { if [ "${QUIET_BUILD:-0}" = "1" ]; then "$@" > /dev/null; else "$@"; fi; }

if [ -z "$NDK" ]; then
    echo "Error: NDK env var not set. Run: export NDK=/path/to/ndk"
    exit 1
fi

ANDROID_API=21
BUILD_PLATFORM=linux-x86_64
LLVM_PREFIX="${NDK}/toolchains/llvm/prebuilt/${BUILD_PLATFORM}"
LLVM_BIN="${LLVM_PREFIX}/bin"
SOURCE_DIR="$(cd "$(dirname "$0")/libevent" && pwd)"

function build_one {
    local ARCH_NAME="$1"
    local ANDROID_TRIPLE="$2"
    local CONFIGURE_HOST="$3"
    local PREFIX="${SOURCE_DIR}/build/${ARCH_NAME}"
    local BUILD_DIR="${SOURCE_DIR}/build_tmp_${ARCH_NAME}"
    local NDK_VERSION
    NDK_VERSION=$(basename "${NDK}")

    [ -f "${PREFIX}/lib/libevent.a" ] && [ -f "${PREFIX}/.ndk-${NDK_VERSION}" ] && return

    echo "Building libevent for ${ARCH_NAME}..."

    mkdir -p "${PREFIX}"
    rm -rf "${BUILD_DIR}"
    mkdir -p "${BUILD_DIR}"

    # Autogen (libevent ships only configure.ac in the submodule)
    if [ ! -f "${SOURCE_DIR}/configure" ]; then
        (cd "${SOURCE_DIR}" && _quiet_redir ./autogen.sh)
    fi

    local SYSROOT="${LLVM_PREFIX}/sysroot"

    (
        cd "${BUILD_DIR}"
        export CC="${LLVM_BIN}/${ANDROID_TRIPLE}${ANDROID_API}-clang"
        export AR="${LLVM_BIN}/llvm-ar"
        export RANLIB="${LLVM_BIN}/llvm-ranlib"
        export STRIP="${LLVM_BIN}/llvm-strip"
        export CFLAGS="-Os -fPIC -DANDROID -D_LARGEFILE_SOURCE=1 -Wno-builtin-macro-redefined -D__FILE__=__FILE_NAME__ --sysroot=${SYSROOT}"
        export LDFLAGS="-fPIC --sysroot=${SYSROOT}"

        _quiet_redir "${SOURCE_DIR}/configure" \
            --host="${CONFIGURE_HOST}" \
            --prefix="${PREFIX}" \
            --enable-static \
            --disable-shared \
            --disable-openssl \
            --disable-libevent-regress \
            --disable-samples \
            --disable-debug-mode

        _quiet_redir make -j"$(nproc)"
        _quiet_redir make install
    )

    "${LLVM_BIN}/llvm-strip" --strip-unneeded "${PREFIX}/lib/libevent.a" "${PREFIX}/lib/libevent_core.a" "${PREFIX}/lib/libevent_extra.a" "${PREFIX}/lib/libevent_pthreads.a"

    rm -rf "${BUILD_DIR}"
    touch "${PREFIX}/.ndk-${NDK_VERSION}"
    echo "Done: ${PREFIX}/lib/libevent.a"
}

cd "${SOURCE_DIR}"

build_abi() {
    case "$1" in
        arm64-v8a)   build_one "arm64-v8a"   "aarch64-linux-android"  "aarch64-linux-android" ;;
        armeabi-v7a) build_one "armeabi-v7a" "armv7a-linux-androideabi" "arm-linux-androideabi" ;;
        x86)         build_one "x86"         "i686-linux-android"     "i686-linux-android" ;;
        x86_64)      build_one "x86_64"      "x86_64-linux-android"   "x86_64-linux-android" ;;
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

echo "libevent build complete."
