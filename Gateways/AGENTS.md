# Gateways — WebPush Gateway

Self-hosted WebPush gateway servers. **Rust (`Gateways/Rust/`) is production; Python (`Gateways/Python/`) is a reference/alternative.**

## Endpoints

**POST `/aesgcm?e=<url-encoded-endpoint>`**
Receives Telegram's WebPush request (`Encryption` and `Crypto-Key` headers), embeds them into the body as `aesgcm\nEncryption: ...\nCrypto-Key: ...\n<ciphertext>`, forwards to the UP distributor endpoint, and stamps a correlation cache entry on success.

**PUT `/<url-encoded-endpoint>`**
Simple Push handler. Checks correlation cache for a recent POST to the same endpoint:
- Found within 2 s → suppresses PUT (POST already delivered the notification)
- Not found after 200 ms wait → forwards the real `version=N` body as a synthetic wake-up

The correlation window (200 ms wait, 2 s cache age) prevents duplicate wake-ups for regular messages while ensuring secret chat pushes still reach the app.

## SSRF protection (Rust)

`validate_endpoint()` rejects non-http/https schemes and literal private IPs. `SafeResolver` (custom reqwest DNS resolver) filters resolved IPs at connect time — single resolution, no TOCTOU gap. Redirects disabled.

## ntfy.sh deployment note

The public gateway at `https://p2p.belloworld.it/` short-circuits `ntfy.sh` endpoints with an immediate 201 at the nginx level (not in gateway code). The gateway runs on OCI infrastructure whose IP is repeatedly blocked by `ntfy.sh` due to connection volume. Self-hosted `ntfy` instances are unaffected.
