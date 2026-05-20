#!/usr/bin/env bash
# mgSaveConfig()/mgLoadConfig() persist every MG flag in the "userconfing"
# SharedPreferences file. A setter that writes the same key to another file
# (historically "mainconfig") is never read back, so the flag reverts on the
# next launch -- silently, because a later saveConfig() usually rewrites the
# correct in-memory value, hiding the bug until a setter is followed by a
# process kill. Flag those writes, and any key loaded but never saved.
# Exit 1 on findings.

set -eu

src=TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java
if [ ! -f "$src" ]; then
    echo "error: $src not found (run from repo root)" >&2
    exit 2
fi

python3 - "$src" <<'PY'
import re, sys

lines = open(sys.argv[1]).read().split('\n')

def block(signature):
    start = next(i for i, l in enumerate(lines) if signature in l)
    depth, opened = 0, False
    for i in range(start, len(lines)):
        depth += lines[i].count('{') - lines[i].count('}')
        opened = opened or '{' in lines[i]
        if opened and depth == 0:
            return start, i
    raise SystemExit('error: unterminated %s' % signature)

save_start, save_end = block('private static void mgSaveConfig')
load_start, load_end = block('private static void mgLoadConfig')
saved = set(re.findall(r'put\w+\("([^"]+)"', '\n'.join(lines[save_start:save_end + 1])))
loaded = set(re.findall(r'get\w+\("([^"]+)"', '\n'.join(lines[load_start:load_end + 1])))

findings = []
for key in sorted(loaded - saved):
    findings.append('%s: loaded by mgLoadConfig but never written by mgSaveConfig' % key)
for key in sorted(saved - loaded):
    findings.append('%s: written by mgSaveConfig but never read by mgLoadConfig' % key)

for i, line in enumerate(lines):
    if save_start <= i <= save_end or load_start <= i <= load_end:
        continue
    m = re.search(r'put\w+\("([^"]+)"', line)
    if not m or m.group(1) not in (saved | loaded):
        continue
    prefs = None
    for j in range(i, max(0, i - 12), -1):
        p = re.search(r'getSharedPreferences\("(\w+)"', lines[j])
        if p:
            prefs = p.group(1)
            break
    if prefs != 'userconfing':
        findings.append('%s:%d: %s written to "%s", expected "userconfing"'
                        % (sys.argv[1], i + 1, m.group(1), prefs))

print('MG keys persisted by mgSaveConfig: %d' % len(saved))
if findings:
    print('\n%d finding(s):' % len(findings))
    for f in findings:
        print('  ' + f)
    raise SystemExit(1)
print('OK: every MG key is written to and read from "userconfing".')
PY
