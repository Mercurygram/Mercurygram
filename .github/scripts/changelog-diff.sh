#!/bin/bash
# changelog-diff.sh — extract structured diff between two tags for changelog generation.
#
# Usage:
#   changelog-diff.sh <previous-tag> <current-tag>   # compare two releases
#   changelog-diff.sh <current-tag>                  # first release (all as ADDED)
#
# Output is structured text consumed by the changelog LLM prompt. The
# `=== CODE FEATURES ===` block is the canonical enumeration source; raw
# diff blocks are kept as context the LLM uses to phrase each item.
#
# Tree diff (not commit walk): `git diff PREV CURR` compares the two trees
# directly, so MG features present in both tags don't show up, regardless of
# the rebase-induced SHA churn in Mercurygram's history. Reachability-based
# tools (`git log PREV..CURR`, `git rev-list`) are unsafe here and avoided.

set -euo pipefail

if [[ $# -eq 2 ]]; then
    PREV_TAG="$1"
    CURR_TAG="$2"
elif [[ $# -eq 1 ]]; then
    PREV_TAG=""
    CURR_TAG="$1"
else
    echo "Usage: $0 [previous-tag] <current-tag>" >&2
    exit 1
fi

# shellcheck source=../../scripts/lib/mg-paths.sh
. "$(git rev-parse --show-toplevel)/scripts/lib/mg-paths.sh"
MG_PATHS=("${MG_OWNED_PATHS[@]}")
SHARED_CONFIG="${MG_HOOK_FILES[0]}"
STRINGS_XML='TMessagesProj/src/main/res/values/strings.xml'
MG_STRING_PREFIXES='HiddenAccounts|WebPush|Mercurygram|UpdateCheck|MessageDetails'

upstream_version() {
    local ref="$1"
    git show "${ref}:gradle.properties" 2>/dev/null | grep '^APP_VERSION_NAME=' | cut -d= -f2
}

new_classes() {
    local prev="$1" curr="$2"
    git diff --diff-filter=A --name-only "$prev" "$curr" -- "${MG_PATHS[@]}" 2>/dev/null \
        | { grep '\.java$' || true; } \
        | while read -r path; do
            local cls short
            cls=$(basename "$path" .java)
            short=${path#TMessagesProj/src/main/java/}
            echo "New class: $cls ($short)"
        done
}

new_config_flags() {
    local prev="$1" curr="$2"
    git diff "$prev" "$curr" -- "$SHARED_CONFIG" 2>/dev/null \
        | { grep -E '^\+[[:space:]]+.*\.put(Boolean|String|Int|Long)\("mg_' || true; } \
        | sed -E 's/.*\.put(Boolean|String|Int|Long)\("(mg_[^"]+)".*/New config flag: \2 (\1)/' \
        | sort -u
}

# Heuristic: added lines starting with public/protected containing a (...)
# signature, excluding class/interface/enum/annotation declarations.
new_methods() {
    local prev="$1" curr="$2"
    git diff "$prev" "$curr" -- "${MG_PATHS[@]}" 2>/dev/null \
        | { grep -E '^\+[[:space:]]+(public|protected)[[:space:]]' || true; } \
        | { grep -vE '(class|interface|enum|@interface)[[:space:]]' || true; } \
        | { grep -E '\([^)]*\)[[:space:]]*(\{|$|throws)' || true; } \
        | sed -E 's/^\+[[:space:]]+//; s/[[:space:]]*\{[[:space:]]*$//' \
        | sed -E 's/^/New method: /' \
        | sort -u
}

new_strings() {
    local prev="$1" curr="$2"
    git diff "$prev" "$curr" -- "$STRINGS_XML" 2>/dev/null \
        | { grep -E "^\+[[:space:]]*<string name=\"(${MG_STRING_PREFIXES})" || true; } \
        | sed -E 's/^\+[[:space:]]*<string name="([^"]+)">([^<]*).*/New string: \1 = "\2"/' \
        | sort -u
}

# Tree diff of upstream Java/Kotlin files that changed between the two tags,
# excluding MG-owned paths, SharedConfig.java, and strings.xml (those have
# their own dedicated blocks). For upstream-version-bump releases this can be
# large because of the rebase against new upstream; for hotfix releases at
# the same upstream version it's typically just the MG-touched lines.
mg_upstream_patches() {
    local prev="$1" curr="$2"
    local files
    files=$(git diff --name-only "$prev" "$curr" 2>/dev/null \
        | { grep -E '^TMessagesProj/.*\.(java|kt)$' || true; } \
        | { grep -vE "^TMessagesProj/src/main/java/(it/belloworld/mercurygram|tw/nekomimi/nekogram/helpers)/" || true; } \
        | { grep -vxF "$SHARED_CONFIG" || true; } \
        | { grep -vxF "$STRINGS_XML" || true; })
    [[ -z "$files" ]] && return
    git diff "$prev" "$curr" -- $files 2>/dev/null || true
}

emit_code_features() {
    local prev="$1" curr="$2"
    local out
    out=$(
        new_classes "$prev" "$curr" || true
        new_config_flags "$prev" "$curr" || true
        new_methods "$prev" "$curr" || true
        new_strings "$prev" "$curr" || true
    )
    if [[ -z "${out// /}" ]]; then
        echo "(none)"
    else
        printf '%s\n' "$out"
    fi
}

emit_code_features_first_release() {
    local curr="$1"
    local out
    out=$(
        git ls-tree -r --name-only "$curr" -- "${MG_PATHS[@]}" 2>/dev/null \
            | { grep '\.java$' || true; } \
            | while read -r path; do
                local cls short
                cls=$(basename "$path" .java)
                short=${path#TMessagesProj/src/main/java/}
                echo "New class: $cls ($short)"
            done
    )
    if [[ -z "${out// /}" ]]; then
        echo "(none)"
    else
        printf '%s\n' "$out"
    fi
}

CURR_UPSTREAM=$(upstream_version "$CURR_TAG")

echo "RELEASE: $CURR_TAG"

if [[ -n "$PREV_TAG" ]]; then
    PREV_UPSTREAM=$(upstream_version "$PREV_TAG")
    echo "PREVIOUS: $PREV_TAG"
    echo "UPSTREAM_CURRENT: $CURR_UPSTREAM"
    echo "UPSTREAM_PREVIOUS: $PREV_UPSTREAM"
    if [[ "$CURR_UPSTREAM" != "$PREV_UPSTREAM" ]]; then
        echo "UPSTREAM_CHANGED: true"
    else
        echo "UPSTREAM_CHANGED: false"
    fi
    echo ""

    echo "=== CODE FEATURES ==="
    emit_code_features "$PREV_TAG" "$CURR_TAG"
    echo ""

    echo "=== DIFF STAT ==="
    git diff --stat "$PREV_TAG" "$CURR_TAG" -- "${MG_PATHS[@]}" "$SHARED_CONFIG" "$STRINGS_XML" gradle.properties || true
    echo ""

    echo "=== MG CODE DIFF ==="
    git diff "$PREV_TAG" "$CURR_TAG" -- "${MG_PATHS[@]}" || true
    echo ""

    echo "=== MG UPSTREAM PATCHES ==="
    mg_upstream_patches "$PREV_TAG" "$CURR_TAG"
    echo ""

    echo "=== CONFIG DIFF ==="
    git diff "$PREV_TAG" "$CURR_TAG" -- "$SHARED_CONFIG" || true
    echo ""

    echo "=== VERSION DIFF ==="
    git diff "$PREV_TAG" "$CURR_TAG" -- gradle.properties || true
else
    echo "UPSTREAM_CURRENT: $CURR_UPSTREAM"
    echo ""

    echo "=== CODE FEATURES ==="
    emit_code_features_first_release "$CURR_TAG"
    echo ""

    echo "=== VERSION DIFF ==="
    git show "${CURR_TAG}:gradle.properties" 2>/dev/null || true
fi
