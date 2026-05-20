# Mercurygram Python Gateway

Reference implementation of the Mercurygram WebPush/UnifiedPush gateway.
The production deployment uses the Rust gateway (`Gateways/Rust/`).

## Requirements

- Python 3.10+
- [uv](https://docs.astral.sh/uv/)

## Running

```bash
uv run gateway.py
```

For production, bind to a UNIX socket:

```bash
uv run gateway.py --uds /run/p2p-gateway.sock
```

All options:

```
--host HOST        Bind host (default: 127.0.0.1)
--port PORT        Bind port (default: 8000)
--uds PATH         Bind to a UNIX domain socket instead of host:port
--log-level LEVEL  Log level (default: info)
```

## Routes

| Method | Path | Description |
|---|---|---|
| `POST` | `/aesgcm?e=<url>` | WebPush (token_type=10): embeds `Encryption`/`Crypto-Key` headers into body, forwards to UP endpoint, stamps correlation cache |
| `PUT` | `/<url>` | Simple Push (token_type=4): waits 200 ms for a matching POST; suppresses if found, else forwards `version=N` body as synthetic wake-up |

## SSRF protection

- Non-http/https schemes and URLs with credentials are rejected.
- Literal IP addresses are checked before forwarding.
- Hostnames are resolved by `SafeResolver` (a custom `aiohttp.abc.AbstractResolver`) at connection time — the same resolution is used for both IP safety validation and the actual connection, eliminating the TOCTOU gap.
