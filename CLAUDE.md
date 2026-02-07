# CLAUDE.md - Mercurygram Codebase Guide

## Project Overview

Mercurygram is an unofficial FOSS-friendly Android fork of [Telegram-FOSS](https://github.com/Telegram-FOSS-Team/Telegram-FOSS), which itself forks the official [Telegram for Android](https://github.com/DrKLO/Telegram). It replaces non-FOSS components (Google Maps, GCM, Apple emoji, prebuilt binaries) with open-source alternatives (OpenStreetMap, UnifiedPush, Noto emoji, source-built BoringSSL/FFmpeg/libvpx).

- **Package:** `it.belloworld.mercurygram`
- **Current version:** 10.14.5 (MG: 10.14.5.1)
- **License:** Apache 2.0

## Build System

### Prerequisites

- **JDK:** 17 (Temurin recommended)
- **Android SDK:** API 33, Build Tools 33.0.0
- **Android NDK:** 21.4.7075529
- **Ninja** and **yasm** (for native library builds)
- **Go (Golang)** (for native builds)
- **Linux required** (Windows not supported for building)

### Gradle Modules

| Module | Type | Purpose |
|---|---|---|
| `TMessagesProj` | Android Library | Core Telegram code, all source, JNI, resources |
| `TMessagesProj_App` | Android Application | Main APK module, depends on TMessagesProj |

Only these two modules are included in `settings.gradle`.

### Build Commands

```bash
# Debug build (all ABIs)
./gradlew assembleAfatDebug

# Release build (all ABIs)
./gradlew assembleAfatRelease
```

### Native Libraries (must build first)

Before the first Gradle build, native dependencies must be compiled from `TMessagesProj/jni/`:

```bash
export NDK=[PATH_TO_NDK]
export NINJA_PATH=[PATH_TO_NINJA]
cd TMessagesProj/jni
./build_libvpx_clang.sh
./build_ffmpeg_clang.sh
./patch_ffmpeg.sh
./patch_boringssl.sh
./build_boringssl.sh
```

### API Keys

A file named `API_KEYS` must exist at the repository root with Telegram API credentials:

```
APP_ID = 12345
APP_HASH = aaaaaaaabbbbbbccccccfffffff001122
```

Obtain keys at https://core.telegram.org/api/obtaining_api_id. **Never commit this file.**

### Product Flavors

- `afat` — All ABIs (armeabi-v7a, arm64-v8a, x86, x86_64), primary build target
- `bundleAfat` — Bundle variant for Play Store
- `bundleAfat_SDK23` — Bundle variant with minSdk 23

### Build Types

- `debug` — Debuggable, app ID suffix `.beta`
- `release` — Minified with ProGuard, production
- `standalone` — Web variant, app ID suffix `.web`
- `HA_private`, `HA_public`, `HA_hardcore` — Legacy HockeyApp variants

### Key Gradle Properties (`gradle.properties`)

```
APP_VERSION_CODE=4945
APP_VERSION_NAME=10.14.5
MG_VERSION_CODE=494501
MG_VERSION_NAME=10.14.5.1
APP_PACKAGE=it.belloworld.mercurygram
```

## Source Code Structure

### Java Source (`TMessagesProj/src/main/java/`)

| Package | Description |
|---|---|
| `org.telegram.messenger` | Core messaging logic — controllers, config, networking, services (~144 files) |
| `org.telegram.ui` | UI activities, fragments, views, cells, components (~174 files) |
| `org.telegram.tgnet` | MTProto networking layer (JNI bindings) |
| `org.telegram.SQLite` | SQLite database wrapper (JNI bindings) |
| `org.telegram.PhoneFormat` | Phone number formatting utilities |
| `it.belloworld.mercurygram.ui` | **Mercurygram-specific UI** (e.g., `MessageDetailsActivity.java`) |
| `tw.nekomimi.nekogram.helpers` | NekoX fork features (e.g., `MonetHelper.java` for Monet themes) |
| `org.webrtc` | WebRTC for voice/video calls |
| `com.google.zxing` | QR/barcode scanning |
| `androidx.recyclerview` | Custom RecyclerView backport (excluded from dependencies, bundled) |

### Key Files

| File | Purpose |
|---|---|
| `messenger/ApplicationLoader.java` | App initialization and lifecycle |
| `messenger/BuildVars.java` | Build-time configuration constants |
| `messenger/SharedConfig.java` | Global shared preferences/settings |
| `messenger/UserConfig.java` | Per-account user configuration |
| `messenger/MessagesController.java` | Message management and state |
| `messenger/ContactsController.java` | Contact management |
| `messenger/NotificationsController.java` | Notification handling |

### Native Code (`TMessagesProj/jni/`)

- **C/C++ sources:** ~17 files for audio processing, networking, image processing, neural nets
- **CMake** build via `CMakeLists.txt`
- **Submodules:** boringssl, ffmpeg, libvpx, libwebp
- **Build scripts:** `build_*.sh` and `patch_*.sh`
- **VoIP/WebRTC:** `voip/` directory

### Resources (`TMessagesProj/src/main/res/`)

- 50+ language localizations in `values-*/`
- Custom drawables, layouts, animations
- Noto emoji set in `assets/emoji/`
- GLSL shaders in `assets/shaders/`
- ML models in `assets/models/`

## Git Submodules

```
TMessagesProj/jni/libwebp  -> https://github.com/webmproject/libwebp
TMessagesProj/jni/boringssl -> https://github.com/google/boringssl
TMessagesProj/jni/libvpx   -> https://github.com/webmproject/libvpx
TMessagesProj/jni/ffmpeg   -> https://github.com/FFmpeg/FFmpeg
```

Always clone with `--recursive` or run `git submodule update --init --recursive`.

## CI/CD (GitHub Actions)

Two workflows in `.github/workflows/`:

- **`generate-apk-release.yml`** — Triggers on push to `Mercurygram` branch. Builds native libs (cached), assembles release APK, zipaligns, and signs.
- **`generate-apk-debug.yml`** — Same flow for debug APK.

### Required Secrets

- `APP_ID` / `APP_HASH` — Telegram API credentials
- `KEYSTORE_BASE64` — Base64-encoded signing keystore
- `KEYSTORE_PASSWORD` — Keystore password

## Testing

There is no active automated test suite. The Gradle test task is commented out in CI. Test code exists only within third-party native libraries (WebRTC, libsrtp).

## Code Style & Conventions

- **Language:** Java 17 (source and target compatibility)
- **Native:** C++14 / C11
- **No explicit linter/formatter config** (no checkstyle, spotless, etc.)
- **Android Lint:** `MissingTranslation`, `ExtraTranslation`, and `BlockedPrivateApi` checks are disabled
- **ProGuard:** Applied on release builds via `proguard-rules.pro`
- **RecyclerView:** Custom bundled backport — the `androidx.recyclerview` dependency is explicitly excluded

## Architecture Notes

- **Multi-account:** Managed via `AccountInstance.java` — most controllers are per-account singletons
- **Networking:** Custom MTProto implementation via JNI (`TgNetWrapper.cpp`, ~32K lines)
- **Database:** Custom SQLite wrapper via JNI (`SqliteWrapper.cpp`, ~10K lines)
- **UI pattern:** Activities host Fragments; custom views/cells are heavily used rather than XML layouts
- **Animations:** Mix of Lottie (6.4.0), Android dynamic animation, and custom frame-based animation

## Mercurygram-Specific Modifications

These are the features added on top of Telegram-FOSS:

- Profile ID display in user info
- UnifiedPush distributor selection and PUT-to-POST gateway support
- Toggle settings: rear camera for video messages, hide keyboard on scroll, hide "All Chats" tab, message details, disable secure flags
- Large photo sending (2560px vs 1280px)
- Monet/Material You theme support (`MonetHelper.java`)
- Unlocked premium app icons
- Disabled DNS-over-HTTPS (DOH) to prevent proxy leaks to Google
- Custom app icon (Hermes wing)

## Versioning

Format: `$UPSTREAM.$RELEASE` where `$UPSTREAM` is the Telegram-FOSS tag and `$RELEASE` is a patch number.

## Docker

A `Dockerfile` is provided for containerized builds using Gradle 7.0.2 + JDK 11 with the full Android SDK/NDK toolchain.

## Common Tasks for AI Assistants

- **To modify Mercurygram-specific features:** Edit files in `it.belloworld.mercurygram.ui` or `tw.nekomimi.nekogram.helpers`
- **To modify core Telegram behavior:** Edit files in `org.telegram.messenger` or `org.telegram.ui`
- **To add a new setting toggle:** Follow the pattern in `SharedConfig.java` and wire it through the appropriate settings UI fragment
- **To update version:** Edit `MG_VERSION_CODE` and `MG_VERSION_NAME` in `gradle.properties`
- **Build output location:** `TMessagesProj_App/build/outputs/apk/afat/{buildType}/app.apk`
