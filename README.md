# Telegram-FOSS

[Telegram](https://telegram.org) is a messaging app with a focus on speed and security. It's superfast, simple and free.

This is an unofficial, FOSS-friendly fork of the original [Telegram App for Android](https://github.com/DrKLO/Telegram).

## Changes

*Replacement of non-FOSS, untrustworthy or suspicious binaries or source code:*
- Do location sharing with OpenStreetMap via MapLibre instead of Google Maps
- Use Noto emoji set instead of Apple's emoji
- **SECURITY:** BoringSSL, FFmpeg, libvpx, dav1d, and tde2e are built from source at compile time instead of shipping upstream prebuilts

*Removal or stubbing of non-FOSS, untrustworthy or suspicious binaries or source code and their functionality:*
- Google Play Services / Firebase dependencies removed from build and manifests
- Google Maps / Fused Location providers replaced by MapLibre / Android location providers
- Google Wallet, SafetyNet, Play Integrity, and related proprietary verification pieces stubbed out
- Google Cast integration removed
- Google ML Kit / Google Vision integrations removed
- Google Voice integration removed
- Google SMS retrieval removed
- HockeyApp / AppCenter crash reporting and self-updates removed
- Passkeys disabled (requires official APK signature verification)

*Other:*
- Added the ability to parse locations from intents containing a `geo:<lat>,<lon>` string
- No content restrictions
- DNS-over-HTTPS disabled (leaks proxy usage to Google; use Android Private DNS instead)
- API keys read from external `API_KEYS` file instead of hardcoded

## Compilation Guide

### Prerequisites

- JDK 17 or later
- Android SDK with NDK 21.4.7075529 and build-tools 35.0.0
- `ninja-build`, `meson`, `pip` (for native library builds)
- An `API_KEYS` file in the project root (see below)

### API Keys

1. [Obtain your own api_id](https://core.telegram.org/api/obtaining_api_id)
2. Create an `API_KEYS` file in the project root:
   ```
   APP_ID=<your_app_id>
   APP_HASH=<your_app_hash>
   ```

### Building

```bash
./gradlew assembleAfatRelease
```

The first build will automatically initialize git submodules and compile all native libraries (libvpx, dav1d, ffmpeg, BoringSSL, tde2e) from source.

## API, Protocol documentation

Telegram API manuals: https://core.telegram.org/api

MTproto protocol manuals: https://core.telegram.org/mtproto

### Localization

Translations: https://translations.telegram.org/en/android/

---

![Digital Resistance](DigitalResistance.jpg)
