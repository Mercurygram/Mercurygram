# TMessagesProj — Build & Release

## Prerequisites

- JDK 17 (`JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk`)
- Android SDK with the NDK + build-tools versions pinned in `TMessagesProj/build.gradle` (`ndkVersion` / `buildToolsVersion`)
- `ninja-build`, `meson`, `pip` (for native deps)
- `gradle.properties` with `APP_ID`, `APP_HASH`, signing config
- `API_KEYS` file in project root with `APP_ID` and `APP_HASH`

## Building the APK

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew assembleAfatRelease   # or assembleAfatDebug
```

`buildNativeLibs` builds native libs (libvpx, dav1d, ffmpeg, BoringSSL, tde2e) on first run and skips on subsequent runs (sentinel: `jni/tde2e/build/arm64-v8a/libtde2e.a`). Delete sentinel to force rebuild.

`afterEvaluate` blocks in both build files wire native build ordering:
- `configureCMake*`/`buildCMake*`/`externalNativeBuild*` tasks use `dependsOn` the matching per-ABI task (e.g. `buildNativeLibsArm64` for `arm64-v8a`).
- `merge*JniLibFolders` tasks use `mustRunAfter` (not `dependsOn`) each per-ABI task. `jniLibs.srcDirs` points to `jni/` but contributes no `.so` files, so ordering only prevents a race condition. Using `mustRunAfter` avoids scheduling unneeded ABI builds.

Per-ABI F-Droid optimization: `detectSingleFdAbi()` inspects `gradle.startParameter.taskNames` at configuration time. If all requested tasks target one F-Droid per-ABI flavor, it sets `ndk.abiFilters` on the library's `defaultConfig` to that single ABI. This limits AGP's CMake task creation to one ABI, meaning only that ABI's `buildNativeLibs*` task is scheduled. Fat builds and local development are unaffected (returns `null` → no filter set).

## F-Droid flavors

| Flavor | ABI | abiVersionCode | Gradle task |
|---|---|---|---|
| `afatFdX86` | x86 | 3 | `assembleAfatFdX86Release` |
| `afatFdX86_64` | x86_64 | 4 | `assembleAfatFdX86_64Release` |
| `afatFdArm32` | armeabi-v7a | 7 | `assembleAfatFdArm32Release` |
| `afatFdArm64` | arm64-v8a | 8 | `assembleAfatFdArm64Release` |

Version codes: `MG_VERSION_CODE * 10 + abiVersionCode`. `MG_VERSION_CODE`
and `MG_VERSION_NAME` are derived from `MG_BUILD_TAG` at configure time
by `gradle/mg-version.gradle`; see [`AGENTS.md` → "Versioning"](../AGENTS.md#versioning)
for the exact rules.
Signed outputs: `Mercurygram-<tag>-<abi>.apk` (one per ABI flavor). `release.sh` also builds a fat `Mercurygram-<tag>-release.apk` (local/manual use only; CI releases do not include it).

## Beta channel

`.github/workflows/beta.yml` builds **both `debug` and `release` build types for the four `afatFd*` flavors** on every push to `Mercurygram` (8 APKs per push) and publishes them as a 5-dotted GitHub pre-release (e.g. `12.6.4.4.42`). Tag-shape rules and pre-stable vs post-stable selection: see [`AGENTS.md` → "Prerelease channels"](../AGENTS.md#prerelease-channels).

APK set per release:

| variant | filename | package | reproducible? |
|---|---|---|---|
| Release | `Mercurygram-<tag>-<abi>.apk`        | `it.belloworld.mercurygram` (stable)       | yes (via `scripts/check-reproducibility.sh verify <github-apk-url>`) |
| Debug   | `Mercurygram-debug-<tag>-<abi>.apk`  | `it.belloworld.mercurygram.beta` (side-by-side install) | no — `BuildVars.MG_BUILD_TIMESTAMP = System.currentTimeMillis()` for the debug buildType (see `TMessagesProj/build.gradle:170`) |

versionName/versionCode handling (`TMessagesProj_App/build.gradle:274`):
- **Debug**: `MG_BETA_VERSION_NAME` / `MG_BETA_VERSION_CODE` overrides apply. versionCode = `${{ github.run_number }}` shared across the 4 ABIs (OTA picks asset by filename). Do not rename or delete the workflow — it would reset `run_number` and cause versionCode regression.
- **Release**: overrides skipped. versionCode = `MG_VERSION_CODE * 10 + abiVersionCode` (same value as the matching stable release for this `MG_VERSION_CODE` — see [`AGENTS.md` → "Versioning"](../AGENTS.md#versioning) for the `MG_VERSION_CODE = APP_VERSION_CODE * 100 + M_eff` rule). A user installing the Release APK gets a stable-package APK that won't OTA-upgrade until the next stable bump. `versionName` is the tag verbatim (4-dotted or 5-dotted), so `PackageInfo.versionName` already disambiguates pre-stable sideloads — no compile-time tag baking needed.

`MG_BUILD_TAG` is consumed by `gradle/mg-version.gradle` (applied by `TMessagesProj_App/build.gradle`) to derive `MG_VERSION_NAME` / `MG_VERSION_CODE` and is **required** — the build fails without it. Resolution priority: `-PMG_BUILD_TAG=<tag>` > `MG_BUILD_TAG` in `gradle.properties` (added by the F-Droid recipe `prebuild` step from `.github/scripts/fdroid_sync.py`) > `MG_BUILD_TAG` in `local.properties` (developer-machine override, gitignored) > error. CI passes via the `build-mg` composite action's `build-tag:` input (`beta.yml` uses `needs.version.outputs.name`; `release.yml` uses `github.ref_name`). `scripts/check-reproducibility.sh` propagates the tag through its recipe rewriter via `MG_BUILD_TAG_OVERRIDE` (env var or auto-derived from a tag-shaped `ref`).

`MgUpdateChecker` selects the GitHub endpoint based on the runtime package name and the user's opt-in toggle:
- `.beta` suffix → `GITHUB_LIST_URL = /releases` (includes prereleases), filename pattern `Mercurygram-debug-<tag>-<abi>.apk`
- otherwise + `SharedConfig.acceptPreReleaseUpdates == true` (or `PackageInfo.versionName` is already a 5-dotted tag) → `GITHUB_LIST_URL`, filename pattern `Mercurygram-<tag>-<abi>.apk`
- otherwise → `GITHUB_LATEST_URL = /releases/latest` (GitHub server-side filters out prereleases), filename pattern `Mercurygram-<tag>-<abi>.apk`

Three independent gates keep stable installs from pulling a beta prerelease by default: `beta.yml` creates the GH release with `prerelease: true` + `make_latest: false`, `MgUpdateChecker` uses `/releases/latest` for stable, and the opt-in toggle defaults to off (UI in Settings → Mercurygram → Updates; warns on enable).

`release.yml` excludes 5-part tags (`!*.*.*.*.*`) so beta tag pushes never fire the stable release workflow.

A 3rd job in `beta.yml` (`verify`, `needs: release`, `continue-on-error: true`) re-runs `scripts/check-reproducibility.sh verify <published-arm64-release-apk-url> <sha>` for every push, flagging reproducibility regressions in CI without blocking the beta release. arm64 only — the source is identical across ABIs and per-ABI delta is just the `buildNativeLibs<Abi>` task. Flip to hard-fail (drop `continue-on-error`) once consistently green.

## Release script

```bash
./release.sh 12.5.1.3                     # build + create/replace GitHub release tag
RELEASE_REPLACE=1 ./release.sh 12.5.1.3   # skip confirmation when replacing an existing release
```

Tag must be 4-dotted (`X.Y.Z.M`); 5-dotted snapshots are published by `beta.yml`.

Signing credentials from `.env` (copy `.env.example`):
```
KS=/path/to/your/keystore.jks
KS_PASS=...
KS_KEY_ALIAS=...
KS_KEY_PASS=...
```

**Sign via Gradle, not `apksigner`** — `apksigner` from build-tools 35.0.0 rewrites ZIP extra fields, breaking F-Droid reproducible build verification. Gradle signing preserves zero-padding that matches the fdroid-built APK.

The keystore path is configurable via `RELEASE_STORE_FILE` Gradle property (defaults to `TMessagesProj/config/release.keystore` — a committed dummy). `release.sh` passes `-PRELEASE_STORE_FILE="$KS"`.

## gradle.properties keys (not committed)

```
APP_ID=...
APP_HASH=...
ADDITIONAL_BUILD_NUMBER=...
```

Default signing credentials (`android`/`androidkey`) are dev-only. Release builds override via `-P` flags.

## local.properties keys (optional)

| Key | Effect |
|---|---|
| `QUIET_NATIVE_BUILD=true` | Suppresses native build progress/warnings. Auto-set on F-Droid via metadata `prebuild`. Local: `echo QUIET_NATIVE_BUILD=true >> local.properties` (repo root — `getProps()` reads `rootProject.file('local.properties')`) |

## Reproducible builds: JDK version

F-Droid production server uses JDK 17 (`openjdk-17-jdk-headless`). DEX files, baseline profiles, and VectorDrawable rasterization are JDK-version-sensitive. For local testing with the fdroid Docker container (Debian 13 Trixie, no JDK 17 in repos):

```bash
echo "deb https://deb.debian.org/debian bookworm main" > /etc/apt/sources.list.d/bookworm.list
apt-get update && apt-get install -y -t bookworm openjdk-17-jdk-headless
update-java-alternatives -s java-1.17.0-openjdk-amd64
```
