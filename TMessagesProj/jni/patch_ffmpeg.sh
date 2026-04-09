#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Determine which ABIs to process (default: all 4)
if [ $# -eq 0 ]; then
    ABIS=(arm64-v8a armeabi-v7a x86 x86_64)
else
    ABIS=("$@")
fi

# Copy private headers into each ABI's build include directory,
# then apply C++ compatibility patches to the COPIES only (never to source).
# The ffmpeg source must remain unpatched for its own C compilation.
for ABI in "${ABIS[@]}"; do
    install -D ffmpeg/libavformat/dv.h        "ffmpeg/build/${ABI}/include/libavformat/dv.h"
    install -D ffmpeg/libavformat/isom.h      "ffmpeg/build/${ABI}/include/libavformat/isom.h"
    install -D ffmpeg/libavcodec/bytestream.h "ffmpeg/build/${ABI}/include/libavcodec/bytestream.h"
    install -D ffmpeg/libavcodec/get_bits.h   "ffmpeg/build/${ABI}/include/libavcodec/get_bits.h"
    install -D ffmpeg/libavcodec/golomb.h     "ffmpeg/build/${ABI}/include/libavcodec/golomb.h"
    install -D ffmpeg/libavcodec/vlc.h        "ffmpeg/build/${ABI}/include/libavcodec/vlc.h"
    install -D ffmpeg/libavutil/intmath.h     "ffmpeg/build/${ABI}/include/libavutil/intmath.h"

    # Apply patches to copies (--forward makes it idempotent: skip if already applied)
    (cd "ffmpeg/build/${ABI}/include" && \
        patch -p1 --forward < "${SCRIPT_DIR}/patches/ffmpeg/0001-compilation-magic.patch" 2>/dev/null || true)
    (cd "ffmpeg/build/${ABI}/include" && \
        patch -p1 --forward < "${SCRIPT_DIR}/patches/ffmpeg/0002-compilation-magic-2.patch" 2>/dev/null || true)
done

# Create the merged ffmpeg/include/ directory that voip/CMakeLists.txt expects.
# Only done once; use the first available ABI as the canonical header source.
if [ ! -f ffmpeg/include/dav1d/dav1d.h ]; then
    CANONICAL_ABI="${ABIS[0]}"
    rm -rf ffmpeg/include
    cp -r "ffmpeg/build/${CANONICAL_ABI}/include/." ffmpeg/include/
    # libvpx installs headers under vpx/; rename to libvpx/ to match upstream layout
    cp -r "libvpx/build/${CANONICAL_ABI}/include/vpx" ffmpeg/include/libvpx
    # dav1d headers
    cp -r "dav1d/build/${CANONICAL_ABI}/include/." ffmpeg/include/
    echo "ffmpeg/include/ created with libavutil, libavcodec, libvpx, dav1d headers"
fi
