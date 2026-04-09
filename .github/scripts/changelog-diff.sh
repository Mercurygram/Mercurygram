#!/bin/bash
# changelog-diff.sh — extract structured diff between two tags for changelog generation.
#
# Usage:
#   changelog-diff.sh <previous-tag> <current-tag>   # compare two releases
#   changelog-diff.sh <current-tag>                  # first release (all as ADDED)
#
# Output is structured text consumed by the changelog LLM prompt.

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

# MG-owned source paths (contains exclusively MG/TF code)
MG_PATHS=(
    "TMessagesProj/src/main/java/it/belloworld/mercurygram/"
    "TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/"
)
SHARED_CONFIG="TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java"

mg_subjects() {
    local ref="$1"
    git log --format='%s' "$ref" | grep -E '^\[(MG|TF)\]' | sort
}

upstream_version() {
    local ref="$1"
    git show "${ref}:gradle.properties" 2>/dev/null | grep '^APP_VERSION_NAME=' | cut -d= -f2
}

mg_version() {
    local ref="$1"
    git show "${ref}:gradle.properties" 2>/dev/null | grep '^MG_VERSION_NAME=' | cut -d= -f2
}

CURR_UPSTREAM=$(upstream_version "$CURR_TAG")
CURR_MG=$(mg_version "$CURR_TAG")

echo "RELEASE: ${CURR_MG:-$CURR_TAG}"

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

    echo "=== CURRENT FEATURES ==="
    mg_subjects "$CURR_TAG" || true
    echo ""

    echo "=== DIFF STAT ==="
    git diff --stat "$PREV_TAG" "$CURR_TAG" || true
    echo ""

    echo "=== MG CODE DIFF ==="
    git diff "$PREV_TAG" "$CURR_TAG" -- "${MG_PATHS[@]}" || true
    echo ""

    echo "=== CONFIG DIFF ==="
    git diff "$PREV_TAG" "$CURR_TAG" -- "$SHARED_CONFIG" || true
    echo ""

    echo "=== VERSION DIFF ==="
    git diff "$PREV_TAG" "$CURR_TAG" -- gradle.properties || true
else
    echo "UPSTREAM_CURRENT: $CURR_UPSTREAM"
    echo ""

    echo "=== CURRENT FEATURES ==="
    mg_subjects "$CURR_TAG" || true
    echo ""

    echo "=== VERSION DIFF ==="
    git show "${CURR_TAG}:gradle.properties" 2>/dev/null || true
fi
