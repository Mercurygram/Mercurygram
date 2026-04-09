# it.belloworld.mercurygram — MG-specific classes

## In-App Autoupdater (MgUpdateChecker.java)

Checks GitHub releases and installs per-ABI APKs in-app. **Disabled for F-Droid builds.**

- **F-Droid detection**: Compares the installed APK's signing certificate SHA-256 against the hardcoded release cert (`MG_CERT_SHA256`). Any cert mismatch = F-Droid build → updater silently disabled. Fail-safe: exceptions also disable it.
- **Check throttle**: 1-hour interval via `SharedConfig.mgLastUpdateCheckTime`. Collapses to 5 minutes (`CHECK_INTERVAL_PENDING`) while a pending update is staged so a newer prerelease landing shortly after the previous check doesn't stay hidden behind the full hour. Bypassed with `force=true`.
- **User opt-out**: `SharedConfig.disableAutoUpdate` (toggle in MG settings → Updates) skips the auto check. `force=true` (debug menu) ignores this and still runs.
- **Channel selection**: `acceptPreReleases()` returns true on the `.beta` package, on a stable install whose last installed tag is already 5-dotted, or when the user has opted in via `SharedConfig.acceptPreReleaseUpdates`. The flag picks `/releases` (all non-draft releases) vs `/releases/latest` (server-filtered to non-prerelease).
- **Version comparison**: `versionUpToDate(current, tag)` splits each tag on `.` into a numeric vector and lex-compares them, padding the shorter side with zeros. `currentInstallVersion()` reads `PackageInfo.versionName`; with `versionName = tag` baked into the APK by `gradle/mg-version.gradle`, that's the canonical 4- or 5-dotted tag for every build path (CI release, CI beta, F-Droid, sideload).
- **APK selection**: Uses `Mercurygram-<tag>-<Build.SUPPORTED_ABIS[0]>.apk` (per-ABI only; no fat APK fallback).
- **Signature verification**: After download, `verifyApkSignature()` checks the downloaded APK's cert against `MG_CERT_SHA256` before install. Deleted on mismatch.
- **Install path**: Stored in `SharedConfig.mgUpdateApkPath` (cache dir); installed via `ACTION_VIEW` intent with FileProvider URI.
- **UI entry points**: `LaunchActivity` and `ProfileActivity` call `MgUpdateChecker.checkForUpdates(false)` on startup; a bottom-sheet (`MgUpdateAlertDialog`, `MgUpdateLayout`) shows changelog + download button.
- **Dialog re-pop suppression**: dismissing the bottom sheet via "Remind me later" or tap-outside stamps `SharedConfig.mgDismissedPendingTag` with the current tag. Subsequent checks suppress the popup as long as the pending tag matches. The side-menu strip still tracks via the `appUpdateAvailable` notification. The flag clears automatically as soon as a strictly newer tag arrives (handled in `SharedConfig.setMgPendingUpdate`), so the user is re-prompted only for genuinely new versions.
- **Files**: `MgUpdateChecker.java`, `MgUpdateInfo.java`, `ui/MgUpdateAlertDialog.java`, `TMessagesProj_App/.../MgUpdateLayout.java`

## Mercurygram settings screen (ui/MercurygramSettingsActivity.java)

Single dedicated entry point for ALL user-toggleable MG features. Reached via main Settings → "Mercurygram" row (`SettingsActivity.fillItems`, item ID 100). Built on `UniversalFragment` + `UItem.asCheck/asHeader/asShadow/asButton`, mirroring `HiddenAccountsActivity`.

Sections: General, Media, Updates (hidden on F-Droid via `MgUpdateChecker.isFdroidBuild()`), Notifications. Toggling `disableUnifiedPush` requires an app restart (the activity prompts and finishes affinity).

Adding a new MG toggle: pick the right store. **Per-account** (a user might want it to differ between accounts — UX, data recording): add the field to `UserConfig` (declaration, `loadConfig`, `saveConfig`, `clearConfig`), no `mg_` prefix on the key. **Global** (system/infra, single-instance state): add the `mg_*` field to `SharedConfig` (load + save + a `toggleX()` if user-toggleable). Then add a row in `fillItems()` and a case in `onClick()` of `MercurygramSettingsActivity`. Avoid inlining MG rows into upstream `ThemeActivity` / `NotificationsSettingsActivity` — keeps rebase conflict surface minimal.

## GMS/Firebase compat stubs (compat/billing/)

Upstream Telegram references Google Play Billing classes at runtime. After removing the GMS dependency (`[TF]` patch), these stubs (`BillingClient`, `BillingResult`, `ProductDetails`, etc.) prevent `NoClassDefFoundError` on app init.

**Pattern**: If upstream adds new GMS/Google library references in the future, add matching no-op stub classes here rather than re-introducing the dependency.
