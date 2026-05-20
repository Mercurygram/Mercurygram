# Mercurygram Cloudflare Worker gateway

The WebPush/UnifiedPush gateway as a Cloudflare Worker, for people who would
rather not run a server. Same routes and semantics as the Rust
[aesgcm-proxy](https://github.com/Mercurygram/aesgcm-proxy), including the
200 ms PUT/POST correlation window and the VAPID-signed FCM leg.

The Workers free tier allows 100,000 requests per day, well above what a private
gateway needs.

## Routes

| Method | Path | Description |
|---|---|---|
| `POST` | `/aesgcm?e=<url>` | WebPush (token_type=10): embeds `Encryption`/`Crypto-Key` into the body, forwards to the UnifiedPush endpoint, stamps the correlation cache |
| `PUT` | `/<url>` | Simple Push (token_type=4): waits 200 ms for a matching POST; suppresses if found, else forwards the `version=N` body as a synthetic wake-up |
| `POST` | `/fcm/<token>` | Same fold, VAPID-signed here, forwarded to FCM (for the built-in "Google FCM" distributor entry) |
| `PUT` | `/fcm/<token>` | Simple Push leg of the FCM route |

Requests are accepted only from the Telegram server ranges published at
`core.telegram.org/resources/cidr.txt` (cached for an hour). That gate replaces
the nginx ACL the self-hosted deployments put in front of the proxy.

## Deploy

Without the FCM route, the [Cloudflare
playground](https://workers.cloudflare.com/playground) is enough: paste
`worker.mjs` in, press Deploy, pick a name, and use the resulting
`https://<name>.<account>.workers.dev/` URL as the gateway in
*Settings -> Notifications and Sounds*.

The FCM route needs two secrets, so it needs `wrangler`:

```bash
node generate-vapid.mjs                # prints the key pair
wrangler secret put VAPID_PRIVATE_KEY  # the base64url private scalar
wrangler secret put VAPID_PUBLIC_KEY   # the uncompressed public point
wrangler deploy
```

`generate-vapid.mjs` needs nothing beyond the Node that `wrangler` already
requires, and prints the same format as `aesgcm-proxy --generate-vapid`, so a
key pair minted either way works in either gateway.

Put the same public key in *Settings -> Notifications and Sounds* next to the
gateway URL. The URL and the key only work as a pair: a gateway without the
matching private half signs with a key FCM does not know, and every push is
rejected. Leave both secrets unset and `/fcm` answers 503 while every other
route keeps working.

## Tests

```bash
node --test
```

Covers the CIDR allowlist, endpoint validation, the header fold, the 4096-byte
FCM clamp, the PUT/POST correlation and the VAPID signature (verified against
the advertised public key). No dependencies, Node 20 or newer.

## Known difference from the self-hosted proxies

The correlation cache is an isolate-local `Map`. Cloudflare may run two requests
of the same push event in different isolates, in which case the PUT is not
suppressed and the app gets one extra wake-up, which it handles as it does any
other. The self-hosted proxies keep one process-wide cache and never miss.
