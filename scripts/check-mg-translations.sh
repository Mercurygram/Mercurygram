#!/usr/bin/env bash
# List Mercurygram-only string keys (Mercurygram*, mg_*) that are missing
# from each shipped locale relative to the base values/strings.xml.
# Exit 0 always — informational only.

set -eu

base=TMessagesProj/src/main/res/values/strings.xml
if [ ! -f "$base" ]; then
    echo "error: $base not found (run from repo root)" >&2
    exit 2
fi

keys=$(grep -oE 'name="(Mercurygram|mg_)[^"]*"' "$base" | sort -u)
total=$(printf '%s\n' "$keys" | grep -c . || true)
missing_total=0

printf 'MG keys in base: %d\n' "$total"

for d in TMessagesProj/src/main/res/values-*/strings.xml; do
    case "$d" in
        *values-night*|*values-v[0-9]*|*values-sw*) continue ;;
    esac
    locale=$(basename "$(dirname "$d")")
    locale=${locale#values-}
    have=$(grep -oE 'name="(Mercurygram|mg_)[^"]*"' "$d" 2>/dev/null | sort -u || true)
    missing=$(comm -23 <(printf '%s\n' "$keys") <(printf '%s\n' "$have"))
    if [ -n "$missing" ]; then
        n=$(printf '%s\n' "$missing" | grep -c . || true)
        missing_total=$((missing_total + n))
        printf '\n## %s (%d missing)\n%s\n' "$locale" "$n" "$missing"
    fi
done

printf '\n---\nTotal missing across locales: %d\n' "$missing_total"
