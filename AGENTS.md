# Mercurygram — Development Guide

Mercurygram is a FOSS Android Telegram client (package `it.belloworld.mercurygram`), built by rebasing two patch sets on top of upstream Telegram:

- **[TF]** patches — de-googling (from Telegram-FOSS project, forward-ported manually)
- **[MG]** patches — Mercurygram features

Upstream: https://github.com/DrKLO/Telegram.git (remote `upstream`)

---

## Rebase Workflow

Mercurygram is maintained as a **rebase on top of upstream/master**, not a merge fork.

- **Keep the commit count low.** Every commit must survive future rebases. Fewer, well-scoped commits = fewer conflicts.
- **When fixing a bug or adjusting an existing feature, amend the related commit** (`git commit --amend` or rebase -i fixup) rather than adding a new "fix" commit.
- **Keep documentation up to date.** When a change affects build instructions, configuration, architecture, or workflow, update `AGENTS.md`, `README`, or other relevant docs in the same commit.
- **Never squash [TF] and [MG] commits together.** They serve different purposes and may need to be separated in future rebases.

### Commit naming convention

```
[TF] short description of FOSS patch
[MG] short description of Mercurygram feature
```

### Rebasing to a new upstream version

1. `git fetch upstream`
2. `git rebase upstream/master`
3. Resolve conflicts — upstream heavily modifies the same large files (ChatActivity, ProfileActivity, etc.)
4. Re-verify that all features still work

---

## Code Isolation Principle

**Isolate MG/TF changes from upstream code as much as possible.** This reduces rebase conflicts.

### Preferred patterns

- **New file over modifying a large upstream file.** E.g., `MessageDetailsActivity.java` is a new file; only a tiny hook added to `ChatActivity.java`.
- **Add a constant or static method in a small helper class** rather than inlining logic into a 40,000-line upstream file.
- **MG-specific SharedConfig fields** use the `mg_` prefix in SharedPreferences, declared in a dedicated block (~line 238 of `SharedConfig.java`).
- **New UI screens** go in `it.belloworld.mercurygram.ui` package.
- **Helper classes** (e.g. `MonetHelper`) go in `tw.nekomimi.nekogram.helpers` (kept for historical reasons).

### Anti-patterns to avoid

- Inline logic changes in the middle of methods in large upstream files.
- Adding new imports to already-heavily-modified files (each import is a potential conflict line).
- Feature flags that touch many files — prefer a single `SharedConfig` boolean read at the call site.

---

## [TF] De-googling architecture

Key patterns from the Telegram-FOSS patch set:

- **MapLibre GL Native** replaces Google Maps for location sharing. Provider: `MapLibreMapsProvider.java` in `org.telegram.messenger`. No Google Maps API key needed.
- **GMS/Firebase/MLKit/Cast removed**: `it.belloworld.mercurygram.compat.billing.*` are no-op stubs that prevent `NoClassDefFoundError` at app init. If upstream adds new GMS references, add a matching stub class here rather than re-introducing the dependency.
- **API keys externalized**: `APP_ID`/`APP_HASH` come from `gradle.properties` and an `API_KEYS` file — not hardcoded. `BuildVars` reads them at compile time.
- **Noto emoji** replaces Apple emoji assets.

---

## Known Limitations

- **Passkeys**: Disabled via `BuildVars.SUPPORTS_PASSKEYS = false`. Telegram servers verify APK signature against the official app, which fails for unofficial forks.

---

## Key File Paths

| Purpose | Path |
|---|---|
| MG feature flags | `TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java` |
| UnifiedPush service | `TMessagesProj/src/main/java/org/telegram/messenger/UnifiedPushReceiver.java` |
| WebPush decryptor | `TMessagesProj/src/main/java/it/belloworld/mercurygram/WebPushDecryptor.java` |
| Message Details screen | `TMessagesProj/src/main/java/it/belloworld/mercurygram/ui/MessageDetailsActivity.java` |
| Monet helper | `TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/MonetHelper.java` |
| Native build scripts | `TMessagesProj/jni/build_*.sh`, `TMessagesProj/jni/patch_*.sh` |
| Fastlane metadata | `metadata/` |
