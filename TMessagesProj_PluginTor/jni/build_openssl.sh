#!/bin/bash
# MG: Build OpenSSL (3.0 LTS) as a static library for tor.
#
# This sits alongside the in-tree boringssl, which the rest of the JNI surface
# (tgnet/tde2e/voip) keeps using — tor needs OpenSSL-specific TLS APIs
# (SSL_set_session_secret_cb, OSSL_HANDSHAKE_STATE, ...) that BoringSSL
# deliberately removed, so a single-crypto-lib build isn't possible.
# Output: openssl/build/${ANDROID_ABI}/{lib/libssl.a, lib/libcrypto.a, include/openssl/*.h}
#
# OpenSSL's Configure ships first-class Android targets (android-arm64,
# android-arm, android-x86, android-x86_64) that wire up the NDK clang
# wrapper given ANDROID_NDK_ROOT + API. Use those instead of generic
# cross-compile; saves us a lot of flag-tuning.
#
# Requires: NDK env var pointing to the NDK install.
set -e
_quiet_redir() { if [ "${QUIET_BUILD:-0}" = "1" ]; then "$@" > /dev/null; else "$@"; fi; }

if [ -z "$NDK" ]; then
    echo "Error: NDK env var not set. Run: export NDK=/path/to/ndk"
    exit 1
fi

ANDROID_API=21
BUILD_PLATFORM=linux-x86_64
LLVM_PREFIX="${NDK}/toolchains/llvm/prebuilt/${BUILD_PLATFORM}"
SOURCE_DIR="$(cd "$(dirname "$0")/openssl" && pwd)"

function build_one {
    local ARCH_NAME="$1"
    local OPENSSL_TARGET="$2"
    local PREFIX="${SOURCE_DIR}/build/${ARCH_NAME}"
    local BUILD_DIR="${SOURCE_DIR}/build_tmp_${ARCH_NAME}"
    local NDK_VERSION
    NDK_VERSION=$(basename "${NDK}")

    [ -f "${PREFIX}/lib/libssl.a" ] && [ -f "${PREFIX}/.ndk-${NDK_VERSION}" ] && return

    echo "Building openssl for ${ARCH_NAME}..."

    mkdir -p "${PREFIX}"
    rm -rf "${BUILD_DIR}"
    mkdir -p "${BUILD_DIR}"

    # OpenSSL's Configure must run inside the build dir but reads sources
    # from a relative path. Create symlinks.
    (
        cd "${BUILD_DIR}"
        export ANDROID_NDK_ROOT="${NDK}"
        export PATH="${LLVM_PREFIX}/bin:${PATH}"
        # util/mkbuildinf.pl bakes a "built on: <gmtime>" string into
        # libcrypto via crypto/buildinf.h. The string sits in a
        # SHF_MERGE|SHF_STRINGS .rodata pool — lld hash-orders that pool,
        # so a single-byte change in the timestamp reshuffles every
        # merged string's address → broad .text/.rela.dyn churn even
        # though section sizes stay constant. mkbuildinf.pl honours
        # SOURCE_DATE_EPOCH (line 19 of the perl script). Pin to 0; the
        # string is inert (tor never surfaces OPENSSL_built_on()).
        export SOURCE_DATE_EPOCH=0

        # -Os: size-optimized codegen (sub-1% perf hit on tor crypto path).
        # The chain of no-* flags drops ciphers/protocols tor never asks for.
        # --prefix / --openssldir / --libdir pinned to FIXED paths (not
        # ${PREFIX}): OpenSSL bakes OPENSSLDIR into libcrypto via AC_DEFINE,
        # so a per-ABI ${PREFIX} would embed the build-host's CWD →
        # libmgtor.so non-reproducible across containers. Real artifacts
        # land at ${PREFIX} via DESTDIR; the baked /etc/ssl string is
        # inert (tor never consults OPENSSLDIR).
        _quiet_redir "${SOURCE_DIR}/Configure" \
            "${OPENSSL_TARGET}" \
            -D__ANDROID_API__=${ANDROID_API} \
            -Wno-builtin-macro-redefined -D__FILE__=__FILE_NAME__ \
            -Os \
            no-shared \
            no-tests \
            no-engine \
            no-dso \
            no-asm \
            no-comp \
            no-dtls1 \
            no-ssl3 \
            no-zlib \
            no-weak-ssl-ciphers \
            no-srp no-psk no-cmp no-cms \
            no-rc2 no-rc4 no-rc5 no-md4 no-mdc2 no-rmd160 no-whirlpool \
            no-bf no-cast no-idea no-seed no-camellia \
            no-sm2 no-sm3 no-sm4 no-aria no-siphash \
            --prefix=/ \
            --openssldir=/etc/ssl \
            --libdir=lib

        _quiet_redir make -j"$(nproc)" build_libs
        _quiet_redir make DESTDIR="${PREFIX}" install_dev
    )

    # Drop debug symbols + local relocations from the static archives.
    "${LLVM_PREFIX}/bin/llvm-strip" --strip-unneeded "${PREFIX}/lib/libcrypto.a" "${PREFIX}/lib/libssl.a"

    rm -rf "${BUILD_DIR}"
    touch "${PREFIX}/.ndk-${NDK_VERSION}"
    echo "Done: ${PREFIX}/lib/libssl.a"
}

cd "${SOURCE_DIR}"

build_abi() {
    case "$1" in
        arm64-v8a)   build_one "arm64-v8a"   "android-arm64" ;;
        armeabi-v7a) build_one "armeabi-v7a" "android-arm" ;;
        x86)         build_one "x86"         "android-x86" ;;
        x86_64)      build_one "x86_64"      "android-x86_64" ;;
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

echo "openssl build complete."
