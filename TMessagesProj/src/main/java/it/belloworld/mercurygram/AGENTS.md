# it.belloworld.mercurygram — MG-specific classes

## In-App Autoupdater (MgUpdateChecker.java)

Checks GitHub releases and installs per-ABI APKs in-app. **Disabled for F-Droid builds.**

- **F-Droid detection**: Compares the installed APK's signing certificate SHA-256 against the hardcoded release cert (`MG_CERT_SHA256`). Any cert mismatch = F-Droid build → updater silently disabled. Fail-safe: exceptions also disable it.
- **Check throttle**: 1-hour interval via `SharedConfig.mgLastUpdateCheckTime`. Bypassed with `force=true`.
- **APK selection**: Prefers `Mercurygram-<tag>-<Build.SUPPORTED_ABIS[0]>.apk`; falls back to the fat `Mercurygram-<tag>-release.apk`.
- **Signature verification**: After download, `verifyApkSignature()` checks the downloaded APK's cert against `MG_CERT_SHA256` before install. Deleted on mismatch.
- **Install path**: Stored in `SharedConfig.mgUpdateApkPath` (cache dir); installed via `ACTION_VIEW` intent with FileProvider URI.
- **UI entry points**: `LaunchActivity` and `ProfileActivity` call `MgUpdateChecker.checkForUpdates(false)` on startup; a bottom-sheet (`MgUpdateAlertDialog`, `MgUpdateLayout`) shows changelog + download button.
- **Files**: `MgUpdateChecker.java`, `MgUpdateInfo.java`, `ui/MgUpdateAlertDialog.java`, `TMessagesProj_App/.../MgUpdateLayout.java`

## GMS/Firebase compat stubs (compat/billing/)

Upstream Telegram references Google Play Billing classes at runtime. After removing the GMS dependency (`[TF]` patch), these stubs (`BillingClient`, `BillingResult`, `ProductDetails`, etc.) prevent `NoClassDefFoundError` on app init.

**Pattern**: If upstream adds new GMS/Google library references in the future, add matching no-op stub classes here rather than re-introducing the dependency.
