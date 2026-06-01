<div align="center">

<img src="./.github/assets/logo.png" alt="Mercurygram logo" title="Mercurygram logo" width="80"/>

# Mercurygram

[Telegram](https://telegram.org) is a messaging app with a focus on speed and security. It’s superfast, simple and free.

This is an unofficial fork of [Telegram App for Android](https://github.com/DrKLO/Telegram), maintained by rebasing Mercurygram patches and forward-ported de-googling patches on top of upstream Telegram.

[![Releases](https://img.shields.io/github/release/Mercurygram/Mercurygram.svg)](https://github.com/Mercurygram/Mercurygram/releases/latest)
[![Discussions](https://img.shields.io/badge/Official-Group-blue.svg?logo=telegram)](https://t.me/Mercurygram)

</div>

## Install

Mercurygram publishes two kinds of build. The tag shape tells you which:

| Channel | Tag shape | Example | Packages | When it ships |
|---|---|---|---|---|
| **Stable** | 4-part `X.Y.Z.M` (M ≥ 1) | `12.7.3.1` | stable only (`it.belloworld.mercurygram`) | Tagged release. Also goes to F-Droid / IzzyOnDroid. |
| **Snapshot** | 5-part `X.Y.Z.M.K` (M ≥ 1) | `12.7.3.1.42` | **both** stable and beta (`it.belloworld.mercurygram.beta`) | Every push to the `Mercurygram` branch (`beta.yml`). Snapshot of the next stable. |
| **Pre-stable** | 5-part `X.Y.Z.0.K` | `12.7.3.0.5` | **both** stable and beta (`it.belloworld.mercurygram.beta`) | After an upstream rebase, before the first `X.Y.Z.M` (M ≥ 1) stable for that upstream ships (`beta.yml`). |

Snapshots and pre-stable builds both publish two APKs per release: a Release-flavor APK that updates the stable package side and a Debug-flavor APK (filename infixed with `-debug`) that updates the `.beta` package side. Filenames: `Mercurygram-<tag>-<abi>.apk` (Release) and `Mercurygram-debug-<tag>-<abi>.apk` (Debug). Stable installs pull the Release APK via the in-app updater opt-in toggle; `.beta` installs pull the Debug APK.

Versions order naturally: `12.7.3.0.5 < 12.7.3.1 < 12.7.3.1.42 < 12.7.3.2`.

### One-click install via [Obtainium](https://obtainium.imranr.dev/)

Open the link on your Android device and the app source pre-fills with the right release filter, package ID, and APK filename filter.

#### Main app

Same package ID across these entries; pick **one**. Stable + Pre-release mirrors the in-app **Accept pre-release updates** toggle and tracks both 4-dotted stable and 5-dotted pre-release tags.

**Stable** — package `it.belloworld.mercurygram`. Tagged stable releases only.

[![Add Mercurygram to Obtainium](https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png)](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22it.belloworld.mercurygram%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FMercurygram%2FMercurygram%22%2C%22author%22%3A%22Mercurygram%22%2C%22name%22%3A%22Mercurygram%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%20%5C%22%5EMercurygram-%5C%5C%5C%5Cd%5C%22%7D%22%7D)

**Stable + Pre-release** — package `it.belloworld.mercurygram`. Tagged stable releases plus 5-dotted pre-releases (Release-flavor APK).

[![Add Mercurygram (Stable + Pre-release) to Obtainium](https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png)](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22it.belloworld.mercurygram%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FMercurygram%2FMercurygram%22%2C%22author%22%3A%22Mercurygram%22%2C%22name%22%3A%22Mercurygram%20%28Stable%20%2B%20Pre-release%29%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3A%20true%2C%20%5C%22filterReleaseTitlesByRegEx%5C%22%3A%20%5C%22%5E%5C%5C%5C%5Cd%2B%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%28%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%29%3F%24%5C%22%2C%20%5C%22apkFilterRegEx%5C%22%3A%20%5C%22%5EMercurygram-%5C%5C%5C%5Cd%5C%22%7D%22%7D)

**Beta** — package `it.belloworld.mercurygram.beta`. Per-push snapshots (`X.Y.Z.M.K`, M ≥ 1) and pre-stable test builds (`X.Y.Z.0.K`). Debug-flavor APK.

[![Add Mercurygram Beta to Obtainium](https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png)](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22it.belloworld.mercurygram.beta%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FMercurygram%2FMercurygram%22%2C%22author%22%3A%22Mercurygram%22%2C%22name%22%3A%22Mercurygram%20Beta%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3A%20true%2C%20%5C%22filterReleaseTitlesByRegEx%5C%22%3A%20%5C%22%5E%5C%5C%5C%5Cd%2B%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%24%5C%22%2C%20%5C%22apkFilterRegEx%5C%22%3A%20%5C%22%5EMercurygram-debug-%5C%22%7D%22%7D)

#### Tor plugin (optional companion APK)

Same package ID across these entries; pick **one**. The plugin only ships a Release-flavor APK (no separate `.beta` package).

**Stable** — package `it.belloworld.mercurygram.plugin.tor`. Tagged stable releases only.

[![Add Mercurygram Tor Plugin to Obtainium](https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png)](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22it.belloworld.mercurygram.plugin.tor%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FMercurygram%2FMercurygram%22%2C%22author%22%3A%22Mercurygram%22%2C%22name%22%3A%22Mercurygram%20Tor%20Plugin%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%20%5C%22%5EMercurygram-tor-plugin-%5C%22%7D%22%7D)

**Stable + Pre-release** — package `it.belloworld.mercurygram.plugin.tor`. Tagged stable releases plus 5-dotted pre-releases. Pair this with **Main Beta** or **Main Stable + Pre-release** so the plugin tracks the same release cadence.

[![Add Mercurygram Tor Plugin (Stable + Pre-release) to Obtainium](https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png)](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22it.belloworld.mercurygram.plugin.tor%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FMercurygram%2FMercurygram%22%2C%22author%22%3A%22Mercurygram%22%2C%22name%22%3A%22Mercurygram%20Tor%20Plugin%20%28Stable%20%2B%20Pre-release%29%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3A%20true%2C%20%5C%22filterReleaseTitlesByRegEx%5C%22%3A%20%5C%22%5E%5C%5C%5C%5Cd%2B%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%28%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%29%3F%24%5C%22%2C%20%5C%22apkFilterRegEx%5C%22%3A%20%5C%22%5EMercurygram-tor-plugin-%5C%22%7D%22%7D)

> **Stable users:** the in-app updater can be opted in to pre-release updates from **Settings → Mercurygram → Updates → Accept pre-release updates**. Enabling shows a warning dialog; once installed a pre-release you can only turn the toggle off again after upgrading to a stable (4-part) release.

## Features

- Add ID in Profile Info
- Add a menu in Notifications and Sounds in order to set the UnifiedPush distributor. The same menu may be long-clicked to inspect recent UnifiedPush notification/decryption stats
- Add a menu in Notifications and Sounds in order to set the [UnifiedPush WebPush gateway](#unifiedpush-webpush-gateway)
- Add toggle setting in Chat Settings to start video messages with rear-facing camera
- Add toggle setting in Chat Settings to hide keyboard on chat scroll
- Add toggle setting in Chat Setting to hide "All Chats" tab (feature from NekoX)
- Add administrators item in group/channel info
- Add toggle setting in Debug Menu to enable Message Details menu
- Add toggle setting in Debug Menu to disable Unified Push support
- Add toggle setting in Debug Menu to disable Secure Flags. This option must **only** be used for debugging
- Add toggle setting in Debug Menu to remove sponsored messages and proxy sponsor banners. This option must **only** be used for debugging
- Re-add Monet themes ([#31](https://github.com/Mercurygram/Mercurygram/pull/31))
- Disabled DOH resolving since this leaks your used proxy to Google and it's not needed since Android DNS over TLS should be used instead
- Unlock premium app icons for anybody
- Unlock 5 accounts (was 3) and remove premium check for number of accounts
- Add toggle setting in Chat Settings to send large photos (2560px instead of 1280px)
- Add per-account toggle setting in Settings → Mercurygram to hide the stories bar in the chat list
- Telegram application icons are replaced with [hermes wing (Created by Anthony Ledoux from Noun Project)](https://thenounproject.com/icon/hermes-wing-3559879/)

### TF-originated de-googling patches

These patches were originally derived from the Telegram-FOSS effort, but Mercurygram now forward-ports and rebases them directly onto upstream Telegram.

*Replacement of non-FOSS, untrustworthy or suspicious binaries or source code:*
- Do location sharing with OpenStreetMap via MapLibre instead of Google Maps
- Use Noto emoji set instead of Apple's emoji
- Google/Firebase push services replaced with [UnifiedPush](https://unifiedpush.org)
- **SECURITY:** BoringSSL, FFmpeg, libvpx, dav1d, and tde2e are built from source at compile time instead of shipping upstream prebuilts

*Removal or stubbing of non-FOSS, untrustworthy or suspicious binaries or source code and their functionality:*
- Google Play Services / Firebase dependencies from the default Mercurygram build and manifests
- Google Maps / Fused Location providers are stubbed out and replaced by MapLibre / Android location providers
- Google Wallet, SafetyNet, Play Integrity, and related proprietary verification pieces are stubbed out through local compatibility classes
- Google Cast integration
- Google ML Kit / Google Vision integrations, including barcode and face detection paths
- Android passkey support is disabled as Telegram servers verify the APK signature, which fails for unofficial forks

*Other:*
- Added the ability to parse locations from intents containing a `geo:<lat>,<lon>,<zoom>` string
- Force static map previews from Telegram
- No content restrictions

### Privacy & anti-tracking (Mercurygram-only)

Mercurygram adds MTProto-layer mitigations that upstream Telegram and Telegram-FOSS do not ship:

- **Reduce network tracking** — opt-in toggle at *Settings → Mercurygram → Privacy*. Defends against the passive `auth_key_id` fingerprint described in the OCCRP / Symbolic Software review of May 2026 ([review-confirms-telegram-tracking-vulnerability](https://www.occrp.org/en/news/review-confirms-telegram-tracking-vulnerability)). MTProto's outer obfuscation2 stream cipher is recoverable from on-wire bytes (the AES-CTR keys are derived from the visible 64-byte TCP-handshake bytes via a public algorithm), so a passive observer with a full pcap from connection start can decode the obfuscation and read the 8-byte `auth_key_id` at the front of every MTProto frame. That id is stable enough to correlate a device across IP/network changes even though message contents stay encrypted. When the toggle is on:
  - Every default-network change (Wi-Fi ↔ cellular, VPN flip, IP rebind) forces a fresh PFS temp-key handshake so `auth_key_id` rotates across network boundaries (`MgNetworkChangeWatcher` + native `ConnectionsManager::rotateTempAuthKeys()`).
  - The CDN-redirect (`upload.fileCdnRedirect`) path is refused once per file download and the request is reissued against the main DC, keeping the long-lived permanent `auth_key_id` (which CDN nodes use because PFS is off there) off the wire.
  - `TEMP_AUTH_KEY_EXPIRE_TIME` is shortened from 24 h to 1 h via a runtime variable, with a `1h → 6h → 24h` ladder that bumps the TTL on a `bindTempAuthKey` `ENCRYPTED_MESSAGE_INVALID` rejection — protects against a future server-side policy tightening without logging the user out (when the ladder exhausts the toggle auto-disables). Probed lower bound on DC2 in 2026-05 is 60 s; 1 h leaves a 60× safety margin. See [`scripts/probe-temp-key-ttl.py`](scripts/probe-temp-key-ttl.py) to re-measure the floor after a rebase.
- **Tor with idle auto-stop (optional companion APK)** — *Settings → Mercurygram → Privacy → Use Tor*. Tor support now lives in a separate, optional companion APK on F-Droid (**Mercurygram Tor Plugin**, package `it.belloworld.mercurygram.plugin.tor`); toggling Use Tor prompts to install the plugin if it isn't present. The plugin runs the same daemon (vendored from [tor-android](https://github.com/guardianproject/tor-android), built reproducibly from source) in its own process — only the packaging changes. MTProto is pinned to a loopback SOCKS5 the moment the toggle flips so direct connections cannot leak even before bootstrap finishes. Tor shuts down after a debounced idle window (default 5 min, configurable) once the app is backgrounded and no VoIP / downloads are active, and restarts on resume or on a UnifiedPush fallback wake — keeping battery cost in the same range as upstream Telegram while the user is not actively using the app. Telegram still sees the Tor exit; latency-sensitive features (calls) may suffer.
- **Privacy-respecting translation engine** — *Settings → Mercurygram → Privacy → Translation → Engine* picks how received messages (manual translate, per-chat auto-translate, and the in-chat translate bar) are translated, instead of always hitting Telegram's cloud:
  - *Telegram cloud* (default) — upstream behaviour; the text is sent to Telegram's servers, which forward it to Google.
  - *Alternative HTTP* — routes the text through a public [Mozhi](https://codeberg.org/aryak/mozhi) privacy proxy that aggregates several engines (DuckDuckGo, LibreTranslate, Google, MyMemory, Reverso); pick the engine and instance. Keeps the query off Telegram's servers.
  - *Offline (external app)* — delegates every translation to [Offline Translator](https://f-droid.org/packages/dev.davidv.translator/) (GPL-3.0, Bergamot offline models) over an AIDL bound service. Background dispatch — the translator UI never opens — so the message text never leaves your device. Bold / italic / code formatting can be lost because Bergamot returns plain text.
  - *Auto-fallback* (default on) routes offline-engine failures (no model for the language pair, language not detected, service unreachable) to the Alternative HTTP path; Telegram cloud is never chosen as the fallback.
- **On-device voice transcription** — *Settings → Mercurygram → Privacy → Voice transcription*. Transcribes voice and video messages locally with [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (Tiny / Base / Small models, downloaded or imported on demand). The audio never leaves your device and it works without Telegram Premium, unlike the built-in cloud transcription. The spoken-language hint is per-account (e.g. English on a work account, Italian on a personal one).
- **Disable global search** — opt-in per-account toggle at *Settings → Mercurygram → Privacy*. Suppresses Telegram's global search (public username/channel lookup and the `messages.searchGlobal` RPC) so a search query only ever filters your own chats and contacts locally, never broadcasting it to the server.
- **Hidden accounts** — additional accounts can be marked hidden behind the existing passcode (*Settings → Mercurygram → General → Hidden accounts*); they don't appear in the account switcher when the passcode is locked.
- **Anti-delete & anti-edit message history** — opt-in per-account (*Settings → Mercurygram → General → Save deleted & edited messages*). Server-deleted messages stay in the chat as a grayed-out ghost; edited messages keep all previous versions accessible from the message menu. Self-destructing / TTL / secret-chat messages are never recorded (api/terms §1.4).
- **De-googled UnifiedPush + WebPush** — no Google Play Services / Firebase Cloud Messaging anywhere in the binary; push notifications use [UnifiedPush](https://unifiedpush.org) with end-to-end WebPush encryption (`aesgcm` Draft 4 — decrypted locally; the gateway only sees ciphertext). See [UnifiedPush WebPush gateway](#unifiedpush-webpush-gateway).
- **DoH resolving disabled** — upstream Telegram falls back to Google DoH when normal DNS fails, which leaks the user's proxy/IP to Google. Mercurygram drops that path; Android's system DNS-over-TLS handles the same encryption-in-transit need without the leak.
- **Native crypto built from source** — BoringSSL, FFmpeg, libvpx, dav1d, and tde2e are compiled from source at build time instead of shipping upstream prebuilts (no opaque third-party binaries in the APK).

## Notes

In order to have reliable notifications, it may be necessary to set battery
optimization to **Not optimized** for Mercurygram (no, it won't use more battery).

Background Connections setting is not necessary and uses lot of battery, so
please disable it when you use UnifiedPush.

If you set Battery optimization to Not optimized, Keep-Alive Service will be not
necessary.

See [dontkillmyapp](https://dontkillmyapp.com/) for more information.

If you can't/want set Battery optimization to Not optimized and you don't
receive notifications after a while (more than 30 minutes) please enable
Keep-Alive Service instead.

## UnifiedPush WebPush gateway

Mercurygram uses Telegram's WebPush notifications through UnifiedPush.

When the app registers with a UnifiedPush distributor, it generates its own WebPush keypair and auth secret, then sends Telegram a WebPush token in JSON form:

```json
{"endpoint":"https://<gateway>/aesgcm?e=<distributor-endpoint>","keys":{"p256dh":"...","auth":"..."}}
```

Telegram encrypts notifications with WebPush `aesgcm` (Draft 4) and sends them to the configured gateway. The gateway forwards them to the chosen UnifiedPush distributor while embedding the `Encryption` and `Crypto-Key` headers into the request body, because UnifiedPush distributors do not preserve arbitrary HTTP headers.

Mercurygram then decrypts the payload locally and passes the MTProto notification payload to Telegram's normal notification pipeline. If decryption fails, it falls back to a wake-up notification path.

The gateway is configurable from Notifications and Sounds. Mercurygram currently defaults to https://p2p.belloworld.it/.

> **Note:** `ntfy.sh` (the public hosted instance) does not work through
> the default Mercurygram gateway at `https://p2p.belloworld.it/`.
> That gateway is hosted on OCI infrastructure, and its IP is repeatedly
> blocked by `ntfy.sh` due to connection volume. The production nginx
> in front of the gateway short-circuits `ntfy.sh` endpoints with an
> immediate 201 response instead of proxying them.
> If you want to use `ntfy`, prefer a self-hosted instance.

Two self-hostable gateway implementations are included in this repository:

- [Python](https://github.com/Mercurygram/Mercurygram/tree/Mercurygram/Gateways/Python)
- [Rust](https://github.com/Mercurygram/Mercurygram/tree/Mercurygram/Gateways/Rust)

The public Rust instance only accepts Telegram server IP ranges (https://core.telegram.org/resources/cidr.txt) to reduce abuse.

## Why the name Mercurygram?

For a couple of reasons:

- Mercury is the Roman, and I'm Italian, God and the "**messenger** of the gods"
- The logo is a stylized 'F' representing his winged shoes, but it also resembles an 'F' in honor of **Freddy Mercury**.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the contribution guide. **Translations
of the Mercurygram-only strings are especially welcome** — translators do not
need to know the codebase, just edit the relevant
`TMessagesProj/src/main/res/values-<locale>/strings.xml` and open a PR. Run
`./scripts/check-mg-translations.sh` to see which keys are still missing per
locale.

## Current Maintainers

- [drizzt](https://github.com/drizzt)
- you? :)

## Contributors
- [quqkuk](https://github.com/quqkuk)

## Current Telegram-FOSS Maintainers

- [thermatk](https://github.com/thermatk)
- you? :)

## Telegram-FOSS Contributors

- [slp](https://github.com/slp)
- [Bubu](https://github.com/Bubu)
- [Sudokamikaze](https://github.com/Sudokamikaze)
- [l2dy](https://github.com/l2dy)
- [maximgrafin](https://github.com/maximgrafin)
- [vn971](https://github.com/vn971)
- [theel0ja](https://github.com/theel0ja)
- [AnXh3L0](https://github.com/AnXh3L0)
- [noplanman](https://github.com/noplanman)
- [vk496](https://github.com/vk496)
- [verdulo](https://github.com/verdulo)
- [anupritaisno1](https://github.com/anupritaisno1)
- [nekohasekai](https://github.com/nekohasekai)
- [kdrag0n](https://github.com/kdrag0n)
- [terachad](https://github.com/terachad)
- [ppnplus](https://github.com/ppnplus)
- [luvletter2333](https://github.com/luvletter2333)
- [23rd](https://github.com/23rd)
- [proletarius101](https://github.com/proletarius101)
- [CWJamieson](https://github.com/CWJamieson)
- [verdulo](https://github.com/verdulo)
- [tehcneko](https://github.com/tehcneko)

## Versioning

Tag shape encodes the release channel (see the [Install](#install) section for the table):

- **Stable** — `X.Y.Z.M` (4-part, `M ≥ 1`). `X.Y.Z` is the upstream Telegram version; `M` is the Mercurygram minor revision on top of it. Goes to the `it.belloworld.mercurygram` package, F-Droid, IzzyOnDroid.
- **Snapshot** — `X.Y.Z.M.K` (5-part, `M ≥ 1`). Per-push automated build between stable `X.Y.Z.M` and `X.Y.Z.(M+1)`. `K` is per-stable-bump monotonic. Goes to the `it.belloworld.mercurygram.beta` package and (for opted-in stable installs) the `it.belloworld.mercurygram` package.
- **Pre-stable** — `X.Y.Z.0.K` (5-part, `M = 0`). Per-push automated build issued between an upstream rebase and the first `X.Y.Z.M` (M ≥ 1) stable for that upstream. Lets testers exercise the upcoming stable before it gets the official 4-part tag. Stops being published once any `X.Y.Z.M` ≥ 1 stable exists for the current upstream. `M = 0` is the namespace marker — no `X.Y.Z.0` 4-part tag is ever created.

Pure lex compare on the dotted integer vector (shorter padded with zero) gives the right chronology: `12.7.3.0.5 < 12.7.3.1 < 12.7.3.1.42 < 12.7.3.2`.

`MgUpdateChecker` reads the GitHub tag from `PackageInfo.versionName` — the manifest carries the tag verbatim (see `gradle/mg-version.gradle`), so the canonical tag is available for every install path (in-app updater, sideload, F-Droid).

## API, Protocol documentation

Telegram API manuals: https://core.telegram.org/api

MTproto protocol manuals: https://core.telegram.org/mtproto

## Building

**NOTE: Building on Windows is, unfortunately, not supported.
Consider using a Linux VM or dual booting.**
![WindowsSupport](/tgfoss-build-under-win.gif?raw=true)

**Prerequisites:** Android SDK with the NDK version pinned by `ndkVersion` in `TMessagesProj/build.gradle`, JDK 17, and [Ninja](https://ninja-build.org/).

Clone the repository (submodules are initialized automatically at build time):

```
git clone https://github.com/Mercurygram/Mercurygram.git
```

Build with Android Studio or from the command line:

```bash
# Fat APK (all ABIs)
./gradlew assembleAfatRelease

# Single-ABI APKs (F-Droid)
./gradlew assembleAfatFdArm32Release   # armeabi-v7a
./gradlew assembleAfatFdArm64Release   # arm64-v8a
./gradlew assembleAfatFdX86Release     # x86
./gradlew assembleAfatFdX86_64Release  # x86_64
```

Native libraries (FFmpeg, BoringSSL, libvpx, dav1d, tde2e) are built from source automatically on the first build and cached for subsequent runs.

If you want to publish a modified version of Telegram:
- You should get **your own API key** here: https://core.telegram.org/api/obtaining_api_id and create a file called `API_KEYS` in the source root directory.
  The contents should look like this:
  ```
  APP_ID = 12345
  APP_HASH = aaaaaaaabbbbbbccccccfffffff001122
  ```
- Do not use the name Telegram and the standard logo (white paper plane in a blue circle) for your app — or make sure your users understand that it is unofficial
- Take good care of your users' data and privacy
- **Please remember to publish your code too in order to comply with the licenses**

# DIGITAL RESISTANCE

![DIGITALRESISTANCE](/DigitalResistance.jpg?raw=true "DIGITALRESISTANCE")
