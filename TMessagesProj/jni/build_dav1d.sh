#!/bin/bash
# Build dav1d (AV1 decoder) for Android using meson cross-compilation.
# Requires: meson, ninja, NDK (set via NDK env var)
# Output: dav1d/build/${ANDROID_ABI}/lib/libdav1d.a
set -e
_quiet_redir() { if [ "${QUIET_BUILD:-0}" = "1" ]; then "$@" > /dev/null; else "$@"; fi; }

if [ -z "$NDK" ]; then
    echo "Error: NDK env var not set. Run: export NDK=/path/to/ndk"
    exit 1
fi

if ! command -v meson &>/dev/null; then
    echo "Error: meson not found. Install with: pip install meson"
    exit 1
fi

if ! command -v ninja &>/dev/null; then
    echo "Error: ninja not found. Install with your package manager."
    exit 1
fi

ANDROID_API=21
BUILD_PLATFORM=linux-x86_64
LLVM_PREFIX="${NDK}/toolchains/llvm/prebuilt/${BUILD_PLATFORM}"
LLVM_BIN="${LLVM_PREFIX}/bin"
SOURCE_DIR="$(cd "$(dirname "$0")/dav1d" && pwd)"

function build_one {
    local ARCH_NAME="$1"
    local MESON_CPU_FAMILY="$2"
    local MESON_CPU="$3"
    local ANDROID_TRIPLE="$4"
    local NASM_OPTS="$5"
    local PREFIX="${SOURCE_DIR}/build/${ARCH_NAME}"
    local BUILD_DIR="${SOURCE_DIR}/build_tmp_${ARCH_NAME}"
    local NDK_VERSION
    NDK_VERSION=$(basename "${NDK}")

    # Sentinel ties the cached .a to the NDK that built it; bumping NDK
    # invalidates stale archives on existing local checkouts (CI is clean).
    [ -f "${PREFIX}/lib/libdav1d.a" ] && [ -f "${PREFIX}/.ndk-${NDK_VERSION}" ] && return

    echo "Building dav1d for ${ARCH_NAME}..."

    mkdir -p "${PREFIX}"
    rm -rf "${BUILD_DIR}"
    mkdir -p "${BUILD_DIR}"

    local C_COMPILER="${LLVM_BIN}/${ANDROID_TRIPLE}${ANDROID_API}-clang"
    local SYSROOT="${LLVM_PREFIX}/sysroot"

    cat > "${BUILD_DIR}/android.cross" <<EOF
[host_machine]
system = 'android'
cpu_family = '${MESON_CPU_FAMILY}'
cpu = '${MESON_CPU}'
endian = 'little'

[properties]
sys_root = '${SYSROOT}'
needs_exe_wrapper = true

[built-in options]
c_args = ['-fPIC', '-DANDROID', '-D_LARGEFILE_SOURCE=1', '-Wno-builtin-macro-redefined', '-D__FILE__=__FILE_NAME__']
c_link_args = ['-fPIC']

[binaries]
c = '${C_COMPILER}'
ar = '${LLVM_BIN}/llvm-ar'
strip = '${LLVM_BIN}/llvm-strip'
pkgconfig = 'pkg-config'
EOF

    _quiet_redir meson setup \
        --cross-file "${BUILD_DIR}/android.cross" \
        --prefix "${PREFIX}" \
        --default-library static \
        --buildtype release \
        -Denable_tools=false \
        -Denable_tests=false \
        -Denable_docs=false \
        "${BUILD_DIR}" \
        "${SOURCE_DIR}"

    _quiet_redir ninja -C "${BUILD_DIR}"
    _quiet_redir meson install -C "${BUILD_DIR}" --no-rebuild

    rm -rf "${BUILD_DIR}"
    touch "${PREFIX}/.ndk-${NDK_VERSION}"
    echo "Done: ${PREFIX}/lib/libdav1d.a"
}

cd "${SOURCE_DIR}"

build_abi() {
    case "$1" in
        arm64-v8a)   build_one "arm64-v8a"   "aarch64" "armv8-a" "aarch64-linux-android" ;;
        armeabi-v7a) build_one "armeabi-v7a" "arm"     "armv7"   "armv7a-linux-androideabi" ;;
        x86)         build_one "x86"         "x86"     "i686"    "i686-linux-android" ;;
        x86_64)      build_one "x86_64"      "x86_64"  "x86_64"  "x86_64-linux-android" ;;
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

echo "dav1d build complete."
