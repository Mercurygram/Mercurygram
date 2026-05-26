#!/usr/bin/env python3
"""
Probe whether Telegram production DCs accept MTProto framed inside HTTP/1.1
POST requests over TLS on port 443.

Why
---
Commit c618e62 mitigates the OCCRP/Symbolic Software disclosure (obfuscation2
keys recoverable from the 64-byte TCP handshake -> passive `auth_key_id`
extraction) by routing MTProto through an embedded tor daemon. A lighter
alternative under consideration is to speak MTProto-over-HTTPS directly to
Telegram DCs, putting the entire MTProto frame (incl. the 8-byte
`auth_key_id`) inside a real TLS record stream.

Whether the production DCs actually serve HTTPS-MTProto on :443 is the gate
for that design. The MTProto transport doc page
(https://core.telegram.org/mtproto/transports#http) describes the HTTP
transport but says nothing definitive about TLS on :443 in 2026. Historically
:443 served raw TCP MTProto. This script checks.

How
---
For every production DC IP (v4 + v6) currently shipped in
TMessagesProj/jni/tgnet/ConnectionsManager.cpp::initDatacenters, the script:

  1. Opens a TLS connection to `<ip>:443`. The SSLContext disables session
     tickets (SSL_OP_NO_TICKET) and certificate verification: we are not
     authenticating the server, only testing whether it speaks TLS+HTTP on
     this socket. The privacy-correct production client would keep
     OP_NO_TICKET (to avoid a stable session-ticket fingerprint replacing
     the auth_key_id one) AND validate the cert chain.
  2. Builds an unauthenticated MTProto packet wrapping a `req_pq_multi`
     constructor (the first message of every MTProto session, no auth_key
     needed).
  3. Wraps the packet in `POST /api HTTP/1.1` with Content-Length.
  4. Reads the HTTP response. Expected: HTTP/1.1 200 OK + body that decodes
     as an unauthenticated MTProto frame containing a `resPQ` object whose
     `nonce` field echoes ours.

Per-DC verdict:
  OK          - HTTP 200 + valid resPQ echoing our nonce.
  TLS_FAIL    - TLS handshake never completed (the socket likely serves raw
                TCP MTProto on :443 and choked on the ClientHello).
  HTTP_FAIL   - TLS OK but no usable HTTP response (mid-stream RST, wrong
                content type, non-200 status, etc.).
  REPLY_FAIL  - HTTP OK but body is not a well-formed unauth MTProto frame
                with a resPQ.

Final stdout line: `https_mtproto=supported|unsupported|partial`.

  supported   - every endpoint returned OK. Greenlight to implement the
                native HTTPS-MTProto transport (see the plan at
                ~/.claude/plans/about-last-commit-we-merry-wren.md).
  unsupported - zero endpoints returned OK. Design dies; the plan must fall
                back to MTProto-TLS proxy or another approach.
  partial     - some OK, some not. Silent degradation for users on the
                failing DCs would be unacceptable for a privacy toggle.

Setup
-----
    python3 scripts/probe-https-mtproto.py
    # or, for a pinned interpreter:
    uv run --python 3.13 scripts/probe-https-mtproto.py

Standard library only - no telethon, no third-party deps. No login, no phone,
no auth key. The handshake endpoint is unauthenticated and the request has
zero side-effect on any account.

Re-run when
-----------
- Designing or revisiting the HTTPS-MTProto toggle.
- After any Telegram MTProto / transport policy announcement.
- After bumping the DC IP list in ConnectionsManager.cpp::initDatacenters.
"""

import asyncio
import secrets
import ssl
import struct
import sys
import time


# Production DC endpoints. Mirror TMessagesProj/jni/tgnet/ConnectionsManager.cpp
# ::initDatacenters (lines ~1871-1905). The probe targets the bootstrap addresses
# the native client uses on a cold start; if the user later receives a
# `help.getConfig` reply with extra dc_options, those should be probed too.
DCS: list[tuple[int, str]] = [
    (1, "149.154.175.50"),
    (1, "2001:b28:f23d:f001::a"),
    (2, "149.154.167.51"),
    (2, "95.161.76.100"),
    (2, "2001:67c:4e8:f002::a"),
    (3, "149.154.175.100"),
    (3, "2001:b28:f23d:f003::a"),
    (4, "149.154.167.91"),
    (4, "2001:67c:4e8:f004::a"),
    (5, "149.154.171.5"),
    (5, "2001:b28:f23f:f005::a"),
]

PORT = 443

CONNECT_TIMEOUT = 10  # seconds
HTTP_READ_TIMEOUT = 15  # seconds


def _make_ssl_ctx() -> ssl.SSLContext:
    """Privacy-correct client TLS context, minus cert validation (probe-only)."""
    ctx = ssl.create_default_context()
    # Cert validation is irrelevant to "does the server speak HTTPS-MTProto?".
    # The production toggle MUST re-enable full cert validation.
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    # No session tickets: matches what the production client must do to avoid
    # the ticket becoming the new stable per-user fingerprint that replaces
    # auth_key_id. Probing with the same setting catches a server-side hard
    # requirement on resumption (none is documented, but check anyway).
    ctx.options |= ssl.OP_NO_TICKET
    return ctx


def _new_msg_id() -> int:
    """Unauthenticated-message msg_id per MTProto spec: high 32 bits = unixtime,
    low 32 bits hold sub-second precision; low 2 bits zeroed for client->server."""
    ns = time.time_ns()
    secs, frac_ns = divmod(ns, 1_000_000_000)
    frac32 = (frac_ns << 32) // 1_000_000_000
    return (secs << 32) | (frac32 & 0xFFFFFFFC)


def _build_req_pq_multi(nonce_bytes: bytes) -> bytes:
    """Unauthenticated MTProto packet carrying req_pq_multi.

    Frame: auth_key_id(8) | msg_id(8) | msg_len(4) | constructor(4) | nonce(16)
    Constructor 0xbe7e8ef1 = req_pq_multi (layer-independent handshake call).
    """
    assert len(nonce_bytes) == 16
    body = struct.pack("<I", 0xBE7E8EF1) + nonce_bytes  # 4 + 16 = 20 bytes
    return struct.pack("<qqi", 0, _new_msg_id(), len(body)) + body


def _parse_unauth_response(payload: bytes, expected_nonce: bytes) -> tuple[bool, str]:
    """Parse an unauthenticated MTProto frame. Verify it carries a resPQ
    constructor with our nonce echoed back."""
    if len(payload) < 20:
        return False, f"short payload ({len(payload)} bytes)"
    auth_key_id, _msg_id, msg_len = struct.unpack("<qqi", payload[:20])
    if auth_key_id != 0:
        return False, f"non-zero auth_key_id in reply: {auth_key_id:#x}"
    if msg_len <= 0 or 20 + msg_len > len(payload):
        return False, f"bad msg_len {msg_len} (total {len(payload)})"
    body = payload[20:20 + msg_len]
    if len(body) < 4:
        return False, "body shorter than constructor id"
    constructor = struct.unpack("<I", body[:4])[0]
    if constructor != 0x05162463:  # resPQ
        return False, f"unexpected constructor {constructor:#010x} (want resPQ 0x05162463)"
    # resPQ layout: int nonce(16) | int server_nonce(16) | string pq | vector long fingerprints
    if len(body) < 4 + 16:
        return False, "resPQ body truncated before nonce"
    echoed = body[4:20]
    if echoed != expected_nonce:
        return False, f"nonce mismatch: got {echoed.hex()} want {expected_nonce.hex()}"
    return True, "resPQ ok"


async def _probe_one(ip: str) -> tuple[str, str]:
    ctx = _make_ssl_ctx()
    nonce = secrets.token_bytes(16)
    req_bytes = _build_req_pq_multi(nonce)

    bracketed = f"[{ip}]" if ":" in ip else ip
    http_req = (
        f"POST /api HTTP/1.1\r\n"
        f"Host: {bracketed}:{PORT}\r\n"
        f"Content-Type: application/x-www-form-urlencoded\r\n"
        f"Connection: keep-alive\r\n"
        f"Keep-Alive: timeout=100000, max=10000000\r\n"
        f"Content-Length: {len(req_bytes)}\r\n\r\n"
    ).encode("ascii") + req_bytes

    try:
        try:
            reader, writer = await asyncio.wait_for(
                asyncio.open_connection(ip, PORT, ssl=ctx),
                timeout=CONNECT_TIMEOUT,
            )
        except (ssl.SSLError, OSError) as e:
            return "TLS_FAIL", f"{type(e).__name__}: {e}"
        except asyncio.TimeoutError:
            return "TLS_FAIL", "connect timeout"

        try:
            writer.write(http_req)
            await writer.drain()

            try:
                status_line = await asyncio.wait_for(
                    reader.readline(), timeout=HTTP_READ_TIMEOUT,
                )
            except asyncio.TimeoutError:
                return "HTTP_FAIL", "timeout reading status line"
            if not status_line:
                return "HTTP_FAIL", "empty status line (EOF after TLS)"
            if not status_line.startswith(b"HTTP/"):
                return "HTTP_FAIL", f"non-HTTP reply: {status_line[:60]!r}"
            parts = status_line.split(b" ", 2)
            if len(parts) < 2 or not parts[1].isdigit():
                return "HTTP_FAIL", f"malformed status: {status_line!r}"
            status_code = int(parts[1])

            content_length: int | None = None
            while True:
                try:
                    line = await asyncio.wait_for(
                        reader.readline(), timeout=HTTP_READ_TIMEOUT,
                    )
                except asyncio.TimeoutError:
                    return "HTTP_FAIL", "timeout reading headers"
                if not line:
                    return "HTTP_FAIL", "EOF mid-headers"
                if line in (b"\r\n", b"\n"):
                    break
                low = line.lower()
                if low.startswith(b"content-length:"):
                    try:
                        content_length = int(low.split(b":", 1)[1].strip())
                    except ValueError:
                        return "HTTP_FAIL", f"bad content-length header: {line!r}"

            if status_code != 200:
                return "HTTP_FAIL", f"status {status_code}"
            if content_length is None:
                return "HTTP_FAIL", "no Content-Length header"
            if content_length <= 0 or content_length > 65536:
                return "HTTP_FAIL", f"implausible Content-Length {content_length}"

            try:
                body = await asyncio.wait_for(
                    reader.readexactly(content_length), timeout=HTTP_READ_TIMEOUT,
                )
            except asyncio.TimeoutError:
                return "HTTP_FAIL", "timeout reading body"
            except asyncio.IncompleteReadError as e:
                return "HTTP_FAIL", f"incomplete body: got {len(e.partial)}/{content_length}"

            ok, detail = _parse_unauth_response(body, nonce)
            if not ok:
                return "REPLY_FAIL", detail
            return "OK", detail
        finally:
            writer.close()
            try:
                await writer.wait_closed()
            except (ssl.SSLError, OSError):
                pass
    except Exception as e:  # last-resort guard so one bad endpoint doesn't kill the run
        return "TLS_FAIL", f"outer {type(e).__name__}: {e}"


async def main() -> int:
    print("# Probing whether Telegram production DCs accept MTProto over HTTPS on :443",
          file=sys.stderr)
    print(f"# Endpoints: {len(DCS)} (v4 + v6 per ConnectionsManager.cpp::initDatacenters)",
          file=sys.stderr)

    results: list[tuple[int, str, str, str]] = []
    for dc, ip in DCS:
        verdict, detail = await _probe_one(ip)
        marker = "PASS" if verdict == "OK" else "FAIL"
        print(f"  DC{dc} {ip:<46} {marker}  {verdict:<10} {detail}", file=sys.stderr)
        results.append((dc, ip, verdict, detail))
        await asyncio.sleep(0.5)

    ok = sum(1 for _, _, v, _ in results if v == "OK")
    total = len(results)
    print(f"# {ok}/{total} endpoints accepted HTTPS-MTProto", file=sys.stderr)

    if ok == total:
        print("https_mtproto=supported")
        return 0
    if ok == 0:
        print("https_mtproto=unsupported")
        return 1
    print("https_mtproto=partial")
    return 2


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
