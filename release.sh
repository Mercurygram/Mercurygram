#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
[[ -f "$ENV_FILE" ]] || { echo "Missing $ENV_FILE — copy .env.example and fill in your values"; exit 1; }
source "$ENV_FILE"

MG_VERSION_NAME=$(grep '^MG_VERSION_NAME=' "$SCRIPT_DIR/gradle.properties" | cut -d= -f2)

# Sign via Gradle directly (not apksigner) to preserve zero-padding in ZIP
# extra fields, which is required for F-Droid reproducible builds.

# HACK: cache built native artifacts to avoid full NDK rebuild on every run
if [ -d TMessagesProj/jni.bak ]; then
    rm -rf TMessagesProj/jni
    cp -a --reflink=auto TMessagesProj/jni.bak TMessagesProj/jni
fi
./gradlew assembleAfatRelease assembleAfatDebug \
    assembleAfatFdArm32Release assembleAfatFdArm64Release assembleAfatFdX86Release assembleAfatFdX86_64Release \
    -PRELEASE_STORE_FILE="$KS" \
    -PRELEASE_STORE_PASSWORD="$KS_PASS" \
    -PRELEASE_KEY_PASSWORD="$KS_KEY_PASS" \
    -PRELEASE_KEY_ALIAS="$KS_KEY_ALIAS"
# HACK: update the native artifact cache
rm -rf TMessagesProj/jni.bak
cp -a --reflink=auto TMessagesProj/jni TMessagesProj/jni.bak

APK_DIR=./TMessagesProj_App/build/outputs/apk

cp "$APK_DIR/afat/release/app.apk"                  "$APK_DIR/Mercurygram-${MG_VERSION_NAME}-release.apk"
cp "$APK_DIR/afat/debug/app.apk"                    "$APK_DIR/Mercurygram-${MG_VERSION_NAME}-debug.apk"
cp "$APK_DIR/afatFdArm32/release/afatFdArm32.apk"   "$APK_DIR/Mercurygram-${MG_VERSION_NAME}-armeabi-v7a.apk"
cp "$APK_DIR/afatFdArm64/release/afatFdArm64.apk"   "$APK_DIR/Mercurygram-${MG_VERSION_NAME}-arm64-v8a.apk"
cp "$APK_DIR/afatFdX86/release/afatFdX86.apk"       "$APK_DIR/Mercurygram-${MG_VERSION_NAME}-x86.apk"
cp "$APK_DIR/afatFdX86_64/release/afatFdX86_64.apk" "$APK_DIR/Mercurygram-${MG_VERSION_NAME}-x86_64.apk"

if [[ -n "${1:+x}" ]]; then
    TAG="$1"

    # Find previous Mercurygram release tag (4-part version format)
    PREV_TAG=$(git tag -l '[0-9]*' --sort=-version:refname \
        | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' \
        | grep -A1 "^${TAG}$" | tail -1)

    # Generate commit diff
    if [ "$PREV_TAG" = "$TAG" ] || [ -z "$PREV_TAG" ]; then
        bash "$SCRIPT_DIR/.github/scripts/changelog-diff.sh" "$TAG" > /tmp/mg-diff.txt
    else
        bash "$SCRIPT_DIR/.github/scripts/changelog-diff.sh" "$PREV_TAG" "$TAG" > /tmp/mg-diff.txt
    fi

    # Generate changelog via GitHub Models API (same model as CI)
    GITHUB_TOKEN=$(gh auth token 2>/dev/null || true)
    BODY=""
    if [ -n "$GITHUB_TOKEN" ]; then
        DIFF=$(cat /tmp/mg-diff.txt)
        SYSTEM=$(cat "$SCRIPT_DIR/.github/scripts/changelog-prompt.md")
        BODY=$(curl -sL -X POST \
            -H "Authorization: Bearer $GITHUB_TOKEN" \
            -H "Content-Type: application/json" \
            https://models.github.ai/inference/chat/completions \
            -d "$(jq -n --arg sys "$SYSTEM" --arg diff "$DIFF" '{
                model: "openai/gpt-4.1-mini",
                messages: [
                    {role: "system", content: $sys},
                    {role: "user", content: $diff}
                ]
            }')" | jq -r '.choices[0].message.content // empty' 2>/dev/null || true)
    fi

    if [ -z "$BODY" ]; then
        # Fallback: format ADDED [MG] lines as bullets
        BODY=$(grep -A100 '=== ADDED \[MG\] ===' /tmp/mg-diff.txt \
            | grep '^\[MG\]' | sed 's/^\[MG\] /- /' || echo "- See commit history for changes.")
    fi

    gh release delete "$TAG" --cleanup-tag --yes || :
    gh release create "$TAG" \
        "$APK_DIR/Mercurygram-${MG_VERSION_NAME}-release.apk" \
        "$APK_DIR/Mercurygram-${MG_VERSION_NAME}-debug.apk" \
        "$APK_DIR/Mercurygram-${MG_VERSION_NAME}-armeabi-v7a.apk" \
        "$APK_DIR/Mercurygram-${MG_VERSION_NAME}-arm64-v8a.apk" \
        "$APK_DIR/Mercurygram-${MG_VERSION_NAME}-x86.apk" \
        "$APK_DIR/Mercurygram-${MG_VERSION_NAME}-x86_64.apk" \
        --notes "$BODY"
fi
