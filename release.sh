#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
[[ -f "$ENV_FILE" ]] || { echo "Missing $ENV_FILE — copy .env.example and fill in your values"; exit 1; }
source "$ENV_FILE"

TAG="${1:-}"
[[ -n "$TAG" ]] || { echo "Usage: $0 <tag>  (e.g. $0 12.7.3.5)"; exit 1; }
# release.sh ships only 4-dotted stable tags; 5-dotted snapshots are
# beta.yml's territory. Reject early so PREV_TAG enumeration (4-dotted
# only) can't silently fall back to first-release mode.
[[ "$TAG" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] \
    || { echo "Tag '$TAG' is not 4-dotted (X.Y.Z.M). Snapshots are published by beta.yml."; exit 1; }

# Sign via Gradle directly (not apksigner) to preserve zero-padding in ZIP
# extra fields, which is required for F-Droid reproducible builds.

# HACK: cache built native artifacts to avoid full NDK rebuild on every run
if [ -d TMessagesProj/jni.bak ]; then
    rm -rf TMessagesProj/jni
    cp -a --reflink=auto TMessagesProj/jni.bak TMessagesProj/jni
fi
./gradlew assembleAfatRelease assembleAfatDebug \
    assembleAfatFdArm32Release assembleAfatFdArm64Release assembleAfatFdX86Release assembleAfatFdX86_64Release \
    -PMG_BUILD_TAG="$TAG" \
    -PRELEASE_STORE_FILE="$KS" \
    -PRELEASE_STORE_PASSWORD="$KS_PASS" \
    -PRELEASE_KEY_PASSWORD="$KS_KEY_PASS" \
    -PRELEASE_KEY_ALIAS="$KS_KEY_ALIAS"
# HACK: update the native artifact cache
rm -rf TMessagesProj/jni.bak
cp -a --reflink=auto TMessagesProj/jni TMessagesProj/jni.bak

APK_DIR=./TMessagesProj_App/build/outputs/apk

cp "$APK_DIR/afat/release/app.apk"                  "$APK_DIR/Mercurygram-${TAG}-release.apk"
cp "$APK_DIR/afat/debug/app.apk"                    "$APK_DIR/Mercurygram-${TAG}-debug.apk"
cp "$APK_DIR/afatFdArm32/release/afatFdArm32.apk"   "$APK_DIR/Mercurygram-${TAG}-armeabi-v7a.apk"
cp "$APK_DIR/afatFdArm64/release/afatFdArm64.apk"   "$APK_DIR/Mercurygram-${TAG}-arm64-v8a.apk"
cp "$APK_DIR/afatFdX86/release/afatFdX86.apk"       "$APK_DIR/Mercurygram-${TAG}-x86.apk"
cp "$APK_DIR/afatFdX86_64/release/afatFdX86_64.apk" "$APK_DIR/Mercurygram-${TAG}-x86_64.apk"

# Find previous Mercurygram release tag (4-part version format).
# `|| true` — both greps return 1 on no-match (e.g. first stable ever, or
# `$TAG` doesn't fit the 4-part regex); pipefail would otherwise abort.
PREV_TAG=$(git tag -l '[0-9]*' --sort=-version:refname \
    | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' \
    | grep -A1 "^${TAG}$" | tail -1) || true
if [ "$PREV_TAG" = "$TAG" ] || [ -z "$PREV_TAG" ]; then
    PREV_TAG=""
fi

# Generate changelog via Kilo (same script + endpoint as release.yml).
# Script handles AI call, grep fallback, and maintenance-release stub.
BODY=$(bash "$SCRIPT_DIR/.github/scripts/changelog-ai.sh" "$PREV_TAG" "$TAG")

# Guard re-runs: confirm before destroying an existing published release.
# RELEASE_REPLACE=1 skips the prompt for non-interactive runs.
# Plain `gh release delete` (no --cleanup-tag): the tag is already correct
# (release.sh wouldn't be running for a wrong commit) and dropping
# --cleanup-tag closes the orphan-release window — if the subsequent
# `gh release create` is interrupted, the tag survives and the next run
# can recover. --cleanup-tag's prior failure mode is what produced the
# tag-less releases that beta.yml's version computation tripped on.
if gh release view "$TAG" >/dev/null 2>&1; then
    if [[ -z "${RELEASE_REPLACE:-}" ]]; then
        read -rp "Release $TAG exists. Delete and replace? [y/N] " ans </dev/tty
        [[ "$ans" =~ ^[Yy]$ ]] || { echo "aborted"; exit 1; }
    fi
    gh release delete "$TAG" --yes
fi
gh release create "$TAG" \
    "$APK_DIR/Mercurygram-${TAG}-release.apk" \
    "$APK_DIR/Mercurygram-${TAG}-debug.apk" \
    "$APK_DIR/Mercurygram-${TAG}-armeabi-v7a.apk" \
    "$APK_DIR/Mercurygram-${TAG}-arm64-v8a.apk" \
    "$APK_DIR/Mercurygram-${TAG}-x86.apk" \
    "$APK_DIR/Mercurygram-${TAG}-x86_64.apk" \
    --notes "$BODY"
