#!/usr/bin/env -S uv run
# SPDX-License-Identifier: GPL-2.0-or-later

# /// script
# requires-python = ">=3.10"
# dependencies = [
#   "aiohttp>=3.9",
#   "uvloop>=0.21.0",
# ]
# ///

# Usage: uv run gateway.py [--host HOST] [--port PORT] [--uds PATH] [--log-level LEVEL]


import asyncio
import ipaddress
import logging
import socket
import time
import urllib.parse

import aiohttp
import aiohttp.abc
import aiohttp.web
import uvloop

uvloop.install()

logger = logging.getLogger(__name__)

# ── security helpers ──────────────────────────────────────────────────────────

# Pre-built network objects for ranges not covered by ipaddress built-ins.
_CGNAT       = ipaddress.ip_network('100.64.0.0/10')   # RFC 6598
_NAT64_WK    = ipaddress.ip_network('64:ff9b::/96')    # NAT64 well-known (RFC 6052)
_NAT64_LOCAL = ipaddress.ip_network('64:ff9b:1::/48')  # NAT64 local
_IPV4_COMPAT = ipaddress.ip_network('::/96')           # IPv4-compatible (deprecated)
_IPV4_MAPPED = ipaddress.ip_network('::ffff:0:0/96')   # IPv4-mapped


def _is_ip_safe(ip: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    """Return True if ip is a public, routable address."""
    if (ip.is_private or ip.is_loopback or ip.is_link_local
            or ip.is_reserved or ip.is_unspecified or ip.is_multicast):
        return False
    if isinstance(ip, ipaddress.IPv4Address):
        return ip not in _CGNAT
    else:
        return not any(ip in net for net in (_NAT64_WK, _NAT64_LOCAL, _IPV4_COMPAT, _IPV4_MAPPED))


def _validate_endpoint(url: str) -> bool:
    """Validate URL structure and literal IPs.

    Rejects:
    - non-http/https schemes
    - URLs with credentials (user:pass@host SSRF bypass)
    - literal IP addresses that are non-public

    Hostname → IP safety is enforced by SafeResolver at connection time,
    eliminating the TOCTOU gap that existed with the old two-resolution approach.
    """
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in ("http", "https"):
        logger.warning("SECURITY reject %r: scheme %r is not http/https", url, parsed.scheme)
        return False
    if parsed.username or parsed.password:
        logger.warning("SECURITY reject %r: URL contains credentials", url)
        return False
    hostname = parsed.hostname
    if not hostname:
        logger.warning("SECURITY reject %r: no host", url)
        return False
    # Literal IP addresses are not passed through SafeResolver — check directly.
    try:
        ip = ipaddress.ip_address(hostname)
        if not _is_ip_safe(ip):
            logger.warning("SECURITY reject %r: literal IP %s is non-public", url, ip)
            return False
    except ValueError:
        pass  # hostname — SafeResolver handles IP safety at connect time
    return True


class SafeResolver(aiohttp.abc.AbstractResolver):
    """Custom aiohttp DNS resolver that enforces _is_ip_safe at resolve time.

    Since aiohttp uses this resolver for the actual connection, validation and
    connection share a single DNS resolution — no TOCTOU gap.
    """

    async def resolve(
        self, host: str, port: int = 0, family: int = socket.AF_UNSPEC
    ) -> list[dict]:
        try:
            infos = await asyncio.get_running_loop().getaddrinfo(
                host, port, family=family, type=socket.SOCK_STREAM,
            )
        except OSError as e:
            logger.warning("SECURITY block %r: DNS resolution failed (%s)", host, e)
            raise

        safe = []
        blocked = []
        for af, _socktype, proto, _canonname, sockaddr in infos:
            raw_ip = sockaddr[0]
            try:
                ip = ipaddress.ip_address(raw_ip)
            except ValueError:
                logger.warning("SECURITY block %r: invalid IP %r in DNS result", host, raw_ip)
                blocked.append(raw_ip)
                continue
            if _is_ip_safe(ip):
                safe.append({
                    "hostname": host,
                    "host": raw_ip,
                    "port": sockaddr[1],
                    "family": af,
                    "proto": proto,
                    "flags": socket.AI_NUMERICHOST | socket.AI_NUMERICSERV,
                })
            else:
                logger.warning("SECURITY block %r: resolved to non-public IP %s", host, ip)
                blocked.append(str(ip))

        if not safe:
            raise OSError(
                f"endpoint {host!r} resolves only to non-public addresses: {', '.join(blocked)}"
            )
        return safe

    async def close(self) -> None:
        pass


# ── correlation cache ─────────────────────────────────────────────────────────

# Records the monotonic time of the last successful POST /aesgcm for each endpoint.
# Key: endpoint URL string. Value: time.monotonic() at forward success.
# The PUT handler checks this to suppress duplicate Simple Push wake-ups when an
# encrypted payload (POST /aesgcm) already delivered the notification.
_recent_posts: dict[str, float] = {}

# PUT handler waits this long for a matching POST /aesgcm before sending a synthetic wake-up.
_CORRELATION_WINDOW = 0.200  # seconds

# Cache entries older than this are considered stale and evicted.
_EVICT_AFTER = 2.0  # seconds


def _recent_post_within_window(key: str) -> bool:
    """Return True if a POST /aesgcm was successfully forwarded for this endpoint
    within the last _EVICT_AFTER seconds."""
    ts = _recent_posts.get(key)
    return ts is not None and (time.monotonic() - ts) < _EVICT_AFTER


async def _cleanup_recent_posts() -> None:
    """Periodically evict stale entries from the correlation cache."""
    while True:
        await asyncio.sleep(5)
        now = time.monotonic()
        stale = [k for k, ts in list(_recent_posts.items()) if (now - ts) >= _EVICT_AFTER]
        for k in stale:
            _recent_posts.pop(k, None)


# ── application lifecycle ─────────────────────────────────────────────────────

_DEFAULT_HEADERS = {
    "TTL": "2592000",
    "Urgency": "high",
    "Content-Encoding": "aes128gcm",
}


async def on_startup(app: aiohttp.web.Application) -> None:
    connector = aiohttp.TCPConnector(resolver=SafeResolver())
    app["http_session"] = aiohttp.ClientSession(
        connector=connector,
        headers=_DEFAULT_HEADERS,
        timeout=aiohttp.ClientTimeout(total=15, connect=5),
    )
    app["cleanup_task"] = asyncio.create_task(_cleanup_recent_posts())


async def on_cleanup(app: aiohttp.web.Application) -> None:
    app["cleanup_task"].cancel()
    try:
        await app["cleanup_task"]
    except asyncio.CancelledError:
        pass
    await app["http_session"].close()


# ── route handlers ────────────────────────────────────────────────────────────

async def topic(request: aiohttp.web.Request) -> aiohttp.web.Response:
    """PUT /<url-encoded-endpoint> — Simple Push (token_type=4) handler.

    Telegram sends a PUT to this route for every push event when token_type=4 is registered.
    For regular messages, a matching POST /aesgcm also arrives within _CORRELATION_WINDOW —
    in that case the encrypted payload already woke the app, so we suppress the PUT.
    For encrypted (secret) chats, only the PUT arrives (no content to encrypt); after
    waiting the full window we forward the original body as a synthetic wake-up so the app
    connects and fetches pending messages via MTProto.
    """
    path = request.match_info["path"]

    if not _validate_endpoint(path):
        return aiohttp.web.Response(status=403)

    body = await request.read()

    # Fast path: POST already arrived before we even started waiting.
    if _recent_post_within_window(path):
        logger.info("put_proxy correlated (pre-wait) for %r", path)
        return aiohttp.web.Response(status=200)

    # Wait for the correlation window, then check again.
    await asyncio.sleep(_CORRELATION_WINDOW)

    if _recent_post_within_window(path):
        logger.info("put_proxy correlated (post-wait) for %r", path)
        return aiohttp.web.Response(status=200)

    # No matching POST arrived — forward the original Simple Push body (typically "version=N")
    # as a wake-up signal. The app receives it, aesgcm decryption fails on the non-encrypted
    # payload, and it falls back to the MTProto wake-up path to retrieve pending messages.
    logger.info("put_proxy synthetic wake-up for %r", path)
    session: aiohttp.ClientSession = request.app["http_session"]
    try:
        async with session.post(path, data=body, allow_redirects=False) as resp:
            content = await resp.read()
            status = resp.status
    except aiohttp.ClientError as e:
        logger.error("forward error %r: %s", path, e)
        return aiohttp.web.Response(status=500)

    if 200 <= status < 300:
        logger.info("put_proxy forwarded to %r: status=%s", path, status)
    else:
        logger.warning("put_proxy upstream rejected %r: status=%s", path, status)

    return aiohttp.web.Response(body=content, status=status)


async def aesgcm(request: aiohttp.web.Request) -> aiohttp.web.Response:
    """POST /aesgcm?e=<url-encoded-endpoint>

    Serializes WebPush aesgcm headers (Encryption, Crypto-Key) into the body before
    forwarding to the UnifiedPush endpoint. UP distributors strip HTTP headers, so
    this embeds them in the body for client-side decryption.

    Body format sent to the UP endpoint:
      aesgcm\\n
      Encryption: <value>\\n
      Crypto-Key: <value>\\n
      <original binary ciphertext>

    On success, records the endpoint in the correlation cache so the PUT handler
    (Simple Push, token_type=4) knows this event was already delivered as encrypted.
    """
    endpoint = request.rel_url.query.get("e")
    if not endpoint:
        logger.warning("aesgcm request missing ?e= parameter")
        return aiohttp.web.Response(status=400)

    if not _validate_endpoint(endpoint):
        return aiohttp.web.Response(status=403)

    body = await request.read()

    encryption = request.headers.get("encryption", "")
    crypto_key = request.headers.get("crypto-key", "")
    if not encryption or not crypto_key:
        logger.warning(
            "aesgcm request to %r missing Encryption/Crypto-Key headers — decryption will fail on device",
            endpoint,
        )

    # Prepend headers as text lines before the binary ciphertext
    new_body = (
        b"aesgcm\n"
        + f"Encryption: {encryption}\n".encode()
        + f"Crypto-Key: {crypto_key}\n".encode()
        + body
    )

    session: aiohttp.ClientSession = request.app["http_session"]
    try:
        async with session.post(endpoint, data=new_body, allow_redirects=False) as resp:
            content = await resp.read()
            status = resp.status
            location = resp.headers.get("Location", endpoint)
    except aiohttp.ClientError as e:
        logger.error("forward error %r: %s", endpoint, e)
        return aiohttp.web.Response(status=500)

    # WebPush requires 201 Created on success; normalize any 2xx from the
    # upstream distributor (e.g. ntfy returns 200) to avoid Telegram backoff.
    # Include a Location header as required by the spec.
    if 200 <= status < 300:
        logger.info("aesgcm forwarded to %r: status=%s", endpoint, status)
        # Record that an encrypted payload was successfully delivered for this endpoint.
        # The PUT handler checks this to suppress duplicate Simple Push wake-ups.
        _recent_posts[endpoint] = time.monotonic()
        return aiohttp.web.Response(status=201, headers={"location": location})

    logger.warning("aesgcm upstream rejected %r: status=%s", endpoint, status)
    return aiohttp.web.Response(body=content, status=status)


app = aiohttp.web.Application()
app.on_startup.append(on_startup)
app.on_cleanup.append(on_cleanup)
app.router.add_post("/aesgcm", aesgcm)
app.router.add_put("/{path:.+}", topic)


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Mercurygram WebPush/UnifiedPush gateway")
    parser.add_argument("--host", default="127.0.0.1", help="Bind host (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=8000, help="Bind port (default: 8000)")
    parser.add_argument("--uds", default=None, help="Bind to a UNIX domain socket path instead of host:port")
    parser.add_argument("--log-level", default="info", help="Log level (default: info)")
    args = parser.parse_args()

    logging.basicConfig(level=args.log_level.upper())

    aiohttp.web.run_app(app, host=args.host, port=args.port, path=args.uds)
