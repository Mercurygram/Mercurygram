#!/bin/bash
# changelog-ai.sh — produce a user-facing changelog for a tag/ref range.
#
# Usage:
#   changelog-ai.sh <prev-ref> <curr-ref> [<out-path>]
#   changelog-ai.sh ''         <curr-ref> [<out-path>]   # first release
#
# Wraps changelog-diff.sh + OpenRouter
# (https://openrouter.ai/api/v1/chat/completions). The model is read from
# $OPENROUTER_MODEL (default `openrouter/free`, OpenRouter's free
# auto-router). If the configured model fails for any reason (retired
# model, upstream rate limit, provider outage), the request is retried once
# against `openrouter/free` so a stale or busy pin doesn't poison a release.
# Auth via $OPENROUTER_API_KEY; when unset the AI call is skipped entirely
# (one less guaranteed 401) and the deterministic fallback runs immediately.
# The model returns structured JSON; this script validates policy and renders
# the tiny HTML subset used by GitHub releases and the in-app update dialog.
# Output goes to <out-path> if given, else stdout. Diagnostics go to stderr
# so callers can capture only the body.

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
FACTS_FILE="$(mktemp)"
SOURCE_IDS_FILE="$(mktemp)"
GATED_IDS_FILE="$(mktemp)"
REQ_FILE="$(mktemp)"
trap 'rm -f "$DIFF_FILE" "$FACTS_FILE" "$SOURCE_IDS_FILE" "$GATED_IDS_FILE" "$REQ_FILE"' EXIT

if [[ -n "$PREV_REF" ]]; then
    bash "$SCRIPT_DIR/changelog-diff.sh" "$PREV_REF" "$CURR_REF" > "$DIFF_FILE"
else
    bash "$SCRIPT_DIR/changelog-diff.sh" "$CURR_REF" > "$DIFF_FILE"
fi
echo "Diff size: $(wc -c < "$DIFF_FILE") bytes" >&2

UPSTREAM_CURRENT=$(awk -F': ' '/^UPSTREAM_CURRENT:/ { print $2; exit }' "$DIFF_FILE")
UPSTREAM_CHANGED=$(awk -F': ' '/^UPSTREAM_CHANGED:/ { print $2; exit }' "$DIFF_FILE")
UPSTREAM_CHANGED="${UPSTREAM_CHANGED:-false}"

DEFAULT_MODEL="openrouter/free"
PRIMARY_MODEL="${OPENROUTER_MODEL:-$DEFAULT_MODEL}"
OPENROUTER_URL="https://openrouter.ai/api/v1/chat/completions"

# Retry on transient errors: HTTP 429 (rate-limited — observed on free tiers
# when the upstream provider hits its concurrency cap), HTTP 502/503/504
# (gateway hiccups), and curl-side network errors (DNS, TLS, timeout).
# 401/403 (bad/missing key) and other 4xx bail immediately — won't recover
# on retry. Every failure of the pinned model reaches the outer
# model-fallback loop (try $OPENROUTER_MODEL first, then $DEFAULT_MODEL).
# Backoff is short on purpose — release pipeline is on the critical
# path, transient blips usually clear inside 30-60s. On every
# non-recoverable empty response we log HTTP status + truncated body so
# post-mortems don't require local reproduction.
AI_MAX_ATTEMPTS="${AI_MAX_ATTEMPTS:-3}"
AI_BACKOFFS=(0 15 45)  # cumulative wait BEFORE attempt N (0 = no wait first try)

RESP=''
HTTP=''
BODY=''
BODY_JSON=''

build_source_facts() {
    local raw_file="${FACTS_FILE}.raw"
    awk '
        function flush_patch() {
            if (patch_id != "" && patch_text != "") {
                printf "%s [patch gated=%s themes=%s] %s: %s\n", \
                    patch_id, patch_gated ? "true" : "false", patch_themes, patch_file, patch_text
            }
            patch_id = ""; patch_file = ""; patch_text = ""
            patch_gated = 0; patch_lines = 0; patch_themes = ""
            ctx_comment = ""
        }
        function append(line) {
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", line)
            if (line == "") return
            if (length(line) > 200) line = substr(line, 1, 197) "..."
            if (patch_text != "") patch_text = patch_text " / "
            patch_text = patch_text line
            patch_lines++
        }
        function theme_of(s,    t) {
            t = ""
            if (s ~ /([Tt]or|MgTor|mg_useTor|mg_torIdle|MercurygramTor|mercurygram\/tor\/)/) t = t ",tor"
            if (s ~ /(ReduceTracking|reduceTracking|mgReducedTracking|mg_reduceTracking|mg_lastExhaustedClearedBuild|MercurygramReduceTracking|temp[_-]?key|tempKey|auth_key_id|rotateTempAuthKeys)/) t = t ",reduce-tracking"
            if (s ~ /(MgNetworkChangeWatcher|NetworkChange|rotateAllAccounts)/) t = t ",network-change"
            if (s ~ /([Pp]re[Rr]elease|isPreRelease)/) t = t ",pre-release"
            if (s ~ /(Orbot|MgOrbotHelper|legacyOrbot|TorActiveProxyLocked|TorProxyEntry)/) t = t ",proxy"
            if (s ~ /([Cc][Dd][Nn]|mgRefusedCdnRedirect|FileLoadOperation)/) t = t ",cdn"
            if (s ~ /(UnifiedPush|requestStartForPushFallback|pushFallback)/) t = t ",push"
            if (s ~ /(tw\/nekomimi\/nekogram\/helpers)/) t = t ",infrastructure"
            sub(/^,/, "", t)
            if (t == "") t = "misc"
            return t
        }
        function merge_theme(extra,    n, ts, i) {
            if (extra == "" || extra == "misc") return
            n = split(extra, ts, ",")
            for (i = 1; i <= n; i++) {
                if (ts[i] == "" || ts[i] == "misc") continue
                if (index("," patch_themes ",", "," ts[i] ",") == 0) {
                    if (patch_themes == "" || patch_themes == "misc") patch_themes = ts[i]
                    else patch_themes = patch_themes "," ts[i]
                }
            }
        }
        BEGIN { section = ""; cf = 0; patch = 0; patch_themes = "" }
        /^UPSTREAM_CURRENT:/ { print "META001 [metadata] " $0; next }
        /^UPSTREAM_CHANGED:/ { print "META002 [metadata] " $0; next }
        /^=== CODE FEATURES ===$/ { flush_patch(); section = "code"; next }
        /^=== MG CODE DIFF ===$/ { flush_patch(); section = "patch"; next }
        /^=== MG UPSTREAM PATCHES ===$/ { flush_patch(); section = "patch"; next }
        /^=== / { flush_patch(); section = ""; next }
        section == "code" && NF && $0 != "(none)" {
            cf++
            printf "CF%03d [feature gated=false themes=%s] %s\n", cf, theme_of($0), $0
            next
        }
        section == "patch" && /^diff --git / {
            flush_patch()
            patch++
            patch_id = sprintf("PATCH%03d", patch)
            patch_file = $4; sub(/^b\//, "", patch_file)
            patch_themes = theme_of(patch_file)
            next
        }
        section == "patch" && patch_id != "" && /^[[:space:]]+\/\// {
            ctx_comment = $0
            next
        }
        section == "patch" && patch_id != "" && /^\+[^+]/ {
            line = substr($0, 2)
            if (line ~ /SharedConfig\.mg_[A-Za-z]/) patch_gated = 1
            merge_theme(theme_of(line))
            if (patch_lines >= 60) { ctx_comment = ""; next }
            if (line ~ /(MG:|Mercurygram|SharedConfig\.mg_|mg_|Tor|tor|CDN|cdn|UnifiedPush|proxy|Proxy|auth_key|network|Network|rotate|Rotate|[Pp]re-?release|session key|temporary key|temp[_-]?key|wake|idle|crash|respawn|redirect|fallback|migrate|Orbot|bootstrap|socks|cookie|exhaust)/) {
                if (ctx_comment != "") { append(ctx_comment); ctx_comment = "" }
                append(line)
            } else {
                ctx_comment = ""
            }
            next
        }
        section == "patch" && patch_id != "" { ctx_comment = "" }
        END { flush_patch() }
    ' "$DIFF_FILE" > "$raw_file"

    {
        echo "=== SOURCE FACTS ==="
        awk '/^META[0-9]+ / { print }' "$raw_file"
        echo ""
        echo "THEMES:"
        awk '
            {
                if (match($0, /themes=[^]]+/) == 0) next
                themes_field = substr($0, RSTART + 7, RLENGTH - 7)
                id = $1
                n = split(themes_field, ts, ",")
                for (i = 1; i <= n; i++) {
                    key = ts[i]
                    if (key == "") continue
                    if (!(key in member)) { keys[++nk] = key; member[key] = id }
                    else member[key] = member[key] " " id
                }
            }
            END {
                for (i = 1; i <= nk; i++)
                    for (j = i + 1; j <= nk; j++)
                        if (keys[i] > keys[j]) { x = keys[i]; keys[i] = keys[j]; keys[j] = x }
                for (i = 1; i <= nk; i++) printf "- %s: %s\n", keys[i], member[keys[i]]
            }
        ' "$raw_file"
        echo ""
        echo "ITEMS:"
        awk '/^(CF|PATCH)[0-9]+ / { print }' "$raw_file"
    } > "$FACTS_FILE"
    rm -f "$raw_file"

    awk '/^(CF|PATCH|META)[0-9]+ / { print $1 }' "$FACTS_FILE" > "$SOURCE_IDS_FILE"
    awk '/^(CF|PATCH)[0-9]+ .*gated=true/ { print $1 }' "$FACTS_FILE" > "$GATED_IDS_FILE"
}

build_source_facts

render_body_json() {
    local json="$1"
    printf '%s' "$json" | jq -r \
        --arg upstream "$UPSTREAM_CURRENT" \
        --arg upstream_changed "$UPSTREAM_CHANGED" '
        # Merge sections that share a title (model occasionally emits two
        # "Improved" sections instead of one with multiple bullets).
        def merge_sections:
          reduce .[] as $s ({order: [], by_title: {}};
            if .by_title | has($s.title) then
              .by_title[$s.title].bullets += $s.bullets
            else
              .order += [$s.title]
              | .by_title[$s.title] = {title: $s.title, bullets: $s.bullets}
            end)
          | [.order[] as $t | .by_title[$t]];

        def section:
          "<b>\(.title)</b><br>\n" +
          ([.bullets[].text | "• \(.)<br>"] | join("\n"));

        (if $upstream_changed == "true" then
          "Based on Telegram \($upstream).<br><br>\n"
        else
          ""
        end) +
        ([.sections | merge_sections | .[] | select((.bullets | length) > 0) | section] | join("<br>\n"))
    '
}

source_text_for_ids() {
    local ids="$1"
    printf '%s\n' "$ids" | grep -vxF '' > "$REQ_FILE.sources" || true
    grep -F -f "$REQ_FILE.sources" "$FACTS_FILE" 2>/dev/null || true
    rm -f "$REQ_FILE.sources"
}

sources_include_gated_id() {
    local ids="$1"
    [[ -s "$GATED_IDS_FILE" ]] || return 1
    printf '%s\n' "$ids" | grep -Fx -f "$GATED_IDS_FILE" >/dev/null 2>&1
}

validate_body_json() {
    local json="$1"
    local rendered unknown internal

    if ! printf '%s' "$json" | jq -e '
        (.based_on_telegram | type == "boolean") and
        (.sections | type == "array") and
        (.sections | length > 0) and
        ([.sections[] | select((.bullets | type == "array") and (.bullets | length > 0))] | length > 0) and
        all(.sections[];
          (.title as $title | ["What'\''s New", "Improved", "Fixed", "Infrastructure"] | index($title) != null) and
          (.bullets | type == "array") and
          all(.bullets[];
            (.text | type == "string") and
            (.text | length > 0) and
            (.sources | type == "array") and
            (.sources | length > 0) and
            all(.sources[]; type == "string")
          )
        )
    ' >/dev/null 2>&1; then
        REJECTION_REASON="response is not valid changelog JSON"
        echo "AI changelog rejected: $REJECTION_REASON" >&2
        return 1
    fi

    unknown=$(printf '%s' "$json" | jq -r '.sections[].bullets[].sources[]' | grep -vxF -f "$SOURCE_IDS_FILE" || true)
    if [[ -n "$unknown" ]]; then
        REJECTION_REASON="unknown source ids: $(printf '%s' "$unknown" | tr '\n' ' ')"
        echo "AI changelog rejected: $REJECTION_REASON" >&2
        return 1
    fi

    internal=$(printf '%s' "$json" | jq -r '.sections[].bullets[].text' \
        | grep -nE '(<[^> ]+>|mg_|SharedConfig|SharedPreferences|[A-Za-z0-9_/-]+\.(java|kt)\b|\bMg[A-Z][A-Za-z0-9]+\b|auth_key_id|MT[ -]?Proto|\bPFS\b|\b(CF|PATCH|META)[0-9]{3}\b)' || true)
    if [[ -n "$internal" ]]; then
        REJECTION_REASON="internal identifier leaked: $(printf '%s' "$internal" | head -n1)"
        echo "AI changelog rejected: $REJECTION_REASON" >&2
        return 1
    fi

    while IFS= read -r row; do
        local text ids cited
        text=$(printf '%s' "$row" | jq -r '.text')
        ids=$(printf '%s' "$row" | jq -r '.sources[]')
        cited=$(source_text_for_ids "$ids")
        if printf '%s\n' "$text" | grep -qiE '(^|[[:space:]])(when|if|while|under)[^.!?]*(enabled|active| on |mode)'; then
            if ! sources_include_gated_id "$ids"; then
                REJECTION_REASON="conditional claim is not backed by a gated source: $text"
                echo "AI changelog rejected: $REJECTION_REASON" >&2
                return 1
            fi
        fi
        if printf '%s\n' "$text" | grep -qiE '\bcrash(ed|es|ing)?\b'; then
            if ! printf '%s\n' "$cited" | grep -qiE '\bcrash(ed|es|ing)?\b|exception|fatal|sigsegv'; then
                REJECTION_REASON="crash claim is not backed by crash evidence: $text"
                echo "AI changelog rejected: $REJECTION_REASON" >&2
                return 1
            fi
        fi
    done < <(printf '%s' "$json" | jq -c '.sections[].bullets[]')

    rendered=$(render_body_json "$json") || return 1
    if (( $(printf '%s\n' "$rendered" | wc -l) > 25 )); then
        REJECTION_REASON="rendered output exceeds 25 lines"
        echo "AI changelog rejected: $REJECTION_REASON" >&2
        return 1
    fi
    REJECTION_REASON=""
}

fallback_body() {
    # Generic fallback used whenever the AI path is unavailable or
    # rejected. Bullets the canonical CODE FEATURES block verbatim under
    # a single What's New header — internal identifier leakage is the
    # explicit trade-off for never going stale across releases. The AI
    # path is the only place we phrase user-facing prose.
    # "New method:" lines are raw Java signatures: noise to a reader, and the
    # bulk of the block. Classes, mg_ flags and new strings at least name the
    # feature, so the fallback keeps those and drops the signatures.
    local features
    features=$(awk '
        /^=== CODE FEATURES ===$/ { capture=1; next }
        /^=== / { capture=0 }
        capture && NF && $0 !~ /^New method: / { print }
    ' "$DIFF_FILE")

    if [[ -z "$features" || "$features" == "(none)" ]]; then
        if [[ "$UPSTREAM_CHANGED" == "true" ]]; then
            BODY="Based on Telegram $UPSTREAM_CURRENT.<br><br>"$'\n'
            BODY+="<b>Improved</b><br>"$'\n'
            BODY+="• Updated to the latest Telegram base.<br>"$'\n'
        else
            BODY="Maintenance release: rebased on latest upstream, no user-facing changes."
        fi
        return
    fi

    BODY=''
    if [[ "$UPSTREAM_CHANGED" == "true" ]]; then
        BODY="Based on Telegram $UPSTREAM_CURRENT.<br><br>"$'\n'
    fi
    BODY+="<b>What's New</b><br>"$'\n'
    while IFS= read -r feature; do
        BODY+="• ${feature}<br>"$'\n'
    done <<< "$features"
}

call_model() {
    # $1 = model id. Sets RESP, HTTP. Returns 0 on HTTP 200, 1 otherwise
    # (caller distinguishes model-not-found vs hard fail from HTTP code).
    # If REJECTION_FEEDBACK is non-empty, appends a feedback user message
    # so the model self-corrects on the resample.
    local model="$1"
    jq -n \
        --rawfile sys "$PROMPT_FILE" \
        --rawfile facts "$FACTS_FILE" \
        --arg model "$model" \
        --arg feedback "${REJECTION_FEEDBACK:-}" \
        '{
          model: $model,
          temperature: 0.2,
          response_format: {
            type: "json_schema",
            json_schema: {
              name: "mercurygram_changelog",
              strict: true,
              schema: {
                type: "object",
                additionalProperties: false,
                required: ["based_on_telegram", "sections"],
                properties: {
                  based_on_telegram: { type: "boolean" },
                  sections: {
                    type: "array",
                    items: {
                      type: "object",
                      additionalProperties: false,
                      required: ["title", "bullets"],
                      properties: {
                        title: {
                          type: "string",
                          enum: ["What'\''s New", "Improved", "Fixed", "Infrastructure"]
                        },
                        bullets: {
                          type: "array",
                          items: {
                            type: "object",
                            additionalProperties: false,
                            required: ["text", "sources"],
                            properties: {
                              text: { type: "string", minLength: 1 },
                              sources: {
                                type: "array",
                                minItems: 1,
                                items: { type: "string", pattern: "^[A-Z]+[0-9]{3}$" }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          },
          messages: (
            [
              {role: "system", content: $sys},
              {role: "user", content: $facts}
            ] + (if $feedback == "" then [] else [{role: "user", content: $feedback}] end)
          )
        }' > "$REQ_FILE"

    local attempt RAW
    for attempt in $(seq 1 "$AI_MAX_ATTEMPTS"); do
        sleep "${AI_BACKOFFS[$((attempt - 1))]:-60}"
        # 300s, not 120s: the free auto-router serves a ~100 KB request in
        # 50-190s depending on which provider it lands on, so a 2-minute cap
        # turned normal-but-slow answers into curl errors (HTTP 000) and
        # dropped the release to the deterministic fallback.
        RAW=$(curl -sL --max-time 300 -X POST \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer $OPENROUTER_API_KEY" \
            --data-binary "@$REQ_FILE" \
            -w '\n%{http_code}' \
            "$OPENROUTER_URL") || RAW=''
        if [[ -z "$RAW" ]]; then
            HTTP='000'
            RESP=''
        else
            HTTP=$(printf '%s' "$RAW" | tail -n1)
            RESP=$(printf '%s' "$RAW" | sed '$d')
        fi
        case "$HTTP" in
            200)
                return 0
                ;;
            429|502|503|504|000)
                if [[ "$attempt" -lt "$AI_MAX_ATTEMPTS" ]]; then
                    echo "OpenRouter [$model] attempt $attempt/$AI_MAX_ATTEMPTS: HTTP $HTTP — retrying" >&2
                    continue
                fi
                echo "OpenRouter [$model] attempt $attempt/$AI_MAX_ATTEMPTS: HTTP $HTTP (exhausted)" >&2
                return 1
                ;;
            *)
                # 400/401/403/404/etc — won't get better on retry. Surface
                # the status; caller decides whether to swap models.
                echo "OpenRouter [$model] attempt $attempt: HTTP $HTTP (non-retryable)" >&2
                return 1
                ;;
        esac
    done
    return 1
}

normalize_body_json() {
    local content="$1"
    if printf '%s' "$content" | jq -e . >/dev/null 2>&1; then
        printf '%s' "$content"
        return
    fi
    # Some providers ignore response_format partially and wrap JSON in prose
    # or fences. Keep only the outer JSON object; validation still decides
    # whether the result is acceptable.
    printf '%s' "$content" | perl -0pe 's/\A.*?(\{)/$1/s; s/(\})[^\}]*\z/$1/s'
}

# Validation-aware resample loop. The OSS free models occasionally leak
# forbidden vocab or drop a section even with strict json_schema. On
# rejection we feed the previous draft + reason back as a follow-up user
# turn so the model self-corrects without re-reading the whole diff.
MAX_VALIDATE_ATTEMPTS="${CHANGELOG_VALIDATE_ATTEMPTS:-3}"
ACCEPTED=0
REJECTION_FEEDBACK=""

if [[ -z "${OPENROUTER_API_KEY:-}" ]]; then
    echo "OPENROUTER_API_KEY not set — skipping AI call, using deterministic fallback" >&2
else
    for vatt in $(seq 1 "$MAX_VALIDATE_ATTEMPTS"); do
        if call_model "$PRIMARY_MODEL"; then
            :
        elif [[ "$PRIMARY_MODEL" != "$DEFAULT_MODEL" ]]; then
            # Any failure of the pinned model, not just "model not found".
            # A free-tier pin is rate-limited upstream far more often than it
            # is retired, and a 429-exhausted primary used to drop straight to
            # the identifier-dump fallback without the auto-router ever being
            # tried.
            echo "model '$PRIMARY_MODEL' failed (HTTP $HTTP), retrying with $DEFAULT_MODEL" >&2
            call_model "$DEFAULT_MODEL" || true
        fi
        BODY_JSON=$(printf '%s' "$RESP" | jq -r '.choices[0].message.content // empty' 2>/dev/null) || BODY_JSON=''
        BODY_JSON=$(normalize_body_json "$BODY_JSON")
        REJECTION_REASON=""
        if [[ -n "$BODY_JSON" ]] && validate_body_json "$BODY_JSON"; then
            ACCEPTED=1
            break
        fi
        if (( vatt < MAX_VALIDATE_ATTEMPTS )) && [[ -n "$BODY_JSON" && -n "$REJECTION_REASON" ]]; then
            echo "AI changelog: resampling (attempt $((vatt + 1))/$MAX_VALIDATE_ATTEMPTS)" >&2
            REJECTION_FEEDBACK="Your previous JSON response was rejected. Reason: ${REJECTION_REASON}. Regenerate the entire JSON body fixing this issue. Keep the same schema. Do not include the forbidden tokens listed in the system prompt."
        else
            break
        fi
    done
fi

if (( ACCEPTED )); then
    BODY=$(render_body_json "$BODY_JSON")
    echo "AI changelog: $(printf '%s' "$BODY" | wc -c) bytes" >&2
    if [[ -n "${CHANGELOG_DEBUG_JSON:-}" ]]; then
        printf 'DEBUG_JSON: %s\n' "$BODY_JSON" >&2
    fi
else
    echo "AI changelog empty or rejected — using deterministic fallback" >&2
    if [[ -n "${OPENROUTER_API_KEY:-}" ]]; then
        echo "  last HTTP=$HTTP, response excerpt:" >&2
        { printf '%s' "${BODY_JSON:-$RESP}" | tr '\n' ' ' | sed 's/[[:space:]][[:space:]]*/ /g' | head -c 1500 || true; } | sed 's/^/    /' >&2
        echo >&2
    fi
    fallback_body
fi

if [[ -n "$OUT_PATH" ]]; then
    printf '%s\n' "$BODY" > "$OUT_PATH"
    echo "Final changelog: $(wc -c < "$OUT_PATH") bytes, $(wc -l < "$OUT_PATH") lines" >&2
else
    printf '%s\n' "$BODY"
fi
