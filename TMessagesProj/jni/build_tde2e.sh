#!/bin/bash
# Build tde2e and tdutils from tdlib/td submodule.
# Must run AFTER build_boringssl.sh (requires BoringSSL headers and libs).
# Run from TMessagesProj/jni/ with NDK and NINJA_PATH env vars set.

set -e
_quiet_redir() { if [ "${QUIET_BUILD:-0}" = "1" ]; then "$@" > /dev/null; else "$@"; fi; }

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JNI_DIR="$SCRIPT_DIR"
TD_DIR="$JNI_DIR/td"
BORINGSSL_DIR="$JNI_DIR/boringssl"

# ── Prerequisites ──────────────────────────────────────────────────────────────

if [[ ! -d "$TD_DIR" ]] || [[ -z "$(ls -A "$TD_DIR" 2>/dev/null)" ]]; then
    echo "Error: td submodule is empty. Run 'git submodule update --init TMessagesProj/jni/td' first."
    exit 1
fi

if [[ $# -eq 0 ]]; then
    ABIS_TO_BUILD=(arm64-v8a armeabi-v7a x86_64 x86)
else
    ABIS_TO_BUILD=("$@")
fi

for _abi in "${ABIS_TO_BUILD[@]}"; do
    if [[ ! -f "$BORINGSSL_DIR/build/$_abi/ssl/libssl.a" ]]; then
        echo "Error: BoringSSL not built for $_abi. Run build_boringssl.sh first."
        exit 1
    fi
done
unset _abi

if [[ -z "$NDK" ]]; then
    echo "Error: NDK environment variable is not set."
    exit 1
fi

if [[ -z "$NINJA_PATH" ]]; then
    echo "Error: NINJA_PATH environment variable is not set."
    exit 1
fi

# ── Step 1: Host build — regenerate td/td/generate/auto/ ─────────────────────
# TD_GENERATE_SOURCE_FILES=ON compiles the TL code generators and runs them,
# writing e2e_api.cpp/.h/.hpp (and all other TL output) into the source tree at
# td/td/generate/auto/. cmake returns early after this, before OpenSSL is needed.

if ! [ -d "$TD_DIR/td/generate/auto/" ]; then
    echo "=== tde2e: generating TL source files (host build) ==="
    HOST_BUILD_DIR="$TD_DIR/build/host"
    rm -rf "$HOST_BUILD_DIR"
    mkdir -p "$HOST_BUILD_DIR"
    cd "$HOST_BUILD_DIR"
    _quiet_redir cmake -DCMAKE_BUILD_TYPE=Release -DTD_GENERATE_SOURCE_FILES=ON "$TD_DIR"
    _quiet_redir cmake --build .
fi

# ── Step 2: Cross-compile for each Android ABI ────────────────────────────────
# BoringSSL is OpenSSL-compatible. We set OPENSSL_INCLUDE/CRYPTO/SSL vars directly
# so cmake's FindOpenSSL.cmake uses them without searching (bypassing the NDK
# toolchain's CMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY restriction).

NDK_VERSION=$(basename "$NDK")
for ABI in "${ABIS_TO_BUILD[@]}"; do
    BUILD_DIR="$TD_DIR/build/$ABI"

    # Sentinel ties the cached .a to the NDK that built it; bumping NDK
    # invalidates stale archives on existing local checkouts (CI is clean).
    [ -f "$BUILD_DIR/tde2e/libtde2e.a" ] && [ -f "$BUILD_DIR/.ndk-${NDK_VERSION}" ] && continue

    echo "=== tde2e: building $ABI ==="

    rm -rf "$BUILD_DIR"
    mkdir -p "$BUILD_DIR"
    cd "$BUILD_DIR"

    _quiet_redir cmake -DCMAKE_BUILD_TYPE=Release \
          -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
          -DANDROID_ABI="$ABI" \
          -DANDROID_PLATFORM=android-21 \
          -DANDROID_STL=c++_static \
          -DTD_E2E_ONLY=ON \
          -DOPENSSL_INCLUDE_DIR="$BORINGSSL_DIR/include" \
          -DOPENSSL_CRYPTO_LIBRARY="$BORINGSSL_DIR/build/$ABI/crypto/libcrypto.a" \
          -DOPENSSL_SSL_LIBRARY="$BORINGSSL_DIR/build/$ABI/ssl/libssl.a" \
          "-DCMAKE_C_FLAGS=-Wno-builtin-macro-redefined -D__FILE__=__FILE_NAME__" \
          "-DCMAKE_CXX_FLAGS=-Wno-builtin-macro-redefined -D__FILE__=__FILE_NAME__" \
          -GNinja -DCMAKE_MAKE_PROGRAM="$NINJA_PATH" \
          "$TD_DIR"

    _quiet_redir cmake --build .
    touch "$BUILD_DIR/.ndk-${NDK_VERSION}"
done

echo "=== tde2e: done ==="
