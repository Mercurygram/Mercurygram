#!/usr/bin/env bash
# run-tests.sh — run :TMessagesProj_AppTests:api30AfatDebugAndroidTest inside a
# podman ubuntu:24.04 container. Mirrors .github/workflows/tests.yml: same
# apt set, JDK 17 temurin, sdkmanager components, gradle invocation, and gate
# (KNOWN_FAILURES=0).
#
# Why: AGP's Gradle Managed Devices emulator boot/snapshot is unreliable on
# host Fedora. The container provides a known-good ubuntu environment matching
# CI exactly, with /dev/kvm passed through for hardware-accelerated emulation.
#
# Usage:
#   scripts/run-tests.sh                                # default MG_BUILD_TAG=<APP_VERSION_NAME>.<newest stable M>.99
#   scripts/run-tests.sh -PMG_BUILD_TAG=12.9.2.1.99
#   MG_BUILD_TAG=12.9.2.1.99 scripts/run-tests.sh
#   MG_TESTS_REBUILD=1 scripts/run-tests.sh             # force image rebuild
#   KNOWN_FAILURES=1 scripts/run-tests.sh               # tolerate one failure
#
# Persistent caches under ~/.cache/mg-tests:
#   ./avd     AVD + snapshot (warm second run ~30-60 s vs cold ~3-5 min)
#   ./gradle  GRADLE_USER_HOME
# JNI native build dirs live under the repo and are persisted by the workspace
# bind mount automatically.
#
# Cleanup: rm -rf ~/.cache/mg-tests
#
# Requires: podman, /dev/kvm (mode 0666), ~3 GB free on $HOME for image +
# system-image + gradle cache, ~5 GB extra on first run for SDK downloads.
#
# SELinux note: on Fedora/CachyOS the targeted policy denies `execheap` on
# `container_t`/`spc_t`, which the emulator's SwiftShader JIT needs —
# RenderThread SIGSEGVs in api30Setup. Neither `--security-opt label=disable`
# nor `--privileged` lifts it. Run `sudo setenforce 0` before invoking this
# script (reverts on reboot), or install a persistent policy module:
#   ausearch -m AVC -c RenderThread | audit2allow -M mg-tests-execheap
#   sudo semodule -i mg-tests-execheap.pp
# CI (ubuntu-24.04 + AppArmor) is unaffected.

set -euo pipefail

IMG="${MG_TESTS_IMAGE:-mg-tests-runner:local}"
KNOWN_FAILURES="${KNOWN_FAILURES:-0}"

repo_root=$(git rev-parse --show-toplevel)
# Worktree support: when scripts/run-tests.sh runs from a git worktree
# (e.g. ../worktrees/foo), .git is a file pointing at an absolute
# path under the main repo's $GIT_DIR. The container can't resolve that
# unless we bind-mount the common gitdir at the same absolute path.
# git_common_dir is empty / matches "$repo_root/.git" for a non-worktree
# checkout — the extra mount is added only when needed.
git_common_dir=$(git rev-parse --git-common-dir 2>/dev/null || true)
if [ -n "$git_common_dir" ]; then
    # --git-common-dir can return a relative path; normalise to absolute.
    case "$git_common_dir" in
        /*) : ;;
        *)  git_common_dir=$(cd "$git_common_dir" && pwd) ;;
    esac
fi

NDK_VERSION=$(grep -oE 'ndkVersion[[:space:]]+"[0-9.]+"' \
    "$repo_root/TMessagesProj/build.gradle" | grep -oE '[0-9.]+')
BUILD_TOOLS_VERSION=$(grep -oE "buildToolsVersion[[:space:]]+'[0-9.]+'" \
    "$repo_root/TMessagesProj/build.gradle" | grep -oE '[0-9.]+')

log() { echo "=== $(date -Is) :: $* ===" >&2; }
die() { echo "error: $*" >&2; exit 2; }

[ -n "$NDK_VERSION" ] && [ -n "$BUILD_TOOLS_VERSION" ] \
    || die "could not parse ndkVersion / buildToolsVersion from TMessagesProj/build.gradle"

# MG_BUILD_TAG resolution: env -> -PMG_BUILD_TAG=<tag> arg -> CI default.
MG_BUILD_TAG_VALUE="${MG_BUILD_TAG:-}"
for arg in "$@"; do
    case "$arg" in
        -PMG_BUILD_TAG=*) MG_BUILD_TAG_VALUE="${arg#-PMG_BUILD_TAG=}" ;;
    esac
done
# Default: the current upstream version from gradle.properties, the newest
# stable M already tagged for that version, and 99 as the throwaway K, so the
# placeholder tracks rebases automatically.
#
# The 99 must sit in K, never in M: gradle/mg-version.gradle derives
# MG_VERSION_CODE = APP_VERSION_CODE * 100 + M, so an M of 99 outranks every
# release of the cycle, and a device that installed such a build can no longer
# be moved back to a stable or prerelease APK (PackageInstaller refuses the
# downgrade). Reusing the stable's own M keeps the versionCode identical, so
# swapping in either direction is a plain reinstall.
if [ -z "$MG_BUILD_TAG_VALUE" ]; then
    app_version_name=$(sed -n 's/^APP_VERSION_NAME=//p' "$repo_root/gradle.properties")
    [ -n "$app_version_name" ] || die "APP_VERSION_NAME not found in gradle.properties"
    # Newest 4-dotted stable tag of this cycle, 0 before the first one ships
    # (the pre-stable namespace, which ranks below every stable by design).
    stable_m=$(git -C "$repo_root" tag --list "$app_version_name.*" 2>/dev/null \
        | awk -F. -v v="$app_version_name" \
              'NF == 4 && $1"."$2"."$3 == v && $4 ~ /^[0-9]+$/ { print $4 }' \
        | sort -n | tail -1)
    MG_BUILD_TAG_VALUE="$app_version_name.${stable_m:-0}.99"
fi

work_base="${MG_TESTS_WORK:-$HOME/.cache/mg-tests}"
mkdir -p "$work_base"

# Block concurrent invocations — both runs would touch the same AVD snapshot
# and corrupt it. Non-blocking flock: fail fast instead of stacking up runs.
exec 9>"$work_base/.lock"
flock -n 9 || die "another scripts/run-tests.sh is running ($work_base/.lock held)"

work=$(mktemp -d "$work_base/run.XXXXXX")
trap '[ -n "${MG_TESTS_KEEP:-}" ] && echo "work kept: $work" || rm -rf "$work"' EXIT

command -v podman >/dev/null || die "podman not found"

# /dev/kvm passthrough only works if the host device exists and the container
# user can open it. Mode 0666 is the Fedora default once kvm group setup is
# done; warn loudly if it's been locked down so the failure mode is obvious.
[ -c /dev/kvm ] || die "/dev/kvm missing — install qemu-kvm and reboot"
kvm_mode=$(stat -c '%a' /dev/kvm)
case "$kvm_mode" in
    666|0666) : ;;
    *) log "warn: /dev/kvm mode=$kvm_mode (expected 0666) — container may not be able to open it; consider chmod or --group-add" ;;
esac

# SELinux enforcing on Fedora/CachyOS denies execheap for container_t and
# spc_t, which crashes the emulator's SwiftShader JIT in RenderThread.
# Surface a clear hint instead of letting the user chase the empty
# "Error message from emulator process = []" trail from AGP.
if command -v getenforce >/dev/null 2>&1 && [ "$(getenforce 2>/dev/null)" = "Enforcing" ]; then
    log "warn: SELinux is Enforcing — emulator's SwiftShader JIT will SIGSEGV on execheap denial. Run 'sudo setenforce 0' or install a custom policy module (see script header) before retrying if api30Setup fails."
fi

# ---------------------------------------------------------------------------
# API_KEYS handling. TMessagesProj/build.gradle does an eager FileInputStream
# at configure time; the file must exist or the test build fails before any
# emulator interaction. The test APK never ships and the instrumentation suite
# doesn't authenticate, so a dummy is safe when no real keys are available.
# ---------------------------------------------------------------------------
ensure_api_keys() {
    if [ -n "${APP_ID:-}" ] && [ -n "${APP_HASH:-}" ]; then
        printf 'APP_ID = %s\nAPP_HASH = %s\n' "$APP_ID" "$APP_HASH" \
            > "$repo_root/API_KEYS"
        log "API_KEYS written from APP_ID/APP_HASH env"
        return
    fi
    if [ -f "$repo_root/local.properties" ]; then
        local lp_id lp_hash
        lp_id=$(grep -oE '^APP_ID=[^[:space:]]+' "$repo_root/local.properties" | cut -d= -f2 || true)
        lp_hash=$(grep -oE '^APP_HASH=[^[:space:]]+' "$repo_root/local.properties" | cut -d= -f2 || true)
        if [ -n "$lp_id" ] && [ -n "$lp_hash" ]; then
            printf 'APP_ID = %s\nAPP_HASH = %s\n' "$lp_id" "$lp_hash" \
                > "$repo_root/API_KEYS"
            log "API_KEYS written from local.properties"
            return
        fi
    fi
    if [ -f "$repo_root/API_KEYS" ] && [ -s "$repo_root/API_KEYS" ]; then
        log "API_KEYS already present"
        return
    fi
    printf 'APP_ID = 0\nAPP_HASH = 0\n' > "$repo_root/API_KEYS"
    log "API_KEYS dummy written (no APP_ID/APP_HASH in env or local.properties)"
}

# ---------------------------------------------------------------------------
# Container image. Layered to maximise rebuild reuse: apt + JDK rarely change;
# the sdkmanager step only re-runs when NDK_VERSION / BUILD_TOOLS_VERSION
# (forwarded as build args) change in TMessagesProj/build.gradle.
# ---------------------------------------------------------------------------
build_image() {
    if [ -z "${MG_TESTS_REBUILD:-}" ] && podman image exists "$IMG"; then
        log "image $IMG already present (set MG_TESTS_REBUILD=1 to force rebuild)"
        return
    fi
    log "build container image $IMG (FROM ubuntu:24.04, NDK $NDK_VERSION, BT $BUILD_TOOLS_VERSION)"
    mkdir -p "$work/ctx"
    cat > "$work/ctx/Containerfile" <<'EOF'
FROM docker.io/library/ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive
ENV LANG=C.UTF-8
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64

# Base tooling + emulator runtime libs. The QEMU launched by AGP's emulator
# binary dlopens libpulse / libgl / libnss even with -no-window, and missing
# any of them aborts the snapshot step with a cryptic "cannot open library".
# `patch` is required by patch_boringssl.sh / patch_td.sh. ubuntu:24.04
# doesn't ship it by default, and those scripts swallow errors via `|| true`,
# so a missing tool silently leaves the AES-IGE patch unapplied and breaks
# the BoringSSL build.
# autoconf / automake / libtool are required by build_libevent.sh and
# build_tor.sh which regen `configure` via ./autogen.sh.
# g++ is required by the tde2e host build (CMake compiles the TL source
# generator with the host C++ toolchain). CI's ubuntu-24.04 runner image
# ships build-essential preinstalled — the plain ubuntu:24.04 base does
# not, so add g++ explicitly here.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        ca-certificates curl unzip wget git gnupg \
        gperf meson libuv1-dev nasm cmake make pkg-config g++ \
        patch autoconf automake libtool \
        python3 python3-pip \
        libpulse0 libnss3 libxcomposite1 libxcursor1 libxi6 libxtst6 \
        libgl1 libglu1-mesa libgl1-mesa-dri libdrm2 libxkbcommon0 \
        libxkbfile1 libice6 libsm6 \
        libasound2t64 libfontconfig1 libfreetype6 \
        procps file \
 && rm -rf /var/lib/apt/lists/*

# Eclipse Temurin 17 JDK — same distribution CI's actions/setup-java pulls in.
RUN install -m 0755 -d /etc/apt/keyrings \
 && wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
        | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg \
 && chmod a+r /etc/apt/keyrings/adoptium.gpg \
 && echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb noble main" \
        > /etc/apt/sources.list.d/adoptium.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends temurin-17-jdk \
 && rm -rf /var/lib/apt/lists/*

ENV PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH

# Android cmdline-tools — same release as scripts/check-reproducibility.sh.
RUN mkdir -p $ANDROID_HOME/cmdline-tools \
 && curl -fsSL -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
 && unzip -q /tmp/clt.zip -d $ANDROID_HOME/cmdline-tools \
 && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/clt.zip

ARG NDK_VERSION
ARG BUILD_TOOLS_VERSION

# License accept is split from --install on purpose: a chained `|| true` at
# the tail of a single RUN shell line swallows `sdkmanager --install`
# failures via shell precedence ((A || true) && B && (C || true) -> overall
# `|| true`). Separate RUNs keep failure points loud; install errors abort
# the build instead of producing an SDK-less image that fails at chmod.
RUN yes | sdkmanager --licenses >/dev/null 2>&1; true
RUN sdkmanager --install \
        "platform-tools" \
        "platforms;android-35" \
        "build-tools;${BUILD_TOOLS_VERSION}" \
        "ndk;${NDK_VERSION}" \
        "cmake;3.22.1" \
        "emulator" \
        "system-images;android-30;default;x86_64"
RUN yes | sdkmanager --licenses >/dev/null 2>&1; true

# Post-condition: fail the build here, not at chmod, if any expected SDK
# subtree didn't materialise. AGP's GMD with `systemImageSource = "aosp"`
# resolves to the `default` sdkmanager package (the on-disk dir name "aosp"
# in older docs is legacy; current sdkmanager id is `default`).
RUN test -d $ANDROID_HOME/platform-tools \
 && test -d $ANDROID_HOME/emulator \
 && test -d $ANDROID_HOME/system-images/android-30/default/x86_64 \
 && test -d $ANDROID_HOME/ndk/${NDK_VERSION} \
 && test -d $ANDROID_HOME/build-tools/${BUILD_TOOLS_VERSION}

# AGP 8.13's GMD invokes the launcher with `-no-window -gpu auto-no-window`,
# but emulator 35.x+ rejects `auto-no-window` as a `-gpu` value (valid set
# now: auto|host|software|lavapipe|swiftshader|swangle). `-no-window` is
# already passed separately, so this shim only rewrites the `-gpu` value
# (emitting another `-no-window` would duplicate the flag and confuse the
# launcher's setup). The real binary discovers its install dir via
# dirname(/proc/self/exe), so renaming is transparent.
RUN mv $ANDROID_HOME/emulator/emulator $ANDROID_HOME/emulator/emulator.real \
 && printf '%s\n' \
        '#!/bin/bash' \
        'args=()' \
        'while [ $# -gt 0 ]; do' \
        '    if [ "$1" = "-gpu" ] && [ "${2:-}" = "auto-no-window" ]; then' \
        '        args+=(-gpu swiftshader)' \
        '        shift 2' \
        '    else' \
        '        args+=("$1")' \
        '        shift' \
        '    fi' \
        'done' \
        'exec /opt/android-sdk/emulator/emulator.real "${args[@]}"' \
        > $ANDROID_HOME/emulator/emulator \
 && chmod +x $ANDROID_HOME/emulator/emulator

# Make the whole SDK tree world-rwx: keep-id maps host uid -> in-namespace
# uid, which is NOT root inside the container, so the emulator can't create
# lock files and AGP can't auto-install missing components (cmake, ndk
# side-by-side, etc.) into /opt/android-sdk/<new-dir> without write
# permission on $ANDROID_HOME itself.
RUN chmod -R go+rwX $ANDROID_HOME

# Runtime HOME for the in-container user. Bind mounts attach .android and
# .gradle subtrees from the host cache dir; /home/builder itself stays as a
# writable workspace for any incidental tooling that writes to $HOME.
RUN mkdir -p /home/builder && chmod 0777 /home/builder

WORKDIR /work
EOF
    podman build \
        --network=host \
        --build-arg "NDK_VERSION=$NDK_VERSION" \
        --build-arg "BUILD_TOOLS_VERSION=$BUILD_TOOLS_VERSION" \
        -t "$IMG" "$work/ctx"
}

# ---------------------------------------------------------------------------
# Gradle run. --userns=keep-id maps the in-container root to the host UID so
# repo bind-mount writes (gradle build/, JUnit XMLs) end up host-owned. Using
# --user $(id -u):$(id -g) instead fails under rootless podman with
# `crun: setgroups: Invalid argument` because the host UID isn't a valid UID
# inside the user namespace — only the subuid-mapped range is. --device
# /dev/kvm hands KVM through to the emulator. Workspace bind mount carries:
#   - TMessagesProj/jni/{boringssl,td}/build, jni/ffmpeg/<abi>             native cache
#   - TMessagesProj/jni/td/td/generate/auto, td/tdutils/generate/auto      tdlib generated
#   - TMessagesProj_AppTests/build/outputs/androidTest-results/            gate input
# AVD + gradle caches are bind-mounted out of the repo so they survive
# `git clean -fdx` of the workspace.
# ---------------------------------------------------------------------------
run_gradle() {
    local avd_dir="$work_base/avd"
    local gradle_dir="$work_base/gradle"
    mkdir -p "$avd_dir" "$gradle_dir"

    # Stale XMLs from a previous run would let the gate pass even when this
    # run's gradle died before the test phase (e.g. compile error).
    rm -rf "$repo_root/TMessagesProj_AppTests/build/outputs/androidTest-results"

    log "podman run gradle :TMessagesProj_AppTests:api30AfatDebugAndroidTest -PMG_BUILD_TAG=$MG_BUILD_TAG_VALUE"
    # `|| true`: the gate below is the authoritative pass/fail signal, exactly
    # like `continue-on-error: true` on .github/workflows/tests.yml's gradle
    # step. Lets us tolerate the KNOWN_FAILURES baseline while still flagging
    # any new regression.
    # Worktree support. When running from a git worktree, .git is a file
    # pointing at an absolute path under the main repo's $GIT_DIR. For git
    # inside the container to resolve that path AND avoid rewriting each
    # submodule's `core.worktree` to a /work-relative path on every
    # patchNativeSources run (which would break host-side git ops between
    # container invocations), bind-mount the worktree at the SAME absolute
    # path inside the container and use it as the workdir — no /work
    # mount, no path mismatch. For non-worktree checkouts the legacy
    # /work mount is kept so the upstream invocation stays unchanged.
    extra_mounts=()
    workdir=/work
    repo_root_in_container=/work
    if [ -n "$git_common_dir" ] && [ -d "$git_common_dir" ] \
            && [ "$git_common_dir" != "$repo_root/.git" ]; then
        extra_mounts+=(-v "$git_common_dir:$git_common_dir:z")
        extra_mounts+=(-v "$repo_root:$repo_root:z")
        workdir="$repo_root"
        repo_root_in_container="$repo_root"
    else
        extra_mounts+=(-v "$repo_root:/work:z")
    fi

    podman run --rm \
        --network=host \
        --device /dev/kvm \
        --userns=keep-id \
        --shm-size=2g \
        --security-opt label=disable \
        "${extra_mounts[@]}" \
        -v "$avd_dir:/home/builder/.android:z" \
        -v "$gradle_dir:/home/builder/.gradle:z" \
        -e HOME=/home/builder \
        -e ANDROID_HOME=/opt/android-sdk \
        -e ANDROID_SDK_ROOT=/opt/android-sdk \
        -e GRADLE_USER_HOME=/home/builder/.gradle \
        -w "$workdir" \
        "$IMG" \
        bash -euo pipefail -c "
            mkdir -p /home/builder/.android /home/builder/.gradle
            ./gradlew :TMessagesProj_AppTests:api30AfatDebugAndroidTest \\
                -PMG_BUILD_TAG='$MG_BUILD_TAG_VALUE' --no-daemon --stacktrace
        " || true
}

# ---------------------------------------------------------------------------
# Gate — verbatim port of .github/workflows/tests.yml `Gate on failure count`.
# Baseline is 0 in both: any failure is a regression. Raise it here AND in the
# workflow together if a batch of fixtures ever has to be tolerated.
# ---------------------------------------------------------------------------
gate() {
    local results_dir="$repo_root/TMessagesProj_AppTests/build/outputs/androidTest-results"
    if [ ! -d "$results_dir" ]; then
        die "No JUnit XML produced — $results_dir does not exist (gradle never reached the test phase)"
    fi
    local xmls
    mapfile -d '' xmls < <(find "$results_dir" -name 'TEST-*.xml' -print0)
    [ ${#xmls[@]} -gt 0 ] \
        || die "No JUnit XML produced under $results_dir — test run never reached the assertion phase"
    local fails errs tests total
    fails=$(grep -hoE 'failures="[0-9]+"' "${xmls[@]}" | grep -oE '[0-9]+' | paste -sd+ | bc); fails=${fails:-0}
    errs=$(grep -hoE  'errors="[0-9]+"'   "${xmls[@]}" | grep -oE '[0-9]+' | paste -sd+ | bc); errs=${errs:-0}
    tests=$(grep -hoE 'tests="[0-9]+"'    "${xmls[@]}" | grep -oE '[0-9]+' | paste -sd+ | bc); tests=${tests:-0}
    total=$((fails + errs))
    log "tests=$tests failures=$fails errors=$errs total=$total baseline=$KNOWN_FAILURES"
    [ "$tests" -gt 0 ] \
        || die "XMLs present but tests=0 — likely truncated/corrupt reports"
    if [ "$total" -gt "$KNOWN_FAILURES" ]; then
        die "Regression: $total failures > baseline $KNOWN_FAILURES"
    fi
    log "RESULT: PASS (total=$total <= $KNOWN_FAILURES)"
}

ensure_api_keys
build_image
run_gradle
gate
