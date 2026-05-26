#!/bin/bash
# MG: Build tor as a static library for embedding via tor_jni.c.
#
# Reuses the in-tree boringssl (jni/boringssl) for crypto + the in-tree libevent
# (jni/libevent, built by build_libevent.sh). Output: a libtor.a archive
# exposing tor_run_main() (declared in src/feature/api/tor_api.h) plus the
# auxiliary helper archives tor needs at link time (libor_*.a). The downstream
# JNI shim (jni/tor_jni.c) links all of them into libtmessages.so via the wiring
# that needs to be added to jni/CMakeLists.txt — see notes at the bottom of
# this file.
#
# SCAFFOLD STATUS: deliberate first cut. The autotools flags below are the
# guardianproject tor-android recipe distilled to what's needed when boringssl
# + libevent are already in tree. Expected iteration points:
#   - tor's autoconf macros want `--enable-static-libevent --enable-static-tor`
#     plus EVENT_DIR / OPENSSL_DIR. The exact flag set varies across the 0.4.x
#     release line; if configure fails on these, fall back to pkg-config style
#     EVENT_CFLAGS/EVENT_LIBS/OPENSSL_CFLAGS/OPENSSL_LIBS env-var injection.
#   - tor expects libcrypto with the full OpenSSL API. BoringSSL ships most
#     of it, but a handful of EVP_* / ENGINE_* symbols are stubbed.
#     `patch_tor.sh` (TODO: create alongside this file once concrete failures
#     surface) should reapply the same shim approach build_tde2e.sh uses.
#   - zlib comes from the NDK sysroot via `-lz`; no need to vendor it.
#   - On Android, tor's check for `getentropy` / `arc4random_buf` may
#     misdetect when sysroot's headers are present but the symbol isn't yet
#     in API 21. If so, add `--disable-getentropy --disable-rand-procps`.
#   - The output is `src/libtor.a` (current 0.4.8) but past releases put it
#     under `src/feature/api/libtor.a`. Adjust the install rule accordingly.
#
# Requires: NDK env var (same as build_dav1d.sh). Run after build_boringssl.sh
# and build_libevent.sh have populated their respective build/${ABI}/ trees.
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
JNI_DIR="$(cd "$(dirname "$0")" && pwd)"
SOURCE_DIR="${JNI_DIR}/tor"
LIBEVENT_PREFIX_ROOT="${JNI_DIR}/libevent/build"
OPENSSL_PREFIX_ROOT="${JNI_DIR}/openssl/build"

function build_one {
    local ARCH_NAME="$1"
    local ANDROID_TRIPLE="$2"
    local CONFIGURE_HOST="$3"
    local PREFIX="${SOURCE_DIR}/build/${ARCH_NAME}"
    local BUILD_DIR="${SOURCE_DIR}/build_tmp_${ARCH_NAME}"
    local LIBEVENT_PREFIX="${LIBEVENT_PREFIX_ROOT}/${ARCH_NAME}"
    local OPENSSL_PREFIX="${OPENSSL_PREFIX_ROOT}/${ARCH_NAME}"
    local NDK_VERSION
    NDK_VERSION=$(basename "${NDK}")

    [ -f "${PREFIX}/lib/libtor.a" ] && [ -f "${PREFIX}/.ndk-${NDK_VERSION}" ] && return

    if [ ! -f "${LIBEVENT_PREFIX}/lib/libevent.a" ]; then
        echo "Error: libevent ${ARCH_NAME} not built. Run build_libevent.sh first." >&2
        exit 1
    fi
    if [ ! -f "${OPENSSL_PREFIX}/lib/libssl.a" ]; then
        echo "Error: openssl ${ARCH_NAME} not built. Run build_openssl.sh first." >&2
        exit 1
    fi

    echo "Building tor for ${ARCH_NAME}..."

    mkdir -p "${PREFIX}/lib" "${PREFIX}/include"
    rm -rf "${BUILD_DIR}"
    mkdir -p "${BUILD_DIR}"

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
        export CFLAGS="-Os -fPIC -DANDROID -D_LARGEFILE_SOURCE=1 -Wno-builtin-macro-redefined -D__FILE__=__FILE_NAME__ --sysroot=${SYSROOT} -I${LIBEVENT_PREFIX}/include -I${OPENSSL_PREFIX}/include"
        export LDFLAGS="-fPIC --sysroot=${SYSROOT} -L${LIBEVENT_PREFIX}/lib -L${OPENSSL_PREFIX}/lib"

        # NOTE: tor's configure script accepts --with-libevent-dir / --with-openssl-dir
        # since 0.2.x. The combination below mirrors the guardianproject script.
        # --prefix / --sysconfdir / --localstatedir / --bindir / --datadir
        # pinned to FIXED paths (not ${PREFIX}): configure.ac bakes CONFDIR,
        # BINDIR, LOCALSTATEDIR, DATADIR into orconfig.h via AC_DEFINE_UNQUOTED,
        # so a per-ABI ${PREFIX} would embed the build-host's CWD in libtor.a
        # → libmgtor.so non-reproducible across containers. Strings are inert
        # at runtime (MgTorController drives torrc programmatically).
        _quiet_redir "${SOURCE_DIR}/configure" \
            --host="${CONFIGURE_HOST}" \
            --prefix=/usr/local \
            --sysconfdir=/etc \
            --localstatedir=/var \
            --bindir=/usr/local/bin \
            --datadir=/usr/local/share \
            --enable-static-libevent \
            --enable-static-openssl \
            --with-libevent-dir="${LIBEVENT_PREFIX}" \
            --with-openssl-dir="${OPENSSL_PREFIX}" \
            --disable-asciidoc \
            --disable-systemd \
            --disable-lzma \
            --disable-zstd \
            --disable-seccomp \
            --disable-libscrypt \
            --disable-unittests \
            --disable-tool-name-check \
            --disable-module-relay \
            --disable-module-dirauth \
            --disable-html-manual \
            --disable-manpage \
            --disable-system-torrc

        # tor's automake produces `libtor.a` at the build root (combine_libs
        # bundles src/lib/**/*.a + src/core + src/feature into one archive)
        # plus `src/app/tor` as the binary. We only want the library.
        _quiet_redir make -j"$(nproc)" libtor.a
    )

    # tor's combine_libs script bundles every TOR_INTERNAL_LIBS .a into a
    # single libtor.a at the build root via llvm-ar's MRI mode. That single
    # archive is all the JNI shim needs to link tor_run_main(). Copy it +
    # the public tor_api.h header.
    cp "${BUILD_DIR}/libtor.a" "${PREFIX}/lib/libtor.a"
    cp "${SOURCE_DIR}/src/feature/api/tor_api.h" "${PREFIX}/include/"

    "${LLVM_BIN}/llvm-strip" --strip-unneeded "${PREFIX}/lib/libtor.a"

    rm -rf "${BUILD_DIR}"
    touch "${PREFIX}/.ndk-${NDK_VERSION}"
    echo "Done: ${PREFIX}/lib/libtor.a"
}

cd "${SOURCE_DIR}"

build_abi() {
    case "$1" in
        arm64-v8a)   build_one "arm64-v8a"   "aarch64-linux-android"    "aarch64-linux-android" ;;
        armeabi-v7a) build_one "armeabi-v7a" "armv7a-linux-androideabi" "arm-linux-androideabi" ;;
        x86)         build_one "x86"         "i686-linux-android"       "i686-linux-android" ;;
        x86_64)      build_one "x86_64"      "x86_64-linux-android"     "x86_64-linux-android" ;;
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

echo "tor build complete."

# CMake wiring lives in jni/CMakeLists.txt (mg_tor* IMPORTED libs + the
# `mgtor` SHARED target at the bottom). The tor stack ships as its own
# libmgtor.so to keep OpenSSL out of libtmessages.so's BoringSSL symbol
# space — see the comment block above the mg_tor IMPORTED block.
