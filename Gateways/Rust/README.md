# Mercurygram Rust Gateway

Production implementation of the Mercurygram WebPush/UnifiedPush gateway
(the Python gateway in `Gateways/Python/` is a reference/alternative).

## Building

```bash
cargo build --release
```

The default `native-tls` feature links the system OpenSSL. For a fully static
binary with no OpenSSL dependency, build with `rustls` instead:

```bash
cargo build --release --no-default-features --features rustls
```

## Running

Without socket activation it binds `127.0.0.1:8001`. Override with the
`LISTEN_ADDR` env var:

```bash
LISTEN_ADDR=0.0.0.0:8001 ./target/release/p2p-gateway
```

### systemd (production)

`p2p-gateway.service` + `p2p-gateway.socket` provide a hardened, socket-activated
deployment (the socket is passed via `listenfd`, so `LISTEN_ADDR` is unused).
Install the binary to `/usr/local/bin/p2p-gateway` and enable the socket.

### Container

`Containerfile` is a multi-stage build that compiles with the `rustls` feature
(static musl binary, no OpenSSL) and runs it on a minimal `alpine` image with
`ca-certificates`. It sets `LISTEN_ADDR=0.0.0.0:8001` and exposes `8001`.

```bash
podman build -f Containerfile -t p2p-gateway .
podman run --rm -p 8001:8001 p2p-gateway
```

CI publishes `linux/amd64` + `linux/arm64` images to
`ghcr.io/mercurygram/p2p-gateway` (tags `latest` and `sha-<short>`) on every
push touching this directory, on a weekly cron (base-image / crate security
updates), and on manual dispatch.

## Routes

| Method | Path | Description |
|---|---|---|
| `POST` | `/aesgcm?e=<url>` | WebPush: embeds `Encryption`/`Crypto-Key` headers into body, forwards to UP endpoint, stamps correlation cache |
| `PUT` | `/<url>` | Simple Push (token_type=4): waits 200 ms for a matching POST; suppresses if found, else forwards the body as a synthetic wake-up |

## SSRF protection

- Non-http/https schemes and URLs with credentials are rejected.
- Literal IP addresses are checked before forwarding (private, loopback, CGNAT,
  link-local, ULA, NAT64 and similar ranges are blocked).
- Hostnames are filtered by `SafeResolver` (a custom reqwest DNS resolver) at
  connection time — a single resolution feeds both the safety check and the
  connection, eliminating the TOCTOU gap. Redirects are disabled.
