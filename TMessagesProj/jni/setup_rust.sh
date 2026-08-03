#!/usr/bin/env bash
# Install the pinned Rust toolchain used by jni/prebuild/build_tlottie.sh.
#
# Upstream commits the four cargo-built libtlottie.a archives; we build them
# instead, so every environment that builds this repo needs a Rust toolchain:
# a developer machine, the GitHub runners, the podman test container and the
# F-Droid buildserver. None of them agree on where a toolchain would live, and
# rustc bakes the crate-registry path into its output, so a per-environment
# CARGO_HOME would make the archives differ between our build and F-Droid's.
#
# Both homes are therefore fixed literals, exactly like the /var/tmp/mg-jni
# symlink the media build uses and for the same reproducibility reason. This
# script is idempotent: it exits immediately once the pinned toolchain and all
# four Android targets are present.
#
# Callers must export the same RUSTUP_HOME / CARGO_HOME (TMessagesProj's
# buildNativeLibs* tasks do).
set -Eeuo pipefail

TOOLCHAIN="${1:?usage: setup_rust.sh <toolchain-version>}"

export RUSTUP_HOME="${RUSTUP_HOME:-/var/tmp/mg-rustup}"
export CARGO_HOME="${CARGO_HOME:-/var/tmp/mg-cargo}"
export PATH="$CARGO_HOME/bin:$PATH"

TARGETS="aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android"

# Probe CARGO_HOME rather than PATH: a rustup that happens to be installed in
# the caller's home directory would drive our RUSTUP_HOME but leave the proxy
# binaries out of CARGO_HOME, and `rustup target add` then fails with
# "rustup is not installed at '$CARGO_HOME'".
RUSTUP="$CARGO_HOME/bin/rustup"
if [ ! -x "$RUSTUP" ]; then
    curl -sSf https://sh.rustup.rs \
        | sh -s -- -y --no-modify-path --profile minimal \
                   --default-toolchain "$TOOLCHAIN"
fi

"$RUSTUP" toolchain list | grep -q "^${TOOLCHAIN}-" \
    || "$RUSTUP" toolchain install "$TOOLCHAIN" --profile minimal

installed="$("$RUSTUP" target list --toolchain "$TOOLCHAIN" --installed)"
missing=""
for t in $TARGETS; do
    printf '%s\n' "$installed" | grep -qx "$t" || missing="$missing $t"
done
[ -z "$missing" ] || "$RUSTUP" target add --toolchain "$TOOLCHAIN" $missing

echo "rust $TOOLCHAIN ready in $RUSTUP_HOME (targets:$TARGETS)"
