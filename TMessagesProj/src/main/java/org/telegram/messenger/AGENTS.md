# org.telegram.messenger — App Architecture

## MG feature flags (SharedConfig.java, ~line 238)

All MG-specific settings use the `mg_` prefix in SharedPreferences:

| Field | Default | Pref key | What it does |
|---|---|---|---|
| `disableUnifiedPush` | false | `mg_disableUnifiedPush` | Disables UnifiedPush entirely, falls back to polling |
| `unifiedPushGateway` | `"https://p2p.belloworld.it/"` | `mg_unifiedPushGateway2` | Base URL of the WebPush gateway |
| `unifiedPushEndpointUrl` | `""` | `mg_unifiedPushEndpointUrl` | Raw UP distributor endpoint, saved on `onNewEndpoint()` |
| `pushStringSimple` | `""` | `mg_pushStringSimple` | type-4 Simple Push token URL (secret chat wake-ups) |
| `messageDetailsMenu` | false | `mg_messageDetailsMenu` | Adds "Message Details" item to long-press message menu |
| `disableSecureFlags` | false | `mg_disableSecureFlags` | Removes `FLAG_SECURE` from windows — allows screenshots and shows app content in recents |
| `removeAdsAndProxySponsor` | false | `mg_removeAdsAndProxySponsor` | Hides sponsored messages and proxy sponsor banners |
| `useRearRoundVideos` | false | `mg_useRearRoundVideos` | Uses rear-facing camera by default for round video messages |
| `hideKeyboardOnScroll` | false | `mg_hideKeyboardOnScroll` | Hides soft keyboard when scrolling up in a chat |
| `hideAllTab` | false | `mg_hideAllTab` | Hides the "All" tab from the chat list filter bar |
| `sendLargePhotos` | false | `mg_sendLargePhotos` | Sends photos at original resolution instead of compressed (bypasses the 1280px resize) |

## Debug menu (ProfileActivity.java)

Long-press on version in Profile → debug items array. MG items at indices 39–42:
- 39: Message Details menu toggle
- 40: UnifiedPush disable toggle
- 41: Secure Flags disable toggle
- 42: Remove Ads & Proxy Sponsor toggle

## UnifiedPush

- **Connector**: `org.unifiedpush.android:connector:3.0.9` (Maven Central) — pinned to 3.0.x; see comment in TMessagesProj/build.gradle
- **Service**: `UnifiedPushReceiver.java extends PushService` (declared as `<service>` in manifest)
- **Configuration UI**: `NotificationsSettingsActivity.java`
- **ntfy.sh**: Default ntfy.sh server is blacklisted. On `onNewEndpoint()`, if endpoint URL contains `ntfy.sh`, `showNtfyDefaultServerDialog()` triggers — auto-switches to first non-ntfy distributor, or disables UP if ntfy is the only option.
- **Encryption**: aesgcm (RFC 8188 "Draft 4" — the format Telegram uses for PUSH_TYPE_WEB / token type 10)
- **Key management**: App generates P-256 keypair + auth secret (`SharedConfig.webPushPrivateKey/PublicKey/AuthSecret`). The connector library's `DefaultKeyManager` keys are unused.
- **Decryption**: `WebPushDecryptor.java` handles aesgcm Draft 4. Connector's auto-decryption (RFC 8291/aes128gcm) fails harmlessly and passes raw bytes through.
- **Payload**: After aesgcm decryption → JSON `{"p":"<base64url-mtproto>"}`. The `"p"` field is passed to `processRemoteMessage()` → MTProto decryption → rich notification.

### Dual token registration

- **token_type=10** (Web Push / `PUSH_TYPE_WEB`): aesgcm encrypted, regular messages. Token = JSON `{endpoint, keys: {p256dh, auth}}` → gateway `/aesgcm?e=<UP-endpoint>`.
- **token_type=4** (Simple Push / `PUSH_TYPE_SIMPLE`): plain `PUT version=N` wake-up, secret chat notifications. Token = plain URL `<gateway>/<url-encoded-UP-endpoint>`. Registered via `PushListenerController.sendSimplePushRegistration()` → `MessagesController.registerSimplePush()`. Stored in `SharedConfig.pushStringSimple`.

Both kept in sync: `registerForPush()` always re-registers type-4 alongside type-10 (before the type-10 early-return guard). On distributor unregistration, both tokens are sent `unregisterDevice`.

### Push notification flow

**Regular message:**
1. Telegram → POST `/aesgcm` (type 10) → gateway forwards + stamps correlation cache
2. Telegram → PUT `/` (type 4) → gateway sees POST in cache → suppresses PUT
3. UP delivers encrypted payload → `WebPushDecryptor.decrypt()` → `processRemoteMessage()` → notification

**Secret chat message:**
1. Telegram → PUT `/` (type 4) → gateway waits 200 ms, no POST found → forwards `version=N`
2. UP delivers → aesgcm fails (not encrypted) → MTProto fallback via `ConnectionsManager.onInternalPushReceived()`

**Registration:**
- `onNewEndpoint()` → registers type-10 + type-4 tokens
- `registerForPush()` (on every `getDifference()`) → re-registers type-4 if `pushStringSimple` non-empty
- Migration: if `pushStringSimple` empty but `unifiedPushEndpointUrl` set, `registerForPush()` reconstructs type-4 URL automatically

**WakeLock**: static, reference-counted (`sWakeLock`), 30 s hard timeout safety net.

## Monet themes (Android 12+)

- `MonetHelper` at `tw.nekomimi.nekogram.helpers`
- Assets: `monet_light.attheme`, `monet_dark.attheme`
- Registered in `LaunchActivity` onCreate/onDestroy
