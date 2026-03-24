#!/bin/bash
# changelog-diff.sh — extract structured commit-subject diff between two tags.
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

    CURR_SUBJECTS=$(mg_subjects "$CURR_TAG")
    PREV_SUBJECTS=$(mg_subjects "$PREV_TAG")

    # comm requires sorted inputs; mg_subjects already sorts.
    ADDED=$(comm -13 <(echo "$PREV_SUBJECTS") <(echo "$CURR_SUBJECTS"))
    REMOVED=$(comm -23 <(echo "$PREV_SUBJECTS") <(echo "$CURR_SUBJECTS"))
    UNCHANGED=$(comm -12 <(echo "$PREV_SUBJECTS") <(echo "$CURR_SUBJECTS"))

    echo "=== ADDED [MG] ==="
    echo "$ADDED" | grep -E '^\[MG\]' || true
    echo ""
    echo "=== REMOVED [MG] ==="
    echo "$REMOVED" | grep -E '^\[MG\]' || true
    echo ""
    echo "=== ADDED [TF] ==="
    echo "$ADDED" | grep -E '^\[TF\]' || true
    echo ""
    echo "=== REMOVED [TF] ==="
    echo "$REMOVED" | grep -E '^\[TF\]' || true
    echo ""
    echo "=== UNCHANGED [MG] ==="
    echo "$UNCHANGED" | grep -E '^\[MG\]' || true
    echo ""
    echo "=== UNCHANGED [TF] ==="
    echo "$UNCHANGED" | grep -E '^\[TF\]' || true
else
    echo "UPSTREAM_CURRENT: $CURR_UPSTREAM"
    echo ""

    ALL=$(mg_subjects "$CURR_TAG")

    echo "=== ADDED [MG] ==="
    echo "$ALL" | grep -E '^\[MG\]' || true
    echo ""
    echo "=== ADDED [TF] ==="
    echo "$ALL" | grep -E '^\[TF\]' || true
fi
