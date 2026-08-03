#!/bin/bash
set -e

MESON=third_party/dav1d/include/meson.build

# Idempotency: the probe path below is what this patch installs.
grep -q "'/nonexistent'" "$MESON" && exit

# dav1d stamps vcs_version.h from `git describe --long --always` and compiles
# the result into DAV1D_VERSION. The value depends on whether the submodule
# clone carries tags and on git's auto-scaled abbreviation length, so the same
# pin yields "1.5.4-0-g54706fc" in one checkout and "54706fc6" in another. Both
# strings land in libtmessages' merged SHF_STRINGS pool, where the 9-byte
# length difference shifts every following offset and makes the .so differ
# between two builds of identical sources, which broke F-Droid reproducibility
# when the fdroidserver container's checkout stopped carrying dav1d's tags.
# Point the probe at a non-existent git dir: the command fails and meson falls
# back to vcs_tag's default, meson.project_version(), a fixed "1.5.4". Same
# approach as GIT_EXE for ggml in CMakeLists.txt; ffmpeg gets the equivalent
# treatment via its VERSION file in build.gradle's patchNativeSources.
sed -i "s|join_paths(dav1d_src_root, '\.git')|'/nonexistent'|" "$MESON"

grep -q "'/nonexistent'" "$MESON" || { echo "dav1d: vcs_tag patch did not apply" >&2; exit 1; }

echo "dav1d: deterministic DAV1D_VERSION patch applied"
