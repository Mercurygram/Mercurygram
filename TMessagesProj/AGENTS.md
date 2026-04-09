# TMessagesProj — Build & Release

## Prerequisites

- JDK 17 (`JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk`)
- Android SDK with NDK 21.4.7075529 and build-tools 35.0.0
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

Version codes: `MG_VERSION_CODE * 10 + abiVersionCode`.
Signed outputs: `Mercurygram-<MG_VERSION_NAME>-<abi>.apk` (one per ABI flavor). `release.sh` also builds a fat `Mercurygram-<MG_VERSION_NAME>-release.apk` (local/manual use only; CI releases do not include it).

## Release script

```bash
./release.sh              # build only
./release.sh 12.5.1.3     # build + create/replace GitHub release tag
```

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
