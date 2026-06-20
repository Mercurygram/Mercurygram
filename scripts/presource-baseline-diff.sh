#!/usr/bin/env bash
# presource-baseline-diff.sh — produce an [UP] patch series from a decompiled
# Telegram APK, filtered against MG/TF-owned paths.
#
# How it works:
#   1. Resolve the decompiled tree (default: most recent build/decompiled/<vn>/).
#   2. Build a "synthetic source tree" in build/presource-synth/<vn>/ that
#      mirrors the layout of TMessagesProj/src/main/{java,res,AndroidManifest.xml}
#      using jadx's Java output and apktool's resource output.
#   3. Diff the synthetic tree against --upstream-ref (default: upstream/master)
#      restricted to the paths that exist in the synthetic tree, then strip MG/TF
#      paths via the exclusion list in scripts/lib/mg-paths.sh.
#   4. Emit a single unified diff (build/presource-patches/<vn>.patch) plus a
#      summary listing per-file change counts. Maintainer reviews and commits
#      as [UP] commits on a pre-source/<upstream-version> branch.
#
# This is NOT a one-click ports — decompiled Java has synthetic identifiers,
# missing generic info, and may not compile. Treat output as a guided review.
#
# Final step of the local APK-disassembly / pre-source diff tooling: run after
# apk-fetch.sh + apk-decompile.sh + apk-extract-resources.sh.
#
# Usage:
#   scripts/presource-baseline-diff.sh \
#       [--upstream-ref REF] \
#       [--decompiled DIR] \
#       [--output DIR]

set -euo pipefail

UPSTREAM_REF='upstream/master'
DECOMPILED=''
OUTPUT=''
while [ $# -gt 0 ]; do
    case "$1" in
        --upstream-ref) UPSTREAM_REF="$2"; shift ;;
        --decompiled) DECOMPILED="$2"; shift ;;
        --output) OUTPUT="$2"; shift ;;
        -h|--help) sed -n '2,28p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
    shift
done

repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

# shellcheck source=lib/mg-paths.sh
. "$(git rev-parse --show-toplevel)/scripts/lib/mg-paths.sh"
EXCLUDE_PATHS=("${MG_OWNED_PATHS[@]}")
SHARED_CONFIG="${MG_HOOK_FILES[0]}"

if [ -z "$DECOMPILED" ] && [ -d build/decompiled ]; then
    # Most recent versionName in build/decompiled/ that has both jadx and apktool output.
    while IFS= read -r d; do
        if [ -d "build/decompiled/$d/jadx/sources" ] && [ -d "build/decompiled/$d/apktool/res" ]; then
            DECOMPILED="build/decompiled/$d"
            break
        fi
    done < <(find build/decompiled -mindepth 1 -maxdepth 1 -type d -printf '%T@ %f\n' 2>/dev/null | sort -nr | cut -d' ' -f2-)
fi
[ -n "$DECOMPILED" ] && [ -d "$DECOMPILED" ] || {
    echo "no decompiled tree found; run apk-decompile.sh + apk-extract-resources.sh first" >&2
    exit 1
}
vn=$(basename "$DECOMPILED")
echo "using decompiled tree: $DECOMPILED (versionName=$vn)" >&2

[ -d "$DECOMPILED/jadx/sources" ] || { echo "missing $DECOMPILED/jadx/sources — run apk-decompile.sh" >&2; exit 1; }
[ -d "$DECOMPILED/apktool/res" ]  || { echo "missing $DECOMPILED/apktool/res — run apk-extract-resources.sh" >&2; exit 1; }

synth="build/presource-synth/$vn"
rm -rf "$synth"
mkdir -p "$synth/TMessagesProj/src/main/java" "$synth/TMessagesProj/src/main/res"

# jadx dumps third-party code (kotlin runtime, AndroidX, commonmark, webrtc,
# aspectj, …) that's not in Telegram source. Keeping only org/telegram/* cuts
# the noise by 10× and avoids garbage diffs against shaded libraries.
java_src_root="$DECOMPILED/jadx/sources"
if [ -d "$java_src_root/org/telegram" ]; then
    mkdir -p "$synth/TMessagesProj/src/main/java/org"
    cp -al "$java_src_root/org/telegram" "$synth/TMessagesProj/src/main/java/org/telegram"
fi

# Skip binary or AAPT-compiled resource buckets (drawable*, mipmap*, font*,
# raw*, assets*, lib*); keep XML-class ones.
res_keep_re='^(values|xml|layout|menu|anim|animator|color|navigation|transition|interpolator)(-.*)?$'
apk_res="$DECOMPILED/apktool/res"
if [ -d "$apk_res" ]; then
    for d in "$apk_res"/*; do
        [ -d "$d" ] || continue
        base=$(basename "$d")
        [[ "$base" =~ $res_keep_re ]] || continue
        cp -al "$d" "$synth/TMessagesProj/src/main/res/"
    done
fi
if [ -f "$DECOMPILED/apktool/AndroidManifest.xml" ]; then
    cp -l "$DECOMPILED/apktool/AndroidManifest.xml" "$synth/TMessagesProj/src/main/AndroidManifest.xml"
fi

[ -z "$OUTPUT" ] && OUTPUT="build/presource-patches"
mkdir -p "$OUTPUT"
patch_file="$OUTPUT/$vn.patch"
summary_file="$OUTPUT/$vn.summary"

# Make sure the upstream ref is fetched
if ! git rev-parse --quiet --verify "$UPSTREAM_REF" >/dev/null; then
    echo "upstream ref '$UPSTREAM_REF' not resolvable; try 'git fetch upstream' first" >&2
    exit 1
fi

upstream_worktree=$(mktemp -d -p build/ .upstream.XXXXXX)
trap 'rm -rf "$upstream_worktree"' EXIT
git --work-tree="$upstream_worktree" checkout "$UPSTREAM_REF" -- TMessagesProj >/dev/null 2>&1 || {
    echo "could not checkout TMessagesProj at $UPSTREAM_REF into worktree" >&2
    exit 1
}

# Only diff paths that exist in synth — MG/TF "deletions" from the upstream
# side aren't real deletions, just not present in the decompiled APK.
synth_paths=$(cd "$synth" && find TMessagesProj -type f | sort)

# Build the exclude grep pattern. Only `.` needs escaping for ERE;
# `/` is a literal character.
exclude_pattern=$(printf '%s\n' "${EXCLUDE_PATHS[@]}" | sed 's|\.|\\.|g' | paste -sd'|' -)

printf '%s\n' "$synth_paths" \
    | grep -vE "^($exclude_pattern)" \
    > "$OUTPUT/$vn.files"

: > "$patch_file"
total_files=0
changed_files=0
while IFS= read -r rel; do
    total_files=$((total_files + 1))
    upstream_file="$upstream_worktree/$rel"
    synth_file="$synth/$rel"
    if [ ! -f "$upstream_file" ]; then
        {
            printf 'diff --git a/%s b/%s\nnew file mode 100644\n--- /dev/null\n+++ b/%s\n' "$rel" "$rel" "$rel"
            sed 's/^/+/' "$synth_file"
        } >> "$patch_file"
        changed_files=$((changed_files + 1))
        continue
    fi
    # diff returns 1 on differences, 2 on error; ignore both — empty output
    # means no change.
    out=$(diff -u --label "a/$rel" --label "b/$rel" "$upstream_file" "$synth_file" || true)
    if [ -n "$out" ]; then
        printf 'diff --git a/%s b/%s\n%s\n' "$rel" "$rel" "$(printf '%s\n' "$out" | tail -n +2)" >> "$patch_file"
        changed_files=$((changed_files + 1))
    fi
done < "$OUTPUT/$vn.files"

# 4. Semantic strings.xml diff (apktool sorts alphabetically — line diff is
# useless, but a name-set diff is exactly what translation prep needs).
#
# Apktool's strings.xml is the COMPILED resource table, which includes
# entries injected by every AAR the upstream APK links against
# (AndroidX/AppCompat, Material, Cast/Chromecast, Firebase, Wallet, etc.).
# Comparing it against upstream's hand-authored strings.xml therefore
# surfaces hundreds of library strings as "new" — and several of those
# carry proprietary references that the [TF] de-googling explicitly
# excludes. Treating the raw diff as ports-of-interest would re-inject
# the very dependencies [TF] removes.
#
# Triple filter applied to the ADDED set:
#   (1) Key must be PascalCase. In upstream's hand-authored strings.xml
#       only 4 / 9242 keys are snake_case (all plural quantity keys),
#       whereas AAR-injected keys are almost universally snake_case.
#   (2) Key must not start with a known library prefix (defence in depth
#       for the rare PascalCase library string).
#   (3) Element body must not contain proprietary refs (google, firebase,
#       play services, cast, huawei, amazon, samsung, microsoft, fb,
#       googleusercontent, gms).
#
# Keys filtered out by any layer are emitted separately so a maintainer
# can audit what was skipped.
strings_diff="$OUTPUT/$vn.new-strings.txt"
: > "$strings_diff"
strings_rel='TMessagesProj/src/main/res/values/strings.xml'
upstream_strings="$upstream_worktree/$strings_rel"
synth_strings="$synth/$strings_rel"

if [ -f "$upstream_strings" ] && [ -f "$synth_strings" ]; then
    python3 - "$upstream_strings" "$synth_strings" "$strings_diff" <<'PYEOF'
import re, sys

upstream_path, synth_path, out_path = sys.argv[1:4]

# Triple filter:
#  (1) PascalCase — Telegram's own strings, not AAR snake_case noise.
#  (2) Block known library prefixes even when PascalCase.
#  (3) Element body must not name a proprietary platform/service.
LIB_NAME_RE = re.compile(r'^(Abc|Mtrl|Material|Androidx|Android[A-Z]|Common|Firebase|Fcm|Gcm|Gms|Google|Play|Exo|Cast|Mr|Tooltip|Preference|Paging|Lifecycle|Webview|Wallet|Crash|Fingerprint|Cardview)')
PROP_VALUE_RE = re.compile(r'[Gg]oogle|[Gg]mail|[Ff]irebase|[Cc]ast |[Cc]hromecast|[Hh]uawei|[Aa]mazon|[Ss]amsung|[Mm]icrosoft|[Pp]lay [Ss]tore|googleusercontent|[Gg]ms|[Aa]ndroid [Pp]ay|[Aa]pple [Pp]ay')

NAME_RE = re.compile(r'name="([A-Za-z0-9_]+)"')

def keys(path):
    with open(path) as f:
        return set(NAME_RE.findall(f.read()))

upstream = keys(upstream_path)
synth = keys(synth_path)
added = synth - upstream
removed = upstream - synth

with open(synth_path) as f:
    body = f.read()
bodies = {}
elem_re = re.compile(
    r'<(string|plurals|string-array)\s+name="([A-Za-z0-9_]+)"[^>]*(?:/>|>(?:.|\n)*?</\1>)')
for m in elem_re.finditer(body):
    bodies[m.group(2)] = m.group(0)

kept, dropped_case, dropped_name, dropped_value = [], [], [], []
for k in sorted(added):
    if not k[:1].isupper():
        dropped_case.append(k); continue
    if LIB_NAME_RE.match(k):
        dropped_name.append(k); continue
    if PROP_VALUE_RE.search(bodies.get(k, '')):
        dropped_value.append(k); continue
    kept.append(k)

def emit(section, items):
    out.write(f'\n# {section}:\n')
    for k in items:
        out.write(f'name="{k}"\n')

with open(out_path, 'w') as out:
    emit('strings keys ADDED (Telegram-side, after triple filter)', kept)
    emit('strings keys REMOVED (upstream has them, APK lost them)', sorted(removed))
    emit('DROPPED by case filter (snake_case — library/AAR)', dropped_case)
    emit('DROPPED by name filter (PascalCase library prefix)', dropped_name)
    emit('DROPPED by value scan (proprietary reference in body)', dropped_value)
PYEOF
fi

{
    printf 'pre-source diff summary\n'
    printf '  versionName:   %s\n' "$vn"
    printf '  upstream ref:  %s (%s)\n' "$UPSTREAM_REF" "$(git rev-parse --short "$UPSTREAM_REF")"
    printf '  total files:   %d\n' "$total_files"
    printf '  changed files: %d (jadx noise — review selectively)\n' "$changed_files"
    printf '  patch:         %s\n' "$patch_file"
    if [ -f "$strings_diff" ]; then
        printf '  strings diff:  %s\n' "$strings_diff"
    fi
    printf '\nMG/TF paths excluded:\n'
    for p in "${EXCLUDE_PATHS[@]}"; do printf '  %s\n' "$p"; done
    printf '\nReminder: review %s before committing as [UP] commits.\n' "$patch_file"
    printf 'Decompiled Java diff is mostly stylistic noise; focus on NEW symbols and the strings diff.\n'
    printf 'Hooks in %s require manual reconciliation (MG block ~line 238).\n' "$SHARED_CONFIG"
} > "$summary_file"

cat "$summary_file" >&2
printf '%s\n' "$patch_file"
