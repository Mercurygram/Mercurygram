#!/usr/bin/env python3
"""Sync a Mercurygram release into a local fdroiddata checkout.

Steps per release tag:
  1. Verify each ABI APK with fdroidserver and extract its v1+v2/v3 signature
     block into metadata/<appid>/signatures/<vc>/.
  2. Edit metadata/<appid>.yml via ruamel.yaml (round-trip), appending one
     Builds: entry per ABI (cloned from the most recent same-flavor entry,
     with versionName/versionCode/commit replaced).
  3. Bump CurrentVersion / CurrentVersionCode.

Two F-Droid recipes are kept in sync:
  * main         -> it.belloworld.mercurygram          (TMessagesProj_App)
  * plugin.tor   -> it.belloworld.mercurygram.plugin.tor (TMessagesProj_PluginTor)

Both APKs come from the same git tag / signing key but get separate recipes.
APK files from both AppIDs may co-exist under apks_dir; we filter by package
name (common.get_apk_id()) so we only sign-verify the APKs that belong to
the recipe currently being edited.

Usage:
  fdroid_sync.py [--app=main|plugin.tor|both] <tag> <apks_dir> \
                 <fdroiddata_root> <mercurygram_repo>
"""

import argparse
import copy
import glob
import os
import re
import subprocess
import sys

from fdroidserver import common
from ruamel.yaml import YAML

APPS = {
    'main': {
        'appid': 'it.belloworld.mercurygram',
    },
    'plugin.tor': {
        'appid': 'it.belloworld.mercurygram.plugin.tor',
    },
}
# Shared across both AppIDs — main and plugin use the same afatFd* flavor
# names with the same per-ABI offsets (see TMessagesProj_App/build.gradle
# and TMessagesProj_PluginTor/build.gradle).
FLAVOR_OFFSETS = {
    'afatFdX86':    3,
    'afatFdX86_64': 4,
    'afatFdArm32':  7,
    'afatFdArm64':  8,
}
TAG_RE = re.compile(r'^(\d+)\.(\d+)\.(\d+)\.(\d+)(?:\.(\d+))?$')


def derive_m(tag: str) -> int:
    """Mirror gradle/mg-version.gradle: the 4th tag component verbatim,
    regardless of whether the tag is 4-dotted or 5-dotted."""
    m = TAG_RE.match(tag)
    if not m:
        sys.exit(f'tag={tag!r} malformed — expected X.Y.Z.M or X.Y.Z.M.K')
    m_val = int(m.group(4))
    if not 0 <= m_val <= 99:
        sys.exit(f'tag={tag!r}: M={m_val} outside 0..99 slot')
    return m_val


def sync_app(
    app_key: str,
    tag: str,
    apks_dir: str,
    fdroiddata_root: str,
    vn: str,
    vc_base: int,
    ndk_ver: str,
    sha: str,
) -> None:
    """Sync a single AppID's recipe + signatures from the APKs under apks_dir."""
    appid = APPS[app_key]['appid']
    sigroot = os.path.join(fdroiddata_root, 'metadata', appid, 'signatures')

    # Filter APKs by their embedded package name so a shared apks_dir holding
    # both main and plugin APKs doesn't make us extract signatures of the wrong
    # AppID into this recipe's signature tree.
    matched_any = False
    for apk in sorted(glob.glob(os.path.join(apks_dir, '*.apk'))):
        apk_appid, vc, _ = common.get_apk_id(apk)
        if apk_appid != appid:
            continue
        matched_any = True
        if not common.verify_apk_signature(apk):
            sys.exit(f'invalid sig: {apk}')
        d = os.path.join(sigroot, str(vc))
        os.makedirs(d, exist_ok=True)
        common.apk_extract_signatures(apk, d)
        print(f'[{app_key}] extracted {apk} -> {d}', file=sys.stderr)

    if not matched_any:
        print(
            f'[{app_key}] WARNING: no APKs in {apks_dir} matched appid={appid}',
            file=sys.stderr,
        )

    yaml = YAML(typ='rt')
    yaml.width = 80  # match fdroiddata's default folding so diffs stay minimal
    yaml.indent(mapping=2, sequence=4, offset=2)
    ymlpath = os.path.join(fdroiddata_root, 'metadata', f'{appid}.yml')
    # Gracefully skip recipes that haven't been merged into fdroiddata yet —
    # the plugin recipe ships here as a .yml.template until its initial MR
    # lands upstream. Without this guard, --app=both would FileNotFoundError
    # on the first stable tag after the plugin split and break the main
    # recipe sync too.
    if not os.path.exists(ymlpath):
        print(
            f'[{app_key}] recipe {ymlpath} not in fdroiddata yet — skipping',
            file=sys.stderr,
        )
        return
    with open(ymlpath) as fh:
        data = yaml.load(fh)

    builds = data['Builds']
    existing_vcs = {b.get('versionCode') for b in builds}

    for flavor, off in FLAVOR_OFFSETS.items():
        new_vc = vc_base * 10 + off
        if new_vc in existing_vcs:
            print(
                f'[{app_key}] skip {flavor}: vc {new_vc} already present',
                file=sys.stderr,
            )
            continue
        template = next(
            (b for b in reversed(builds) if b.get('gradle') == [flavor]),
            None,
        )
        if template is None:
            sys.exit(
                f'[{app_key}] no template Builds entry for flavor {flavor}'
            )
        new = copy.deepcopy(template)
        new.pop('disable', None)  # template may carry a disable from a broken release
        new['versionName'] = vn
        new['versionCode'] = new_vc
        new['commit'] = sha
        new['ndk'] = ndk_ver
        # Transient: until the next fdroid release lands a Builds entry
        # carrying `printf 'MG_BUILD_TAG=$$VERSION$$' >> ../gradle.properties`
        # natively, write it ourselves. fdroidserver substitutes $$VERSION$$
        # with the recipe's versionName at build time, so the printf'd line
        # ends up the same regardless of who appends it.
        new.setdefault('prebuild', []).append(
            "printf '\\nMG_BUILD_TAG=$$VERSION$$\\n' >> ../gradle.properties"
        )
        builds.append(new)
        print(f'[{app_key}] appended {flavor} vc={new_vc}', file=sys.stderr)

    data['CurrentVersion'] = vn
    data['CurrentVersionCode'] = vc_base * 10 + max(FLAVOR_OFFSETS.values())

    with open(ymlpath, 'w') as fh:
        yaml.dump(data, fh)


def main(
    app: str,
    tag: str,
    apks_dir: str,
    fdroiddata_root: str,
    mg_repo: str,
) -> None:
    with open(os.path.join(mg_repo, 'gradle.properties')) as fh:
        gp = fh.read()
    app_vc = int(re.search(r'^APP_VERSION_CODE=(\d+)', gp, re.M).group(1))
    vc_base = app_vc * 100 + derive_m(tag)
    # MG_VERSION_NAME = tag verbatim; mirrors gradle/mg-version.gradle.
    vn = tag
    with open(os.path.join(mg_repo, 'TMessagesProj', 'build.gradle')) as fh:
        ndk_ver = re.search(r'ndkVersion\s+"([\d.]+)"', fh.read()).group(1)
    sha = subprocess.check_output(
        ['git', '-C', mg_repo, 'rev-list', '-n', '1', tag]
    ).strip().decode()

    common.read_config()

    selected = list(APPS.keys()) if app == 'both' else [app]
    for app_key in selected:
        sync_app(
            app_key=app_key,
            tag=tag,
            apks_dir=apks_dir,
            fdroiddata_root=fdroiddata_root,
            vn=vn,
            vc_base=vc_base,
            ndk_ver=ndk_ver,
            sha=sha,
        )


if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        '--app',
        choices=['main', 'plugin.tor', 'both'],
        default='main',
        help='which AppID recipe to sync (default: main, preserves the '
             'pre-plugin-split call signature for existing workflows)',
    )
    parser.add_argument('tag')
    parser.add_argument('apks_dir')
    parser.add_argument('fdroiddata_root')
    parser.add_argument('mercurygram_repo')
    args = parser.parse_args()
    main(
        app=args.app,
        tag=args.tag,
        apks_dir=args.apks_dir,
        fdroiddata_root=args.fdroiddata_root,
        mg_repo=args.mercurygram_repo,
    )
