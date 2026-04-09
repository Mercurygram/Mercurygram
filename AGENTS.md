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
- **Never reconstruct a commit via `git reset --mixed HEAD^` + `git add <named files>`.** Untracked entries (`??` in `git status --porcelain`) from the original commit will be silently dropped. Use `git rebase -i` with `squash`/`fixup` (preserves all content) or `git commit --amend --no-edit` after `git add -A`. After any rebase that edits commits, verify with `git diff <backup-tag> HEAD --stat` — file count and line totals must match expected delta (zero if pure reorder); any drift means content was lost.
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
5. **Test before the first stable tag.** Push the rebased branch — `beta.yml`
   publishes a pre-stable prerelease (`X.Y.Z.0.K`, see next section) instead
   of waiting for the official tag. Iterate until the build is good, then
   tag the chosen commit as `X.Y.Z.1` (stable).

---

## Prerelease channels

5-dotted tags have two modes; both ride the same `beta.yml` Release +
Debug APK set and the same in-app updater opt-in toggle. Lex compare on
the dotted integer vector orders them naturally
(`12.7.3.0.5 < 12.7.3.1 < 12.7.3.1.42 < 12.7.3.2`).

- **Pre-stable** — `X.Y.Z.0.K`. Built between an upstream rebase and the
  first `X.Y.Z.M` (M ≥ 1) stable for that upstream. The M=0 slot is the
  namespace marker — no 4-dotted `X.Y.Z.0` stable tag is ever created.
  Closes as soon as any `X.Y.Z.M` ≥ 1 stable ships.
  `MgUpdateChecker.derivePrecedingStableTag()` returns `null` for these
  installs: toggling off the opt-in is a no-op because no preceding
  stable exists yet — the next periodic check picks the first
  `X.Y.Z.M` up automatically once it ships.
- **Post-stable snapshot** — `X.Y.Z.M.K` (M ≥ 1). Continuation beta
  riding on the latest shipped stable. Toggling off the opt-in rolls
  back to that underlying `X.Y.Z.M` (same `MG_VERSION_CODE`,
  PackageInstaller treats it as a reinstall).

## Versioning

`MG_VERSION_NAME` and `MG_VERSION_CODE` are not stored in
`gradle.properties` — `gradle/mg-version.gradle` derives both from the
GitHub release tag (`MG_BUILD_TAG`) at build time:

- `MG_VERSION_NAME = <tag verbatim>` (4-dotted *or* 5-dotted). Android
  only uses `versionCode` for upgrade/downgrade, so the manifest
  carrying the real tag is harmless and gives sideloaded pre-stable
  installs a real `PackageInfo.versionName` to read.
- `MG_VERSION_CODE = APP_VERSION_CODE * 100 + M`, where `M` is the 4th
  tag component verbatim (4-dotted *or* 5-dotted). Bounded to `0..99`;
  bigger M fails the build.

Per-ABI versionCode is still `MG_VERSION_CODE * 10 + abiVersionCode`
(see TMessagesProj/AGENTS.md). Tag-to-versionCode mapping:

- Pre-stable `X.Y.Z.0.K` → `M = 0` → versionCode strictly below any
  `X.Y.Z.M` stable (M ≥ 1). The pre-stable → first stable transition
  is therefore a normal OTA upgrade.
- Post-stable snapshot `X.Y.Z.M.K` and stable `X.Y.Z.M` (M ≥ 1) share
  one versionCode → `PackageInstaller` treats any swap among them as a
  reinstall (clean roll-forward and roll-back within the M family).
- Pre-stable within-namespace `X.Y.Z.0.K1 ↔ X.Y.Z.0.K2` is also a
  reinstall (shared versionCode).

`MG_BUILD_TAG` resolution priority: `-PMG_BUILD_TAG=<tag>` > the
`MG_BUILD_TAG=` line in `gradle.properties` (added by the F-Droid
recipe `prebuild` step in `.github/scripts/fdroid_sync.py`) > the
`MG_BUILD_TAG=` line in `local.properties` (developer-machine
override, gitignored) > build fails. CI passes the tag via
`-PMG_BUILD_TAG`; `release.sh` requires it as `$1`;
`scripts/check-reproducibility.sh` propagates `MG_BUILD_TAG_OVERRIDE`
into the recipe rewrite (auto-derives from `ref` when tag-shaped).

### Beta release changelog

`beta.yml` runs `.github/scripts/changelog-ai.sh` against the diff from the
latest 4-dotted stable in the current `APP_VERSION_NAME` cycle (falling back
to the global newest stable) to `HEAD`, posts it to Kilo `kilo-auto/free`,
and uses the result as the GitHub pre-release body — which `MgUpdateChecker`
surfaces verbatim in the OTA dialog. Same script + endpoint as `release.yml`
for stable tags, so prose style stays consistent across channels. On Kilo
outage / non-JSON response, the script silently falls back to a grep-based
bullet list of new MG classes/flags/strings — the release still publishes.

### Pre-release opt-in on the stable channel

Stable installs (`it.belloworld.mercurygram`) can opt into 5-dotted
prerelease updates (the per-push Release-flavor APKs that `beta.yml`
publishes) via the **Settings → Mercurygram → Updates → Accept
pre-release updates** toggle. Enabling shows a confirmation dialog with
a quality warning; the option can only be turned off again once the user
is back on a 4-dotted stable release. F-Droid / IzzyOnDroid distribution
is unaffected — those channels never see 5-dotted tags.

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

## Reproducible builds

Mercurygram ships on F-Droid; its builds must be **bit-for-bit reproducible**.
Reproducibility has broken before — treat anything touching `build.gradle`,
native code (`TMessagesProj/jni/`), the NDK/build-tools pin, or bundled binary
assets (e.g. `assets/emoji/*.png`) as reproducibility-sensitive.

- **Commit pre-processed assets, never process them at build time.** If an asset
  is optimized (e.g. `pngquant`/`oxipng` on emoji), run the tool **offline once**
  and commit the result. The F-Droid server must build the committed bytes — no
  image/codec tooling may run during the gradle build, or output drifts per host.
- **Native libs are the main reproducibility risk** (compiler/path/timestamp
  embedding), independent of MG/TF changes — the check below catches it too.
- Verify with **`scripts/check-reproducibility.sh`**. It runs the **real
  fdroidserver** against the **real fdroiddata recipe** in the official F-Droid
  container (podman) — no hand-rolled build steps:
  - `scripts/check-reproducibility.sh` — default `determinism` mode: builds the
    working tree (incl. uncommitted changes) **twice** and diffoscopes the two
    unsigned APKs. Run this before tagging and on any reproducibility-sensitive
    change/PR. PASS = byte-identical.
  - `scripts/check-reproducibility.sh verify <versionCode>` — authoritative
    check: `fdroid build` + `fdroid verify` against the APK published on
    `f-droid.org`. Use for an already-shipped tag (fails for unreleased local
    changes by design). Mercurygram is signed **v2/v3-only**, so this needs
    fdroidserver with [MR !1825](https://gitlab.com/fdroid/fdroidserver/-/merge_requests/1825)
    (developer-signature graft for v2-only sigdirs); the script pins
    fdroidserver to that branch via `FDROIDSERVER_PIP` until it merges
    upstream — override that env var once it does.
  - `scripts/check-reproducibility.sh verify <github-apk-url> [ref]` — same
    flow, but the comparison APK is downloaded from a GitHub Release URL
    (Release-flavor beta APKs, per-push). The recipe's last Builds entry is
    rewritten to the supplied `ref` + the APK's versionCode, then
    `fdroid build --on-server --test` rebuilds the source unsigned and both
    APKs are unzipped (excluding `META-INF/*`) and diffoscoped: the v2/v3
    APK Signing Block sits outside the zip entries (between central
    directory and EOCD), so unzip never sees it and signature bytes are
    correctly excluded. Same coverage as `fdroid verify` without the
    apksigcopier metadata-strictness fight. Debug-flavor beta APKs are NOT
    reproducible by design (`BuildVars.MG_BUILD_TIMESTAMP = System.currentTimeMillis()`).
- CI: `.github/workflows/reproducible.yml` (manual `workflow_dispatch` only —
  the build is heavy; not run on every push).

---

## CI conventions

- **Pin every GitHub Action by full 40-char commit SHA** with a trailing
  `# vX.Y.Z` comment for human readability — e.g.
  `actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6.0.2`.
  Mutable references (`@v3`, `@main`, branch names) are forbidden in
  `.github/workflows/` and `.github/actions/` — they let a compromised
  upstream re-point a tag at malicious code. Local reusable workflows
  (`uses: ./.github/...`) are exempt.
- When bumping an action, fetch the SHA the release tag points at
  (`gh api repos/<org>/<repo>/git/refs/tags/<tag> -q .object.sha`,
  dereferencing annotated tags if needed) and update **both** the SHA and
  the trailing version comment in the same commit.

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
| Reproducibility check | `scripts/check-reproducibility.sh`, `.github/workflows/reproducible.yml` |
| Fastlane metadata | `metadata/` |
