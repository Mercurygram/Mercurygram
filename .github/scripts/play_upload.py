#!/usr/bin/env python3
"""Upload a Mercurygram app bundle to a Google Play track.

Uses only the Python standard library plus the openssl CLI, so the release
job needs no pip install and no Google client library:
  1. Sign a service-account JWT with openssl and trade it for an OAuth token.
  2. androidpublisher v3 Edits flow: insert an edit, upload the bundle, set
     the track release (status completed, en-US notes), then commit.

--validate-only runs the same flow but validates the edit instead of
committing it, so nothing reaches Play. The edit is then dropped.

The release notes come from the GitHub release body (changelog.md), which
uses a tiny HTML subset (<b>, <br>, bullet glyphs). Play renders plain text
and caps notes at 500 characters per language, so tags are stripped and the
text is cut at the last whole line that fits.

Usage:
  play_upload.py [--package=it.belloworld.mercurygram] [--track=internal]
                 [--notes=changelog.md] [--validate-only]
                 <service_account.json> <bundle.aab>
"""

import argparse
import base64
import html
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD_API = ("https://androidpublisher.googleapis.com/upload/"
              "androidpublisher/v3/applications")
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
NOTES_MAX = 500


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=")


def sign_rs256(message, private_key_pem):
    # openssl needs the key as a file. Keep it in a private temp dir that is
    # removed right after signing.
    with tempfile.TemporaryDirectory() as tmp:
        key_path = os.path.join(tmp, "key.pem")
        fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(private_key_pem)
        return subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", key_path],
            input=message, capture_output=True, check=True).stdout


def access_token(sa):
    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT"}
    claims = {"iss": sa["client_email"], "scope": SCOPE,
              "aud": sa["token_uri"], "iat": now, "exp": now + 3600}
    unsigned = (b64url(json.dumps(header).encode()) + b"." +
                b64url(json.dumps(claims).encode()))
    jwt = unsigned + b"." + b64url(sign_rs256(unsigned, sa["private_key"]))
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": jwt.decode(),
    }).encode()
    return request("POST", sa["token_uri"], None, body,
                   "application/x-www-form-urlencoded")["access_token"]


def request(method, url, token, body=None, content_type="application/json"):
    headers = {"Content-Type": content_type}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=600) as resp:
            data = resp.read()
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {url.split('?')[0]}: HTTP {e.code}\n"
                 f"{e.read().decode(errors='replace')}")
    return json.loads(data) if data else {}


def release_notes(path):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    # The body writes "<br>" plus a newline, count that as one line break.
    text = re.sub(r"<br\s*/?>\r?\n?", "\n", text, flags=re.I)
    text = html.unescape(re.sub(r"<[^>]+>", "", text))
    lines = [line.strip() for line in text.splitlines()]
    text = re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()
    if len(text) <= NOTES_MAX:
        return text
    cut = text[:NOTES_MAX - 1].rsplit("\n", 1)[0].rstrip()
    return cut + "\n…"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--package", default="it.belloworld.mercurygram")
    ap.add_argument("--track", default="internal")
    ap.add_argument("--notes", help="changelog.md of the release")
    ap.add_argument("--validate-only", action="store_true")
    ap.add_argument("service_account")
    ap.add_argument("bundle")
    args = ap.parse_args()

    with open(args.service_account, encoding="utf-8") as f:
        sa = json.load(f)
    with open(args.bundle, "rb") as f:
        bundle = f.read()

    token = access_token(sa)
    base = f"{API}/{args.package}/edits"

    edit = request("POST", base, token, b"{}")["id"]
    print(f"edit {edit}")

    up = request("POST", f"{UPLOAD_API}/{args.package}/edits/{edit}/bundles"
                 "?uploadType=media", token, bundle, "application/octet-stream")
    vc = str(up["versionCode"])
    print(f"uploaded bundle versionCode {vc} sha256 {up.get('sha256')}")

    rel = {"versionCodes": [vc], "status": "completed"}
    if args.notes:
        rel["releaseNotes"] = [{"language": "en-US",
                                "text": release_notes(args.notes)}]
    request("PUT", f"{base}/{edit}/tracks/{args.track}", token,
            json.dumps({"track": args.track, "releases": [rel]}).encode())
    print(f"track {args.track} set to versionCode {vc}")

    if args.validate_only:
        request("POST", f"{base}/{edit}:validate", token)
        request("DELETE", f"{base}/{edit}", token)
        print("validated, edit discarded")
    else:
        request("POST", f"{base}/{edit}:commit", token)
        print("committed")


if __name__ == "__main__":
    main()
