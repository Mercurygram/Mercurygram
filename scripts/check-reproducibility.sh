#!/usr/bin/env bash
# check-reproducibility.sh — verify Mercurygram builds reproducibly using the
# EXACT toolchain F-Droid's app-build CI uses. No hand-rolled build steps.
#
# Mirrors fdroiddata's .gitlab-ci.yml app-build job verbatim:
#   * container image: registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie
#     ("tied to the buildserver … same provisioning as the production
#      buildserver"); the Android SDK + build env live in this image.
#   * fdroidserver itself is NOT taken from the image — fdroiddata CI overlays a
#     fdroidserver checkout on PATH/PYTHONPATH. We overlay the same way, pinned
#     to !1825 (drizzt/fdroidserver @ mercurygram-v2-only-graft) because
#     Mercurygram is signed v2/v3-only and stock fdroidserver cannot graft its
#     developer signature for `fdroid verify` until that MR merges. Once merged:
#       FDROIDSERVER_REPO=https://gitlab.com/fdroid/fdroidserver.git \
#       FDROIDSERVER_REF=master scripts/check-reproducibility.sh ...
#   * build environment variables come from /etc/profile.d/bsenv.sh, exactly
#     as the CI build job does.
# diffoscope is run OUTSIDE the build image (a separate throwaway container):
#   it is not part of the build, and the buildserver image must stay pristine.
#
# Four modes:
#
#   determinism [<ref>]            (default; ref defaults to the working tree)
#       Build the chosen source TWICE, then diffoscope the two unsigned APKs.
#       PASS iff byte-identical. Per-change / per-PR guard against the
#       nondeterminism that has bitten us before. Uncommitted *tracked* changes
#       are carried into a throwaway commit (submodule gitlinks preserved) so
#       dirty trees / feature branches are checkable before they ship. Does not
#       need !1825.
#
#   verify <versionCode>
#       Authoritative F-Droid reproducible-build check: `fdroid build` the
#       fdroiddata recipe at its pinned commit, then `fdroid verify` against
#       the binary published on https://f-droid.org/repo (developer-signature
#       graft — needs !1825). PASS iff `fdroid verify` reports a match. Use for
#       an already-shipped tag as a release regression guard (it WILL differ
#       for unreleased local changes — expected; use determinism then).
#
#   verify <github-apk-url|local-apk-path> [ref]
#       Same authoritative path, but the comparison APK comes from a GitHub
#       Release URL (Release-flavor beta APK) OR a local file path (used by
#       release.yml to gate publish on `fdroid build` matching the gradle-built
#       APK before it ships). Only Release-flavor APKs are verified —
#       Debug-flavor APKs are not the subject of F-Droid reproducibility.
#       Recipe's last Builds entry is overridden to the given ref and the
#       APK's versionCode; fdroid build --test runs as usual (outputs unsigned
#       APK), then both APKs are unzipped (excluding META-INF/*) and
#       diffoscoped: the v2/v3 APK Signing Block sits outside the zip entries
#       so unzip never sees it — every comparable byte (dex / resources /
#       native libs / manifest) gets compared, signatures don't. Same coverage
#       as fdroid verify without the apksigcopier metadata-strictness fight.
#       ref defaults to HEAD; pass the tag/sha matching the beta build.
#
#   verify-build <abi> [ref] [-o <output.apk>]
#       Build half of `verify <url>` split out so it can run in parallel with
#       a gradle build job on CI. <abi> is the Android ABI (arm64-v8a /
#       armeabi-v7a / x86 / x86_64); the script forces the recipe's last
#       Builds entry to the matching gradle flavor and computes versionCode
#       from MG_VERSION_CODE + flavor offset (no comparison APK needed).
#       Writes the unsigned APK to <output.apk> (default
#       $MG_REPRO_WORK/.../fdroid-<abi>.apk). No diffoscope. ref defaults to
#       HEAD; pass GITHUB_SHA on CI.
#
#   verify-diff <a.apk> <b.apk>
#       Diff half of `verify <url>` split out. Unzips both APKs excluding
#       META-INF/*, diffoscopes the trees. Builds only the diffoscope
#       container (no buildserver image, fast on a fresh runner). Use after
#       verify-build + a gradle build to gate a release.
#
# Usage:
#   scripts/check-reproducibility.sh                 # determinism, working tree
#   scripts/check-reproducibility.sh determinism HEAD
#   scripts/check-reproducibility.sh verify 6666048
#   scripts/check-reproducibility.sh verify \
#       https://github.com/.../releases/download/12.6.4.4.15/Mercurygram-12.6.4.4.15-arm64-v8a.apk \
#       12.6.4.4.15
#   scripts/check-reproducibility.sh verify-build arm64-v8a HEAD -o /tmp/fdroid-arm64.apk
#   scripts/check-reproducibility.sh verify-diff /tmp/gradle.apk /tmp/fdroid-arm64.apk
#
# Requires: podman, git, network. Heavy: full native build x2 (determinism)
# or x1 (verify / verify-build). verify-diff is fast (~1 min).

set -euo pipefail

APPID=it.belloworld.mercurygram
# The image fdroiddata CI builds apps in (.gitlab-ci.yml).
BUILDSERVER_IMAGE="${BUILDSERVER_IMAGE:-registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie}"
FDROIDDATA_RAW="${FDROIDDATA_RAW:-https://gitlab.com/fdroid/fdroiddata/-/raw/master}"
# fdroidserver overlay, pinned to MR !1825 until it lands upstream.
FDROIDSERVER_REPO="${FDROIDSERVER_REPO:-https://gitlab.com/drizzt/fdroidserver.git}"
FDROIDSERVER_REF="${FDROIDSERVER_REF:-mercurygram-v2-only-graft}"
IMG=mg-repro-buildserver:local        # buildserver + fdroidserver overlay
DIFF_IMG=mg-repro-diffoscope:local    # separate, build-independent

repo_root=$(git rev-parse --show-toplevel)
# Optional local fdroiddata checkout; default = sibling of this repo. Skips
# the curl fetch when present. Override with FDROIDDATA_DIR.
FDROIDDATA_DIR="${FDROIDDATA_DIR:-$(dirname "$repo_root")/fdroiddata}"
# NDK version sourced from the gradle pin — keeps this script in lockstep with
# what AGP actually invokes (same approach as fdroid_sync.py).
NDK_VERSION=$(grep -oE 'ndkVersion[[:space:]]+"[0-9.]+"' \
    "$repo_root/TMessagesProj/build.gradle" | grep -oE '[0-9.]+')
BUILD_TOOLS_VERSION=$(grep -oE "buildToolsVersion[[:space:]]+'[0-9.]+'" \
    "$repo_root/TMessagesProj/build.gradle" | grep -oE '[0-9.]+')

# log to stderr so command-substitution callers like
# `snap_sha=$(snapshot_source ...)` capture only the function's return value
# on stdout, not the diagnostic chatter.
log() { echo "=== $(date -Is) :: $* ===" >&2; }
die() { echo "error: $*" >&2; exit 2; }

[ -n "$NDK_VERSION" ] && [ -n "$BUILD_TOOLS_VERSION" ] \
    || die "could not parse ndkVersion / buildToolsVersion from TMessagesProj/build.gradle"

# MG_BUILD_TAG override. Load-bearing: gradle/mg-version.gradle requires
# MG_BUILD_TAG to derive MG_VERSION_NAME / MG_VERSION_CODE, and the F-Droid
# container build has no other source — recipe rewrites add a prebuild step
# that appends MG_BUILD_TAG=<value> to gradle.properties. Used by beta.yml
# verify-build to pass ${{ needs.version.outputs.name }}; the `verify <url>
# [ref]` form auto-derives from `ref` when it looks like a tag. Without it,
# the rebuilt APK has no defined MG version → fdroid build fails.
MG_BUILD_TAG_OVERRIDE="${MG_BUILD_TAG_OVERRIDE:-}"

# local.properties from the dev tree is gitignored and not carried into the
# snapshot — read it from $repo_root as a last-resort fallback so determinism
# mode works against a working tree configured per AGENTS.md. Done at script
# scope (not inside compute_mg_vc_base) because the override_recipe_* helpers
# also need MG_BUILD_TAG_OVERRIDE non-empty to add the recipe prebuild line.
if [ -z "$MG_BUILD_TAG_OVERRIDE" ] && [ -f "$repo_root/local.properties" ]; then
    MG_BUILD_TAG_OVERRIDE=$(grep -oE '^MG_BUILD_TAG=[^[:space:]]+' \
        "$repo_root/local.properties" | cut -d= -f2 || true)
fi

# Derive the MG_VERSION_CODE that gradle/mg-version.gradle would compute
# from a tag, given APP_VERSION_CODE in the snapshot. Mirror of the policy
# in gradle/mg-version.gradle and .github/scripts/fdroid_sync.py.
compute_mg_vc_base() {
    local tag="$1"
    local app_vc
    app_vc=$(grep -oE '^APP_VERSION_CODE=[0-9]+' \
        "$work/src/gradle.properties" | cut -d= -f2 || true)
    [ -n "$app_vc" ] || die "could not parse APP_VERSION_CODE from snapshot"
    [ -n "$tag" ] || die "MG_BUILD_TAG_OVERRIDE required (or set MG_BUILD_TAG in local.properties)"
    python3 - "$app_vc" "$tag" <<'PY'
import re, sys
app_vc = int(sys.argv[1])
tag = sys.argv[2]
m = re.match(r'^(\d+)\.(\d+)\.(\d+)\.(\d+)(?:\.(\d+))?$', tag)
if not m:
    sys.exit(f"tag={tag!r} malformed")
m_val = int(m.group(4))
if not 0 <= m_val <= 99:
    sys.exit(f"M={m_val} outside 0..99")
print(app_vc * 100 + m_val)
PY
}
mode="${1:-determinism}"
# Work dir must sit on a roomy filesystem: a full native build + two APKs need
# several GB, far more than a typical /tmp tmpfs. Override with MG_REPRO_WORK.
work_base="${MG_REPRO_WORK:-$HOME/.cache/mg-repro}"
mkdir -p "$work_base"
work=$(mktemp -d "$work_base/run.XXXXXX")
trap '[ -n "${MG_REPRO_KEEP:-}" ] && echo "work kept: $work" || rm -rf "$work"' EXIT

command -v podman >/dev/null || die "podman not found"

# ---------------------------------------------------------------------------
# Container image builders. Cheap to call repeatedly — podman caches layers,
# so a second invocation is a metadata check. Each mode calls only the images
# it needs (verify-diff skips the heavy buildserver build entirely).
# ---------------------------------------------------------------------------

build_buildserver_image() {
    log "build container image (FROM $BUILDSERVER_IMAGE + fdroidserver overlay)"
    mkdir -p "$work/ctx"
    cat > "$work/ctx/Containerfile" <<EOF
FROM $BUILDSERVER_IMAGE
# fdroiddata CI overlays a fdroidserver checkout on PATH/PYTHONPATH instead of
# using the one in the image. Same here, pinned to !1825. Retry: a shallow
# fetch has flaked with truncated-body errors.
RUN for i in 1 2 3 4 5; do \\
      git clone "$FDROIDSERVER_REPO" /opt/fdroidserver \\
        && git -C /opt/fdroidserver checkout "$FDROIDSERVER_REF" && break; \\
      echo "clone attempt \$i failed, retrying"; rm -rf /opt/fdroidserver; sleep 5; \\
    done && test -d /opt/fdroidserver/.git
# fdroidserver runs the recipe's sudo: block; container runs as root.
RUN test -x "\$(command -v sudo)" || { \\
      printf '#!/bin/sh\\nexec "\$@"\\n' > /usr/local/bin/sudo \\
      && chmod +x /usr/local/bin/sudo; }
# Buildserver image ships ANDROID_HOME=/opt/android-sdk skeleton but no SDK
# packages (production buildserver provisions them on demand). Install the
# exact versions the fdroiddata recipe pins via sdkmanager (image already has
# Java 21, curl, unzip; recipe sudo: installs Java 17 from bookworm at build
# time, so we don't add it here).
RUN mkdir -p /opt/android-sdk/cmdline-tools && \\
    curl -fsSL -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && \\
    unzip -q /tmp/clt.zip -d /opt/android-sdk/cmdline-tools && \\
    mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest && \\
    yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null 2>&1 || true && \\
    /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --install \\
      "platform-tools" "platforms;android-35" "build-tools;$BUILD_TOOLS_VERSION" "ndk;$NDK_VERSION" >/dev/null
EOF
    podman build --network=host -t "$IMG" "$work/ctx" >/dev/null
}

build_diff_image() {
    log "build comparison image (diffoscope, outside the build env)"
    mkdir -p "$work/dctx"
    cat > "$work/dctx/Containerfile" <<'EOF'
FROM docker.io/library/debian:trixie-slim
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update -qq \
    && apt-get install -y --no-install-recommends diffoscope unzip \
    && rm -rf /var/lib/apt/lists/*
EOF
    podman build --network=host -t "$DIFF_IMG" "$work/dctx" >/dev/null
}

# ---------------------------------------------------------------------------
# Recipe + container exec helpers.
# ---------------------------------------------------------------------------

# Populate $work/fdroiddata with the minimal tree fdroid needs (just the
# recipe + config.yml). Cloning all of fdroiddata pulls multi-GB of every
# app's screenshots; the real recipe alone is what fdroid build reads.
fetch_recipe() {
    mkdir -p "$work/fdroiddata/metadata"
    local recipe="$work/fdroiddata/metadata/${APPID}.yml"
    if [ -f "$FDROIDDATA_DIR/metadata/${APPID}.yml" ]; then
        log "use local fdroiddata recipe ($FDROIDDATA_DIR)"
        cp "$FDROIDDATA_DIR/metadata/${APPID}.yml" "$recipe"
    else
        log "fetch fdroiddata recipe for $APPID"
        curl -fsSL "${FDROIDDATA_RAW}/metadata/${APPID}.yml" \
            -o "$recipe" || die "could not fetch recipe metadata/${APPID}.yml"
    fi
    [ -s "$recipe" ] || die "recipe metadata/${APPID}.yml empty/missing"
    printf 'sdk_path: %s\n' "${ANDROID_SDK_IN_IMAGE:-/opt/android-sdk}" \
        > "$work/fdroiddata/config.yml"
}

# Run fdroid inside the buildserver image, exactly as fdroiddata CI does:
# source the buildserver env, overlay the pinned fdroidserver on PATH/PYTHONPATH.
fdroid_in_container() {
    podman run --rm --network=host -v "$work":/w:z -w /w/fdroiddata "$IMG" \
        bash -euo pipefail -c '
            test -f /etc/profile.d/bsenv.sh && . /etc/profile.d/bsenv.sh
            export PATH=/opt/fdroidserver:$PATH
            export PYTHONPATH=/opt/fdroidserver${PYTHONPATH:+:$PYTHONPATH}
            git config --global --add safe.directory "*"
            fdroid '"$*"
}

# diffoscope two APK files directly (zip-aware), excluding signature blocks
# and filesystem dir mtimes (those come from the extractor, not the APK).
diffoscope_apks() {  # $1 $2 = apk filenames under $work
    podman run --rm -v "$work":/w:z "$DIFF_IMG" \
        diffoscope --exclude-directory-metadata=yes \
            --exclude 'META-INF/.*\.(RSA|SF|DSA)$' \
            --exclude 'META-INF/MANIFEST\.MF$' \
            "/w/$1" "/w/$2"
}

# Snapshot the source under test (or a ref) into $work/src and mirror-clone
# to a bare repo at $work/fdroiddata/${APPID}.git. Carries uncommitted
# tracked changes when ref is empty/HEAD. Prints the resulting snapshot SHA.
#
# Why a real branch + mirror clone (not git bundle): a plain bundle works
# for `git clone` but fdroidserver's GitVcs doesn't treat a bundle file as a
# fetchable Repo (vcs.gotorevision silently no-ops -> empty build/<appid> ->
# SOURCE_DATE_EPOCH None -> TypeError). A bare repo with HEAD set behaves
# like a normal git URL.
snapshot_source() {
    local ref="${1:-}"
    log "snapshot source under test (submodule gitlinks preserved)"
    git clone --quiet --no-local "$repo_root" "$work/src"
    if [ -n "$ref" ] && [ "$ref" != HEAD ]; then
        git -C "$work/src" checkout --quiet "$(git -C "$repo_root" rev-parse "$ref")"
    else
        git -C "$work/src" checkout --quiet "$(git -C "$repo_root" rev-parse HEAD)"
        if ! git -C "$repo_root" diff --quiet HEAD; then
            git -C "$repo_root" diff --binary HEAD \
                | git -C "$work/src" apply --index --binary
        fi
    fi
    git -C "$work/src" checkout --quiet -B repro
    git -C "$work/src" -c user.email=repro@local -c user.name=repro \
        commit --quiet --allow-empty -m 'repro snapshot'
    local snap_sha
    snap_sha=$(git -C "$work/src" rev-parse HEAD)
    git clone --quiet --mirror "$work/src" "$work/fdroiddata/${APPID}.git"
    git -C "$work/fdroiddata/${APPID}.git" symbolic-ref HEAD refs/heads/repro
    printf '%s\n' "$snap_sha"
}

# Override the recipe's last Builds entry, keeping the recipe's existing
# gradle flavor (determinism mode: builds whatever the recipe declares).
# Computes versionCode from the snapshot's MG_VERSION_CODE + flavor offset
# (same formula as fdroid_sync.py) — fdroidserver post-build-validates APK
# vc against the recipe's versionCode, so a stale recipe vc after an
# upstream rebase fails the check. Prints the resulting versionCode.
override_recipe_keep_flavor() {
    local snap_sha="$1"
    local MG_VC_BASE
    MG_VC_BASE=$(compute_mg_vc_base "$MG_BUILD_TAG_OVERRIDE")
    podman run --rm -i -v "$work":/w:z "$IMG" python3 - \
        "/w/fdroiddata/metadata/${APPID}.yml" "/w/fdroiddata/${APPID}.git" \
        "$snap_sha" "$NDK_VERSION" "$MG_VC_BASE" "$MG_BUILD_TAG_OVERRIDE" <<'PY'
import sys, yaml
recipe, bundle, sha, ndk, vc_base, build_tag = sys.argv[1:7]
FLAVOR_OFFSETS = {
    'afatFdX86': 3, 'afatFdX86_64': 4, 'afatFdArm32': 7, 'afatFdArm64': 8,
}
with open(recipe) as f:
    m = yaml.safe_load(f)
# Drop all but the last Builds entry — fdroid only builds the one we test;
# prior entries break fdroidserver's strict-increasing-versionCode check
# when the new vc lands below the recipe's existing max.
m['Builds'] = [m['Builds'][-1]]
last = m['Builds'][-1]
flavor = last['gradle'][0]
new_vc = int(vc_base) * 10 + FLAVOR_OFFSETS[flavor]
m['RepoType'] = 'git'
m['Repo'] = bundle
last['commit'] = sha
last['ndk'] = ndk
last['versionCode'] = new_vc
last.pop('disable', None)  # recipe template may carry a disable from a broken release
if build_tag:
    # Append (don't replace): preserve the recipe's existing prebuild
    # steps (API_KEYS writer, QUIET_NATIVE_BUILD) — overwriting would
    # break the in-container gradle build at TMessagesProj/build.gradle.
    last.setdefault('prebuild', []).append(
        f"printf '\\nMG_BUILD_TAG={build_tag}\\n' >> gradle.properties"
    )
with open(recipe, 'w') as f:
    yaml.safe_dump(m, f, sort_keys=False, default_flow_style=False)
print(new_vc)
PY
}

# Override the recipe's last Builds entry, forcing the gradle flavor to
# match the given Android ABI. Computes versionCode from MG_VERSION_CODE +
# offset for that flavor. Prints the resulting versionCode.
override_recipe_for_abi() {
    local snap_sha="$1"
    local abi="$2"
    local MG_VC_BASE
    MG_VC_BASE=$(compute_mg_vc_base "$MG_BUILD_TAG_OVERRIDE")
    podman run --rm -i -v "$work":/w:z "$IMG" python3 - \
        "/w/fdroiddata/metadata/${APPID}.yml" "/w/fdroiddata/${APPID}.git" \
        "$snap_sha" "$NDK_VERSION" "$MG_VC_BASE" "$abi" "$MG_BUILD_TAG_OVERRIDE" <<'PY'
import sys, yaml
recipe, bundle, sha, ndk, vc_base, abi, build_tag = sys.argv[1:8]
ABI_FLAVOR = {
    'x86':         ('afatFdX86', 3),
    'x86_64':      ('afatFdX86_64', 4),
    'armeabi-v7a': ('afatFdArm32', 7),
    'arm64-v8a':   ('afatFdArm64', 8),
}
try:
    flavor, offset = ABI_FLAVOR[abi]
except KeyError:
    sys.exit(f"unknown abi {abi!r}; expected one of {sorted(ABI_FLAVOR)}")
new_vc = int(vc_base) * 10 + offset
with open(recipe) as f:
    m = yaml.safe_load(f)
m['RepoType'] = 'git'
m['Repo'] = bundle
# Drop all but the last Builds entry, then override it for the target ABI.
# Keeping prior entries breaks fdroidserver's strict-increasing-versionCode
# check whenever the target ABI's vc (vc_base*10 + offset) is lower than
# the recipe's highest prior vc (arm64 is offset 8, so any non-arm64
# verify-build would regress the order). fdroid build only needs the
# one vc we're testing — historical entries are irrelevant.
m['Builds'] = [m['Builds'][-1]]
last = m['Builds'][-1]
last['gradle'] = [flavor]
last['commit'] = sha
last['ndk'] = ndk
last['versionCode'] = new_vc
last.pop('disable', None)
if build_tag:
    # Append (don't replace): preserve the recipe's existing prebuild
    # steps (API_KEYS writer, QUIET_NATIVE_BUILD) — overwriting would
    # break the in-container gradle build at TMessagesProj/build.gradle.
    last.setdefault('prebuild', []).append(
        f"printf '\\nMG_BUILD_TAG={build_tag}\\n' >> gradle.properties"
    )
with open(recipe, 'w') as f:
    yaml.safe_dump(m, f, sort_keys=False, default_flow_style=False)
print(new_vc)
PY
}

# Override the recipe's last Builds entry with an explicit versionCode (used
# by the URL form of `verify`: vc comes from the comparison APK's
# AndroidManifest, not from source — that's what catches gradle vs
# flavor-offset table drift in that mode). Keeps the recipe's existing
# gradle flavor. Prints the versionCode.
override_recipe_with_vc() {
    local snap_sha="$1"
    local vc="$2"
    podman run --rm -i -v "$work":/w:z "$IMG" python3 - \
        "/w/fdroiddata/metadata/${APPID}.yml" "/w/fdroiddata/${APPID}.git" \
        "$snap_sha" "$vc" "$NDK_VERSION" "$MG_BUILD_TAG_OVERRIDE" <<'PY'
import sys, yaml
recipe, bundle, sha, vc, ndk, build_tag = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), sys.argv[5], sys.argv[6]
with open(recipe) as f:
    data = yaml.safe_load(f)
data['Repo'] = bundle
# Drop all but the last Builds entry — fdroid only builds the one we test;
# prior entries break fdroidserver's strict-increasing-versionCode check.
data['Builds'] = [data['Builds'][-1]]
last = data['Builds'][-1]
last['commit'] = sha
last['versionCode'] = vc
last['ndk'] = ndk
last.pop('disable', None)
if build_tag:
    # Append (don't replace): preserve the recipe's existing prebuild
    # steps (API_KEYS writer, QUIET_NATIVE_BUILD) — overwriting would
    # break the in-container gradle build at TMessagesProj/build.gradle.
    last.setdefault('prebuild', []).append(
        f"printf '\\nMG_BUILD_TAG={build_tag}\\n' >> gradle.properties"
    )
with open(recipe, 'w') as f:
    yaml.dump(data, f, sort_keys=False)
print(vc)
PY
}

# Pre-populate build/<appid> from the bare repo, then run
# `fdroid build --on-server --test`. Output APK lands at
# $work/fdroiddata/tmp/${APPID}_<vc>.apk. Logs go to $3 (full log on
# failure tail-dumped to stderr).
#
# Pre-clone workaround: fdroidserver py3.13 calls set_FDroidPopen_env BEFORE
# prepare_source clones, so get_source_date_epoch returns None for the
# missing dir and `os.environ[...] = None` raises TypeError. Pre-cloning
# makes the function return a real timestamp; fdroidserver then reuses the
# existing checkout in prepare_source.
run_fdroid_build() {
    local snap_sha="$1"
    local vc="$2"
    local log="$3"
    podman run --rm --network=host -v "$work":/w:z -w /w/fdroiddata "$IMG" \
        bash -euo pipefail -c "
            test -f /etc/profile.d/bsenv.sh && . /etc/profile.d/bsenv.sh
            export PATH=/opt/fdroidserver:\$PATH
            export PYTHONPATH=/opt/fdroidserver\${PYTHONPATH:+:\$PYTHONPATH}
            git config --global --add safe.directory '*'
            mkdir -p build
            rm -rf build/${APPID}
            git clone --quiet /w/fdroiddata/${APPID}.git build/${APPID}
            git -C build/${APPID} checkout --quiet ${snap_sha}
            # Submodule fetch retry — boringssl/dav1d/ffmpeg/libvpx/td are
            # pulled from upstream hosts (codeberg/gitlab/github); a transient
            # outage on any one of them used to fail the whole verify-build
            # leg, which then prevented the marker save and trapped the
            # fingerprint in 4-ABI mode (see also the fdroidserver clone
            # retry at script line ~108).
            ok=0
            for i in 1 2 3; do
                if git -C build/${APPID} submodule update --init --recursive --depth 1 >/dev/null 2>&1 \
                    || git -C build/${APPID} submodule update --init --recursive >/dev/null 2>&1; then
                    ok=1; break
                fi
                echo \"submodule update attempt \$i failed\" >&2
                sleep \$((i * 5))
            done
            [ \"\$ok\" = 1 ] || { echo \"submodule update exhausted retries\" >&2; exit 1; }
            fdroid build --on-server --test --no-tarball --refresh-scanner $APPID:$vc
        " > "$log" 2>&1 \
        || { tail -80 "$log"; die "fdroid build failed (full log: $log)"; }
}

# ---------------------------------------------------------------------------
# Mode dispatch.
# ---------------------------------------------------------------------------

case "$mode" in
  determinism)
    build_buildserver_image
    build_diff_image
    fetch_recipe
    ref="${2:-}"
    snap_sha=$(snapshot_source "$ref")
    REPRO_VC=$(override_recipe_keep_flavor "$snap_sha") \
        || die "could not determine versionCode from recipe"
    [ -n "$REPRO_VC" ] || die "could not determine versionCode from recipe"
    log "recipe: overrode latest build commit -> ${snap_sha:0:12}, vc=$REPRO_VC"

    for tag in a b; do
        log "fdroid build #$tag ($APPID:$REPRO_VC @ ${snap_sha:0:12})"
        run_fdroid_build "$snap_sha" "$REPRO_VC" "$work/build-$tag.log"
        cp "$work"/fdroiddata/tmp/${APPID}_${REPRO_VC}.apk "$work/out-$tag.apk"
        rm -f "$work"/fdroiddata/tmp/${APPID}_*.apk
    done

    log "compare the two builds (diffoscope, outside the build env, sigs excluded)"
    if diffoscope_apks out-a.apk out-b.apk; then
        log "RESULT: REPRODUCIBLE — two independent fdroidserver builds are byte-identical"
        exit 0
    else
        log "RESULT: NOT REPRODUCIBLE — see diffoscope output above"
        exit 1
    fi
    ;;

  verify)
    arg2="${2:-}"
    [ -n "$arg2" ] || die "usage: $0 verify <versionCode> | verify <github-apk-url|local-apk> [ref]"
    build_buildserver_image
    build_diff_image
    fetch_recipe

    if [[ "$arg2" =~ ^[0-9]+$ ]]; then
        # ---- versionCode form: compare against f-droid.org-published APK ----
        vc="$arg2"
        log "fdroid build $APPID:$vc (pinned recipe commit)"
        fdroid_in_container "build --no-tarball --skip-scan $APPID:$vc" \
            > "$work/build.log" 2>&1 \
            || { tail -50 "$work/build.log"; die "fdroid build failed (full log: $work/build.log)"; }
        log "fdroid verify $APPID:$vc against https://f-droid.org/repo (v2/v3 graft, !1825)"
        rc=0
        fdroid_in_container "verify --verbose $APPID:$vc" || rc=$?
        cat "$work"/fdroiddata/verified/${APPID}_${vc}.apk.json 2>/dev/null || true
        if [ "$rc" -eq 0 ]; then
            log "RESULT: VERIFIED — local build matches the F-Droid-published APK"
            exit 0
        fi
        log "RESULT: MISMATCH — local build differs from published APK (see JSON above)"
        exit 1
    fi

    # ---- URL/local-path form: compare against a GitHub Release APK ----
    # Mirrors fdroiddata CI's reproducible-build job for a Release-flavor APK
    # built outside of F-Droid (beta channel publishes these per push).
    url="$arg2"
    ref="${3:-HEAD}"
    # When ref is itself a tag (X.Y.Z.M or X.Y.Z.M.K), use it to seed
    # MG_BUILD_TAG_OVERRIDE so the rebuilt APK matches the CI -P. Caller
    # can still override via env var if ref is a SHA.
    # Bound the 4th component (M) to 0..99 to match gradle/mg-version.gradle's
    # slot constraint — silently auto-deriving an out-of-range tag would log a
    # misleading "auto-derived" line before the build fails downstream.
    if [ -z "$MG_BUILD_TAG_OVERRIDE" ] && [[ "$ref" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.([0-9]|[1-9][0-9])(\.[0-9]+)?$ ]]; then
        MG_BUILD_TAG_OVERRIDE="$ref"
        log "auto-derived MG_BUILD_TAG_OVERRIDE=$MG_BUILD_TAG_OVERRIDE from ref"
    fi
    case "$url" in
        https://*|http://*)
            log "download GitHub APK: $url"
            curl -fsSL "$url" -o "$work/github.apk" || die "download failed"
            ;;
        *)
            [ -f "$url" ] || die "verify arg must be a versionCode, http(s) URL, or existing local APK path"
            log "use local APK: $url"
            cp "$url" "$work/github.apk"
            ;;
    esac

    log "extract versionCode from GitHub APK manifest (aapt2 dump badging)"
    # `|| true` so a pipeline component returning non-zero (corrupt APK,
    # aapt2 abort, head closing pipe early) doesn't trip set -e + pipefail
    # before the explicit die guard fires.
    apk_vc=$(podman run --rm -v "$work":/w:z "$IMG" \
        /opt/android-sdk/build-tools/$BUILD_TOOLS_VERSION/aapt2 dump badging /w/github.apk \
        | sed -n "s/^package: .*versionCode='\([0-9]\+\)'.*/\1/p" | head -1 || true)
    [ -n "$apk_vc" ] || die "could not extract versionCode from $url"
    log "GitHub APK versionCode: $apk_vc"

    log "snapshot source at ref=$ref (carries uncommitted tracked changes when ref=HEAD)"
    snap_sha=$(snapshot_source "$ref")
    REPRO_VC=$(override_recipe_with_vc "$snap_sha" "$apk_vc")

    log "fdroid build --on-server --test $APPID:$REPRO_VC (rebuild GitHub APK from source)"
    run_fdroid_build "$snap_sha" "$REPRO_VC" "$work/build.log"
    cp "$work"/fdroiddata/tmp/${APPID}_${REPRO_VC}.apk "$work/local.apk"

    log "extract APKs (APK Signing Block and META-INF/* excluded from comparison)"
    rm -rf "$work/github.unzip" "$work/local.unzip"
    mkdir -p "$work/github.unzip" "$work/local.unzip"
    unzip -qq "$work/github.apk" -d "$work/github.unzip" -x 'META-INF/*'
    unzip -qq "$work/local.apk"  -d "$work/local.unzip"  -x 'META-INF/*'

    log "diffoscope github.apk content vs local.apk content"
    if podman run --rm -v "$work":/w:z "$DIFF_IMG" \
        diffoscope --exclude-directory-metadata=yes \
            /w/github.unzip /w/local.unzip; then
        log "RESULT: VERIFIED — GitHub APK matches local F-Droid-env build (signatures excluded)"
        exit 0
    fi
    log "RESULT: MISMATCH — see diffoscope output above"
    exit 1
    ;;

  verify-build)
    # Usage: verify-build <abi> [ref] [-o <output.apk>]
    # ref is optional — recognise `-o` in either the [ref] or the trailing
    # slot. The previous parser greedily consumed `-o` as ref when the
    # caller omitted ref, then died on the output path with a confusing
    # message.
    abi="${2:-}"
    [ -n "$abi" ] || die "usage: $0 verify-build <abi> [ref] [-o <output.apk>]"
    arg3="${3:-}"
    arg4="${4:-}"
    arg5="${5:-}"
    ref=HEAD
    out=""
    if [ "$arg3" = -o ]; then
        out="$arg4"
        [ -n "$out" ] || die "-o requires an output path"
        [ -z "$arg5" ] || die "unexpected trailing arg '$arg5'"
    elif [ -n "$arg3" ]; then
        ref="$arg3"
        if [ -n "$arg4" ]; then
            [ "$arg4" = -o ] || die "unexpected arg '$arg4' (expected -o <output.apk>)"
            out="$arg5"
            [ -n "$out" ] || die "-o requires an output path"
            arg6="${6:-}"
            [ -z "$arg6" ] || die "unexpected trailing arg '$arg6'"
        fi
    fi
    # Default output path is OUTSIDE $work (which gets rm -rf'd on EXIT). The
    # caller usually passes -o so this default is mostly a safety net.
    [ -z "$out" ] && out="$work_base/fdroid-${abi}.apk"

    # Auto-derive MG_BUILD_TAG_OVERRIDE from ref when it looks like a tag.
    # beta.yml passes $GITHUB_SHA here (not a tag), so it must set the env
    # var explicitly to keep the rebuilt APK byte-identical to the CI one
    # (which bakes the tag via -PMG_BUILD_TAG).
    # Bound the 4th component (M) to 0..99 to match gradle/mg-version.gradle's
    # slot constraint — silently auto-deriving an out-of-range tag would log a
    # misleading "auto-derived" line before the build fails downstream.
    if [ -z "$MG_BUILD_TAG_OVERRIDE" ] && [[ "$ref" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.([0-9]|[1-9][0-9])(\.[0-9]+)?$ ]]; then
        MG_BUILD_TAG_OVERRIDE="$ref"
        log "auto-derived MG_BUILD_TAG_OVERRIDE=$MG_BUILD_TAG_OVERRIDE from ref"
    fi

    build_buildserver_image
    fetch_recipe
    log "snapshot source at ref=$ref"
    snap_sha=$(snapshot_source "$ref")
    REPRO_VC=$(override_recipe_for_abi "$snap_sha" "$abi") \
        || die "could not compute versionCode for abi=$abi"
    [ -n "$REPRO_VC" ] || die "could not compute versionCode for abi=$abi"
    log "recipe: overrode commit -> ${snap_sha:0:12}, abi=$abi, vc=$REPRO_VC"

    log "fdroid build --on-server --test $APPID:$REPRO_VC"
    run_fdroid_build "$snap_sha" "$REPRO_VC" "$work/build.log"
    # Copy outside $work so EXIT trap doesn't take it with it.
    mkdir -p "$(dirname "$out")"
    cp "$work"/fdroiddata/tmp/${APPID}_${REPRO_VC}.apk "$out"
    # Compute sha on a separate line so a fs/pipefail hiccup here can't
    # kill the script after the deliverable is already on disk.
    out_sha=$(sha256sum "$out" | cut -d' ' -f1 || true)
    log "RESULT: BUILT — APK at $out (vc=$REPRO_VC, abi=$abi, sha256=$out_sha)"
    exit 0
    ;;

  verify-diff)
    # Usage: verify-diff <a.apk> <b.apk>
    a="${2:-}"
    b="${3:-}"
    [ -n "$a" ] && [ -n "$b" ] || die "usage: $0 verify-diff <a.apk> <b.apk>"
    [ -f "$a" ] || die "missing apk: $a"
    [ -f "$b" ] || die "missing apk: $b"

    build_diff_image
    cp "$a" "$work/a.apk"
    cp "$b" "$work/b.apk"

    log "extract APKs (APK Signing Block and META-INF/* excluded from comparison)"
    rm -rf "$work/a.unzip" "$work/b.unzip"
    mkdir -p "$work/a.unzip" "$work/b.unzip"
    unzip -qq "$work/a.apk" -d "$work/a.unzip" -x 'META-INF/*'
    unzip -qq "$work/b.apk" -d "$work/b.unzip" -x 'META-INF/*'

    log "diffoscope a.apk content vs b.apk content"
    if podman run --rm -v "$work":/w:z "$DIFF_IMG" \
        diffoscope --exclude-directory-metadata=yes \
            /w/a.unzip /w/b.unzip; then
        log "RESULT: VERIFIED — APKs match (signatures excluded)"
        exit 0
    fi
    log "RESULT: MISMATCH — see diffoscope output above"
    exit 1
    ;;

  *)
    die "unknown mode '$mode' (expected: determinism | verify | verify-build | verify-diff)"
    ;;
esac
