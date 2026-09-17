#!/usr/bin/env bash
# Compile the widget sandbox JS bundles to the QuickJS bytecode (.qbc) the
# engine loads in place of the source.
#
# Upstream commits the .qbc files next to the bundles in
# assets/WidgetSandbox/WidgetJS; we generate them instead, with the same
# compiler widget-sandbox's build-native-bundles.ts uses: its qjsc.c built on
# the host against the vendored QuickJS. The bytecode depends only on the
# QuickJS sources and the bundle, not on the host compiler or its flags, so
# the output is byte-identical to upstream's. Every bundle that manifest.txt
# lists a .qbc for is compiled, and each result is checked against the
# manifest's SHA-256, which WidgetSandboxResources checks again at runtime.
#
# usage: build_widget_bytecode.sh <output assets dir>
set -Eeuo pipefail

OUT="${1:?usage: build_widget_bytecode.sh <output assets dir>}"
JNI_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGES="$JNI_DIR/twidget/widget-sandbox/packages"
QJS="$PACKAGES/ios/Sources/CQuickJS"
ASSETS="$JNI_DIR/../src/main/assets/WidgetSandbox"
BUILD="$JNI_DIR/prebuild/build/widget-qjsc"

rm -rf "$BUILD" "$OUT/WidgetSandbox"
mkdir -p "$BUILD" "$OUT/WidgetSandbox/WidgetJS"

"${CC:-cc}" -O2 -std=gnu11 -D_GNU_SOURCE -DQUICKJS_NG_BUILD \
    -I"$QJS/include" -I"$QJS/quickjs" -w \
    "$QJS/quickjs/quickjs.c" "$QJS/quickjs/dtoa.c" "$QJS/quickjs/libregexp.c" \
    "$QJS/quickjs/libunicode.c" "$QJS/shim/qjs_shim.c" "$PACKAGES/tools/qjsc/qjsc.c" \
    -lm -o "$BUILD/qjsc"

count=0
while read -r sha name; do
    [[ "$name" == *.qbc ]] || continue
    src="${name%.qbc}.js"
    out="$OUT/WidgetSandbox/WidgetJS/$name"
    mkdir -p "$(dirname "$out")"
    "$BUILD/qjsc" "$ASSETS/WidgetJS/$src" "$out" "$(basename "$src")" >/dev/null
    actual="$(sha256sum "$out" | cut -d' ' -f1)"
    if [[ "$actual" != "$sha" ]]; then
        echo "build_widget_bytecode.sh: $name does not match manifest.txt ($actual != $sha)" >&2
        exit 1
    fi
    count=$((count + 1))
done < "$ASSETS/manifest.txt"

if [[ "$count" == 0 ]]; then
    echo "build_widget_bytecode.sh: manifest.txt lists no .qbc" >&2
    exit 1
fi
echo "widget bytecode: $count .qbc built and verified"
