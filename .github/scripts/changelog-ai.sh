#!/bin/bash
# changelog-ai.sh — produce a user-facing changelog for a tag/ref range.
#
# Usage:
#   changelog-ai.sh <prev-ref> <curr-ref> [<out-path>]
#   changelog-ai.sh ''         <curr-ref> [<out-path>]   # first release
#
# Wraps changelog-diff.sh + Kilo (kilo-auto/free). Falls back to the
# === CODE FEATURES === block bulleted, then to a maintenance-release line
# when even that is empty. Output goes to <out-path> if given, else stdout.
# Diagnostics go to stderr so callers can capture only the body.

set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
    echo "Usage: $0 <prev-ref> <curr-ref> [<out-path>]" >&2
    exit 1
fi

PREV_REF="$1"
CURR_REF="$2"
OUT_PATH="${3:-}"

REPO_ROOT="$(git rev-parse --show-toplevel)"
SCRIPT_DIR="$REPO_ROOT/.github/scripts"
PROMPT_FILE="$SCRIPT_DIR/changelog-prompt.md"
DIFF_FILE="$(mktemp)"
REQ_FILE="$(mktemp)"
trap 'rm -f "$DIFF_FILE" "$REQ_FILE"' EXIT

if [[ -n "$PREV_REF" ]]; then
    bash "$SCRIPT_DIR/changelog-diff.sh" "$PREV_REF" "$CURR_REF" > "$DIFF_FILE"
else
    bash "$SCRIPT_DIR/changelog-diff.sh" "$CURR_REF" > "$DIFF_FILE"
fi
echo "Diff size: $(wc -c < "$DIFF_FILE") bytes" >&2

jq -n \
    --rawfile sys "$PROMPT_FILE" \
    --rawfile diff "$DIFF_FILE" \
    '{
      model: "kilo-auto/free",
      messages: [
        {role: "system", content: $sys},
        {role: "user", content: $diff}
      ]
    }' > "$REQ_FILE"

# Decouple curl and jq so any transient failure (DNS, TLS, HTTP 5xx with
# non-JSON body, malformed completion) degrades to BODY='' and the grep
# fallback below — instead of aborting the script under pipefail and
# taking the release pipeline down with it.
RESP=$(curl -sL --max-time 120 -X POST \
    -H "Content-Type: application/json" \
    --data-binary "@$REQ_FILE" \
    https://api.kilo.ai/api/gateway/v1/chat/completions) || RESP=''
BODY=$(printf '%s' "$RESP" | jq -r '.choices[0].message.content // empty' 2>/dev/null) || BODY=''

if [[ -z "$BODY" ]]; then
    echo "AI changelog empty — using grep-based fallback" >&2
    FEATURES=$(awk '
        /^=== CODE FEATURES ===$/ { capture=1; next }
        /^=== / { capture=0 }
        capture && NF { print }
    ' "$DIFF_FILE")
    if [[ -z "$FEATURES" || "$FEATURES" == "(none)" ]]; then
        BODY="- Maintenance release: no user-facing changes."
    else
        BODY=$(printf '%s\n' "$FEATURES" | sed 's/^/- /')
    fi
else
    echo "AI changelog: $(printf '%s' "$BODY" | wc -c) bytes" >&2
fi

if [[ -n "$OUT_PATH" ]]; then
    printf '%s\n' "$BODY" > "$OUT_PATH"
    echo "Final changelog: $(wc -c < "$OUT_PATH") bytes, $(wc -l < "$OUT_PATH") lines" >&2
else
    printf '%s\n' "$BODY"
fi
