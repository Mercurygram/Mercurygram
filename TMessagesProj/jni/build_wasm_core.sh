#!/usr/bin/env bash
# Build the call core wasm module (tgcalls v2wasm, call protocol 19.0.0) and
# write it as voip/ReferenceCoreEmbedded.cpp, the byte array libtmessages.so
# links in.
#
# Upstream commits that file prebuilt; we generate it from the sources next
# to it instead. Flags, the wasi-sdk release and the object order match
# upstream's build, so the module is byte-identical to the one upstream
# ships: the file prefix map reproduces the source path upstream's assert
# strings carry, and the object order fixes the data section layout.
#
# usage: build_wasm_core.sh <wasi-sdk dir>
set -Eeuo pipefail

SDK="${1:?usage: build_wasm_core.sh <wasi-sdk dir>}"
JNI_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$JNI_DIR/voip/tgcalls"
OUT="$JNI_DIR/voip/ReferenceCoreEmbedded.cpp"
BUILD="$JNI_DIR/prebuild/build/wasm-core"

# Link order is significant, see above.
SOURCES="v2wasm/ReferenceCallCore.cpp v2wasm/SignalingFraming.cpp v2wasm/CoreGzip.cpp
         v2wasm/wasm_module_entry.cpp third-party/json11.cpp v2wasm/reference_core_factory.cpp"
FLAGS="--target=wasm32-wasip1 -Oz -flto -fno-exceptions -fno-rtti"

rm -rf "$BUILD"
mkdir -p "$BUILD"
objs=""
for f in $SOURCES; do
    o="$BUILD/$(basename "$f" .cpp).o"
    "$SDK/bin/clang++" $FLAGS -std=c++17 \
        -ffile-prefix-map="$SRC=submodules/TgVoipWebrtc/tgcalls/tgcalls" \
        -I"$SRC" -c "$SRC/$f" -o "$o"
    objs="$objs $o"
done
"$SDK/bin/clang++" $FLAGS -mexec-model=reactor $objs -o "$BUILD/core.wasm" -Wl,--strip-all

python3 - "$BUILD/core.wasm" "$OUT.tmp" <<'EOF'
import sys
data = open(sys.argv[1], 'rb').read()
rows = ''.join('    ' + ','.join('0x%02x' % b for b in data[i:i + 16]) + ',\n'
               for i in range(0, len(data), 16))
open(sys.argv[2], 'w').write(
    '#include "v2wasm/EmbeddedCoreModule.h"\n\n'
    'namespace tgcalls {\nnamespace v2wasm {\n\n'
    'extern const uint8_t kReferenceCoreWasm[] = {\n' + rows + '};\n'
    'extern const size_t kReferenceCoreWasmSize = %d;\n\n' % len(data) +
    '} // namespace v2wasm\n} // namespace tgcalls\n')
EOF
mv "$OUT.tmp" "$OUT"
echo "wasm core: $(stat -c %s "$BUILD/core.wasm") bytes -> $OUT"
