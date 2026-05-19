# org.telegram.messenger — App Architecture

## MG feature flags

MG-specific settings split between **global** `SharedConfig` (`mg_*` keys in `mainconfig`) and **per-account** `UserConfig` (`userconfig` / `userconfig<N>`). User-toggleable settings live in `MercurygramSettingsActivity` (Settings → Mercurygram); two advanced toggles remain in the debug menu.

### Global (SharedConfig.java)

App-wide infrastructure or shared resources that cannot meaningfully be account-scoped (push tokens, updater state, window flags).

| Field | Default | Pref key | What it does | Exposed in |
|---|---|---|---|---|
| `disableUnifiedPush` | false | `mg_disableUnifiedPush` | Disables UnifiedPush entirely, falls back to polling | MG settings (Notifications) |
| `unifiedPushGateway` | `"https://p2p.belloworld.it/"` | `mg_unifiedPushGateway2` | Base URL of the WebPush gateway | MG settings (Notifications) |
| `unifiedPushEndpointUrl` | `""` | `mg_unifiedPushEndpointUrl` | Raw UP distributor endpoint, saved on `onNewEndpoint()` | internal |
| `pushStringSimple` | `""` | `mg_pushStringSimple` | type-4 Simple Push token URL (secret chat wake-ups) | internal |
| `disableSecureFlags` | false | `mg_disableSecureFlags` | Removes `FLAG_SECURE` from windows — allows screenshots and shows app content in recents | debug menu |
| `removeAdsAndProxySponsor` | false | `mg_removeAdsAndProxySponsor` | Hides sponsored messages and proxy sponsor banners | debug menu |
| `disableAutoUpdate` | false | `mg_disableAutoUpdate` | Skips the GitHub update check at startup. Forced/manual checks (debug menu) still run. Auto-hidden on F-Droid builds. | MG settings (Updates) |
| `acceptPreReleaseUpdates` | false | `mg_acceptPreReleaseUpdates` | Stable-channel opt-in for 5-dotted pre-release updates. When set (or when `PackageInfo.versionName` is already a 5-dotted tag), `MgUpdateChecker` queries `/releases` instead of `/releases/latest`. Enabling shows a warning dialog; the row is locked on (greyed) while the install is on a 5-dotted tag. Hidden on the `.beta` package and on F-Droid builds. | MG settings (Updates) |
| `mgDismissedPendingTag` | null | `mg_dismissedPendingTag` | Last pending tag the user dismissed via "Remind me later" / tap-outside on `MgUpdateAlertDialog`. While this matches the current pending tag, `MgUpdateChecker.checkInternal` suppresses re-showing the bottom sheet (the side-menu strip still tracks the pending). Auto-cleared by `setMgPendingUpdate` whenever a strictly different tag arrives, so the next genuine bump pops the dialog again. | internal |
| `useSystemFont` | false | `mg_useSystemFont` | In `AndroidUtilities.getTypeface()`, swaps bundled `fonts/r*.ttf` / `mw_bold.ttf` / `courier_new_bold.ttf` for the closest Android system typeface (`sans-serif`, `sans-serif-medium`, `sans-serif-condensed`, `serif`, `MONOSPACE`). `bold()` also returns `Typeface.create(null, 500, false)` on API 28+. Toggling clears `typefaceCache` + `mediumTypeface`. `Theme.typefaceCache` is a process-wide static — per-account would need rebuilding the cache on every account switch. | MG settings (General) |

### Per-account (UserConfig.java)

UX or data-recording toggles a user plausibly wants to vary between accounts (e.g. work vs personal). Stored in the same per-account `userconfig` / `userconfig<N>` file as `sendLargePhotos`, `rearRoundCamera`, `hideAllTab`. Pref keys match the field name (no `mg_` prefix — the file is already account-private).

| Field | Default | Pref key | What it does | Exposed in |
|---|---|---|---|---|
| `messageDetailsMenu` | false | `messageDetailsMenu` | Adds "Message Details" item to long-press message menu | MG settings (General) |
| `savedMessagesHistory` | false | `savedMessagesHistory` | Archives server-deleted messages + pre-edit versions into a separate DB (`mg_message_history.db`) via `MgMessageHistory`; hooks `MessagesController` delete/edit dispatch. DB rows are already keyed by `(account, dialog, mid)` so per-account recording matches the on-disk shape. **Inline ghost** (deleted): the message stays in the chat thread, marked via `MessageObject.mgDeletedGhost`. Live path: set in `ChatActivity` just before `processDeletedMessages` (the mid is also filtered out of the local delete list so the in-memory cell survives the session — `MessagesStorage.markMessagesAsDeleted` still removes the row from `messages_v2`). Cold-restart path: `mgPrimeHistoryCaches` reads the full `TLRPC.Message` blobs via `MgMessageHistory.getDeletedEntries` on `Utilities.globalQueue`, then `mgInjectGhosts` rebuilds `MessageObject`s and inserts them into the chat's `messages` / `messagesDict[0]` / `messagesByDays` / `groupedMessagesMap` at the correct chronological position (mirrors the canonical new-message insert at `ChatActivity.java:25097-25360`). Bounded by `MG_GHOST_INJECT_CAP_PER_CALL=200`, idempotent (dedups against `messagesDict[0]`), date-window-clamped via `minDate[0]`/`maxDate[0]` (widened by `endReached`/`forwardEndReached`) so it does not race pagination — `minDate`/`maxDate`/`maxMessageId`/`minMessageId` are deliberately not mutated. Called again after each `messagesDidLoad` post-`checkGroupMessagesOrder()` so paginating up surfaces ghosts in the newly loaded window. First cut skips topics/threads (`isThreadChat() || isTopic`) and `chatAdapter.isFiltered` views. `ChatMessageCell.draw()` wraps the cell in a `saveLayerAlpha(115)` when the flag is set (gray-out); `checkReplyTouchEvent` and the long-tap Reply menu drop the option so a ghost can't be replied to. The "Deleted ·" prefix in `timeString` is preserved. **Per-message edit history**: `MgMessageEditHistoryActivity` opens from a "Edit history" item the long-press menu injects between Delete and "Message Details" when the row has at least one pre-edit version stored (`MgMessageHistory.hasEditHistoryFor`). ToS guard: `MgMessageHistory.isExcluded` never archives or ghosts self-destruct / TTL / secret-chat messages (api/terms §1.4); re-checked at inject time as defence-in-depth. | MG settings (General) |
| `disableLivePhotosByDefault` | false | `disableLivePhotosByDefault` | Pre-disables Live Photo on Google MotionPhotos picked from the gallery; the attach toolbar's motion icon still re-enables it per-selection. Also drops the upstream JPEG re-encode in `SendMessagesHelper`, so a discarded Live Photo is sent as the original JPEG bytes. Read at `MediaController.loadGalleryPhotosAlbums()` and `refreshLivePhotoDefault()` from `UserConfig.getInstance(UserConfig.selectedAccount)` — the gallery is loaded in the active account's context. | MG settings (Media) |
| `hideChatKeyboard` | false | `hideChatKeyboard` | Auto-hide keyboard when the chat list starts scrolling. Read in `ChatActivity` via `getUserConfig().hideChatKeyboard`. | MG settings (General) |
| `sendLargePhotos` | false | `sendLargePhotos` | Send photos at 2560 px instead of 1280 px. | MG settings (Media) |
| `rearRoundCamera` | false | `rearRoundCamera` | Use rear camera for video messages/round stickers. | MG settings (Media) |
| `hideAllTab` | false | `hideAllTab` | Hide the "All" chat-folder tab in the main list. | MG settings (General) |

### Migration

A one-shot migration on first launch after the per-account split copies each formerly-global value (`mg_messageDetailsMenu`, `mg_savedMessagesHistory`, `mg_disableLivePhotosByDefault`, `hide_chat_keyboard`) into **every** account's `userconfig` / `userconfig<N>` file, then removes the old keys from `mainconfig` and sets `mg_perAccountMigrationV1Done = true` to prevent re-runs. See `SharedConfig.migratePerAccountSettingsV1()`.

## Debug menu (ProfileActivity.java)

Long-press on version in Profile → debug items array. MG items at indices 39–41:
- 39: Secure Flags disable toggle
- 40: Remove Ads & Proxy Sponsor toggle
- 41: Force-check for Mercurygram update (also bypasses `mg_disableAutoUpdate`)

`SettingsActivity.java` carries the same two toggles at indices 42–43.

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
