// SPDX-License-Identifier: GPL-2.0-or-later

// Cloudflare Worker build of the Mercurygram WebPush gateway.
//
// Same routes and semantics as the Rust aesgcm-proxy
// (https://github.com/Mercurygram/aesgcm-proxy):
//
//   POST /aesgcm?e=<url>   fold the Encryption/Crypto-Key headers into the body
//   PUT  /<url>            Simple Push leg, correlated against a recent POST
//   POST /fcm/<token>      same fold, VAPID-signed, forwarded to FCM
//   PUT  /fcm/<token>      Simple Push leg of the FCM route
//
// Requests are accepted only from the Telegram server ranges published at
// core.telegram.org/resources/cidr.txt; that gate replaces the nginx ACL the
// self-hosted deployments put in front of the proxy.

const TELEGRAM_CIDR_URL = "https://core.telegram.org/resources/cidr.txt";
const CIDR_CACHE_TTL_MS = 60 * 60 * 1000; // 1 hour

// The PUT leg waits this long for a matching POST before sending a wake-up.
const CORRELATION_WINDOW_MS = 200;
// A POST seen this recently is still evidence the event was already delivered:
// under load a POST can land just over the window before its PUT.
const LOOKUP_AGE_MS = 2000;

const FCM_SEND_PREFIX = "https://fcm.googleapis.com/fcm/send/";
// FCM answers 400 above this, before it even looks at the authorization header.
const MAX_FCM_PAYLOAD = 4096;

const FCM_AUDIENCE = "https://fcm.googleapis.com";
// RFC 8292 requires a contact; FCM does not act on it, but it must be present.
const VAPID_SUBJECT = "https://mercurygram.org/";
// FCM rejects a JWT valid for more than 24h. 12h leaves room for clock skew.
const JWT_LIFETIME_SECS = 12 * 60 * 60;
// Re-sign this long before expiry so an in-flight push never carries a stale JWT.
const JWT_REFRESH_MARGIN_SECS = 60 * 60;
// base64url of {"typ":"JWT","alg":"ES256"}, fixed for every JWT this mints.
const JWT_HEADER = "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9";

// Upstream forward budget, matching the self-hosted proxies' 15 s client timeout.
const FORWARD_TIMEOUT_MS = 15000;

const FORWARD_HEADERS = {
  TTL: "2592000",
  Urgency: "high",
  "Content-Encoding": "aes128gcm",
};

// -- correlation cache --------------------------------------------------------

// Endpoint URL -> Date.now() of the last successful POST.
// Isolate-local, so a colo split can let one extra wake-up through; a Durable
// Object would close that, at the cost of a paid feature and a round trip per
// push. Worth revisiting only if duplicate wake-ups show up in practice.
const recentPosts = new Map();

function recentPostWithinWindow(endpoint) {
  const ts = recentPosts.get(endpoint);
  return ts !== undefined && Date.now() - ts < LOOKUP_AGE_MS;
}

function recordPost(endpoint) {
  const now = Date.now();
  for (const [key, ts] of recentPosts) {
    if (now - ts >= LOOKUP_AGE_MS) recentPosts.delete(key);
  }
  recentPosts.set(endpoint, now);
}

// -- Telegram CIDR allowlist --------------------------------------------------

let cidrCache = null; // { expires, nets }

export function parseCidrs(text) {
  const nets = [];
  for (const line of text.split("\n")) {
    const entry = line.trim();
    if (!entry || entry.startsWith("#")) continue;
    const [addr, len, extra] = entry.split("/");
    if (extra !== undefined) continue;
    const value = ipToBigInt(addr);
    if (value === null) continue;
    const width = addr.includes(":") ? 128 : 32;
    // A non-numeric length (a truncated "1.2.3.4/" line, say) must not fall
    // through to Number("") === 0, which would build a /0 net matching every
    // address and quietly turn the allowlist off.
    if (len !== undefined && !/^\d{1,3}$/.test(len)) continue;
    const bits = len === undefined ? width : Number(len);
    if (bits > width) continue;
    const mask = ((1n << BigInt(width)) - 1n) ^ ((1n << BigInt(width - bits)) - 1n);
    nets.push({ width, mask, base: value & mask });
  }
  return nets;
}

export function ipToBigInt(ip) {
  if (!ip.includes(":")) {
    const parts = ip.split(".");
    if (parts.length !== 4) return null;
    let value = 0n;
    for (const part of parts) {
      const byte = Number(part);
      if (!/^\d{1,3}$/.test(part) || byte > 255) return null;
      value = (value << 8n) | BigInt(byte);
    }
    return value;
  }

  // IPv6, possibly with "::" and a trailing dotted-quad.
  const [head, tail, extra] = ip.split("::");
  if (extra !== undefined) return null;
  const expand = (chunk) => {
    if (!chunk) return [];
    const groups = [];
    for (const piece of chunk.split(":")) {
      if (piece.includes(".")) {
        const v4 = ipToBigInt(piece);
        if (v4 === null) return null;
        groups.push(Number(v4 >> 16n), Number(v4 & 0xffffn));
        continue;
      }
      if (!/^[0-9a-fA-F]{1,4}$/.test(piece)) return null;
      groups.push(parseInt(piece, 16));
    }
    return groups;
  };
  const left = expand(head);
  const right = tail === undefined ? [] : expand(tail);
  if (left === null || right === null) return null;
  const fill = 8 - left.length - right.length;
  if (tail === undefined ? fill !== 0 : fill < 0) return null;
  const groups = [...left, ...new Array(fill).fill(0), ...right];
  let value = 0n;
  for (const group of groups) value = (value << 16n) | BigInt(group);
  return value;
}

export function isIpInCidrs(ip, nets) {
  const value = ipToBigInt(ip);
  if (value === null) return false;
  const width = ip.includes(":") ? 128 : 32;
  return nets.some((net) => net.width === width && (value & net.mask) === net.base);
}

async function getTelegramCidrs() {
  if (cidrCache && cidrCache.expires > Date.now()) return cidrCache.nets;
  try {
    const response = await fetch(TELEGRAM_CIDR_URL, {
      cf: { cacheTtl: CIDR_CACHE_TTL_MS / 1000, cacheEverything: true },
    });
    if (!response.ok) throw new Error(`status=${response.status}`);
    const nets = parseCidrs(await response.text());
    if (nets.length === 0) throw new Error("empty CIDR list");
    cidrCache = { expires: Date.now() + CIDR_CACHE_TTL_MS, nets };
  } catch (e) {
    // Serve the stale list rather than locking every push out over a blip.
    console.warn(`CIDR refresh failed (${e}), keeping the cached list`);
    if (!cidrCache) return null;
    cidrCache.expires = Date.now() + CIDR_CACHE_TTL_MS;
  }
  return cidrCache.nets;
}

// -- VAPID --------------------------------------------------------------------

let vapidKey = null; // CryptoKey
let vapidJwt = null; // { header, exp }

function b64urlEncode(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function b64urlDecode(value) {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(padded + "=".repeat((4 - (padded.length % 4)) % 4));
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

// The key pair `aesgcm-proxy --generate-vapid` prints: VAPID_PRIVATE_KEY is the
// base64url raw 32-byte P-256 scalar, VAPID_PUBLIC_KEY the uncompressed point
// the app hands to Play Services. Both halves are needed because WebCrypto
// imports a private JWK, and deriving the point from the scalar in JS would mean
// implementing P-256 by hand.
async function getVapidKey(env) {
  if (vapidKey) return vapidKey;
  const priv = (env.VAPID_PRIVATE_KEY ?? "").trim();
  const pub = (env.VAPID_PUBLIC_KEY ?? "").trim();
  if (!priv || !pub) return null;
  // A typo in either secret must leave /fcm on the documented 503 rather than
  // letting the decode/import throw out of the fetch handler, which would answer
  // 500 to every push for the life of the deployment.
  try {
    const point = b64urlDecode(pub);
    if (point.length !== 65 || point[0] !== 0x04) {
      console.error("VAPID_PUBLIC_KEY is not an uncompressed P-256 point");
      return null;
    }
    vapidKey = await crypto.subtle.importKey(
      "jwk",
      {
        kty: "EC",
        crv: "P-256",
        d: priv.replace(/=+$/, ""),
        x: b64urlEncode(point.slice(1, 33)),
        y: b64urlEncode(point.slice(33, 65)),
        ext: false,
      },
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["sign"],
    );
  } catch (e) {
    console.error(`VAPID key pair rejected: ${e}`);
    return null;
  }
  return vapidKey;
}

export async function signVapidJwt(key, publicKey, exp) {
  const claims = b64urlEncode(
    new TextEncoder().encode(
      JSON.stringify({ aud: FCM_AUDIENCE, exp, sub: VAPID_SUBJECT }),
    ),
  );
  const signingInput = `${JWT_HEADER}.${claims}`;
  const signature = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    new TextEncoder().encode(signingInput),
  );
  // WebCrypto emits the raw r||s pair, which is already the JWS form.
  return `vapid t=${signingInput}.${b64urlEncode(new Uint8Array(signature))},k=${publicKey}`;
}

async function vapidAuthorization(env) {
  // Configured-check first: a cached JWT must never outlive the key that minted
  // it, or an unconfigured gateway would keep signing with a stale header.
  const key = await getVapidKey(env);
  if (!key) return null;
  const now = Math.floor(Date.now() / 1000);
  if (vapidJwt && vapidJwt.exp > now + JWT_REFRESH_MARGIN_SECS) return vapidJwt.header;
  const exp = now + JWT_LIFETIME_SECS;
  // RFC 8292 k= is unpadded base64url; a key pasted with "=" padding would be
  // rejected by FCM on every push.
  const publicKey = env.VAPID_PUBLIC_KEY.trim().replace(/=+$/, "");
  vapidJwt = { header: await signVapidJwt(key, publicKey, exp), exp };
  return vapidJwt.header;
}

// -- endpoints ----------------------------------------------------------------

// decodeURIComponent throws on a malformed escape ("%zz"); a request that cannot
// be decoded is a 403 like any other unusable endpoint, not an uncaught 500 that
// makes Telegram back the push channel off.
export function decodePath(raw) {
  try {
    return decodeURIComponent(raw);
  } catch {
    return null;
  }
}

export function validateEndpoint(raw) {
  if (raw === null) return null;
  let url;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  // Workers' fetch has no route to RFC1918 or loopback, so the resolver-level
  // SSRF guard the self-hosted proxies need has nothing to do here.
  if (url.protocol !== "http:" && url.protocol !== "https:") return null;
  if (url.username || url.password) return null;
  return url.toString();
}

// An FCM token is an opaque `<id>:<rest>`; anything that could climb out of the
// send prefix is refused rather than escaped.
export function fcmSendUrl(token) {
  if (token === null) return null;
  if (!/^[A-Za-z0-9_.:-]+$/.test(token) || token === "." || token === "..") return null;
  return FCM_SEND_PREFIX + token;
}

function redact(endpoint) {
  return endpoint.startsWith(FCM_SEND_PREFIX)
    ? `${FCM_SEND_PREFIX}${endpoint.slice(FCM_SEND_PREFIX.length, FCM_SEND_PREFIX.length + 6)}...`
    : endpoint;
}

export function foldAesgcmBody(encryption, cryptoKey, body) {
  const prefix = new TextEncoder().encode(
    `aesgcm\nEncryption: ${encryption}\nCrypto-Key: ${cryptoKey}\n`,
  );
  const folded = new Uint8Array(prefix.length + body.length);
  folded.set(prefix, 0);
  folded.set(body, prefix.length);
  return folded;
}

// FCM hard-rejects a body over 4096 bytes, so a payload that only the fold
// pushed over the line would be lost outright. Wake the app with an empty push
// instead: it falls back to fetching over MTProto, like the secret-chat path.
export function clampFcmPayload(endpoint, body) {
  if (!endpoint.startsWith(FCM_SEND_PREFIX) || body.length <= MAX_FCM_PAYLOAD) return body;
  console.warn(
    `fcm payload for ${redact(endpoint)} is ${body.length} bytes (limit ${MAX_FCM_PAYLOAD}), sending a bare wake-up instead`,
  );
  return new Uint8Array(0);
}

function forward(endpoint, body, auth) {
  const headers = { ...FORWARD_HEADERS };
  if (auth) headers.Authorization = auth;
  return fetch(endpoint, {
    method: "POST",
    headers,
    body,
    redirect: "manual",
    // Same ceiling as the self-hosted proxies: a distributor that accepts the
    // connection and never answers must not hold the invocation open.
    signal: AbortSignal.timeout(FORWARD_TIMEOUT_MS),
  });
}

/** Fold-and-forward leg: normalizes 2xx to 201 and stamps the correlation cache. */
async function postAndRecord(endpoint, body, auth) {
  let upstream;
  try {
    upstream = await forward(endpoint, body, auth);
  } catch (e) {
    console.error(`forward error ${redact(endpoint)}: ${e}`);
    return new Response(null, { status: 500 });
  }

  if (!upstream.ok) {
    console.warn(`upstream rejected ${redact(endpoint)}: status=${upstream.status}`);
    return new Response(upstream.body, { status: upstream.status });
  }

  recordPost(endpoint);
  // Normalize any 2xx to 201 Created per WebPush to avoid Telegram backoff. A
  // 201 must carry a Location (RFC 8030 section 5).
  return new Response(null, {
    status: 201,
    headers: { Location: upstream.headers.get("Location") || endpoint },
  });
}

/**
 * Simple Push (token_type=4) leg.
 *
 * For regular messages a matching POST also arrives within the correlation
 * window: the encrypted payload already woke the app, so the PUT is dropped.
 * For secret chats only the PUT arrives, and after the full wait its body
 * ("version=N") is forwarded as a wake-up: decryption fails on the device and
 * the app falls back to fetching over MTProto.
 */
async function simplePush(endpoint, body, auth) {
  if (recentPostWithinWindow(endpoint)) {
    console.log(`put correlated (pre-wait) for ${redact(endpoint)}`);
    return new Response(null, { status: 200 });
  }

  await new Promise((resolve) => setTimeout(resolve, CORRELATION_WINDOW_MS));

  if (recentPostWithinWindow(endpoint)) {
    console.log(`put correlated (post-wait) for ${redact(endpoint)}`);
    return new Response(null, { status: 200 });
  }

  console.log(`put synthetic wake-up for ${redact(endpoint)}`);
  let upstream;
  try {
    upstream = await forward(endpoint, body, auth);
  } catch (e) {
    console.error(`forward error ${redact(endpoint)}: ${e}`);
    return new Response(null, { status: 500 });
  }
  if (!upstream.ok) {
    console.warn(`put synthetic upstream rejected ${redact(endpoint)}: status=${upstream.status}`);
  }
  return new Response(upstream.body, { status: upstream.status });
}

/** Preconditions shared by both /fcm legs. */
async function fcmTarget(env, token) {
  const auth = await vapidAuthorization(env);
  if (!auth) {
    console.warn("fcm request refused: VAPID key pair is not configured");
    return { error: new Response(null, { status: 503 }) };
  }
  const endpoint = fcmSendUrl(token);
  if (!endpoint) {
    console.warn(`SECURITY reject fcm token: does not resolve under ${FCM_SEND_PREFIX}`);
    return { error: new Response(null, { status: 403 }) };
  }
  return { endpoint, auth };
}

export default {
  async fetch(request, env) {
    const clientIp = request.headers.get("CF-Connecting-IP");
    if (!clientIp) return new Response("Missing client IP", { status: 400 });

    const cidrs = await getTelegramCidrs();
    if (!cidrs) return new Response(null, { status: 503 });
    if (!isIpInCidrs(clientIp, cidrs)) return new Response(null, { status: 403 });

    const url = new URL(request.url);

    if (url.pathname.startsWith("/fcm/")) {
      if (request.method !== "POST" && request.method !== "PUT") {
        return new Response(null, { status: 405 });
      }
      // Refuse before the correlation sleep: an unconfigured proxy or a bogus
      // token must not hold the request for the full window.
      const { endpoint, auth, error } = await fcmTarget(
        env,
        decodePath(url.pathname.slice("/fcm/".length)),
      );
      if (error) return error;

      const body = new Uint8Array(await request.arrayBuffer());
      if (request.method === "PUT") {
        return simplePush(endpoint, clampFcmPayload(endpoint, body), auth);
      }
      const folded = foldAesgcmBody(
        request.headers.get("Encryption") ?? "",
        request.headers.get("Crypto-Key") ?? "",
        body,
      );
      return postAndRecord(endpoint, clampFcmPayload(endpoint, folded), auth);
    }

    if (request.method === "POST" && url.pathname === "/aesgcm") {
      const raw = url.searchParams.get("e");
      if (!raw) return new Response("Missing query parameter: e", { status: 400 });
      const endpoint = validateEndpoint(raw);
      if (!endpoint) return new Response(null, { status: 403 });

      const encryption = request.headers.get("Encryption") ?? "";
      const cryptoKey = request.headers.get("Crypto-Key") ?? "";
      if (!encryption || !cryptoKey) {
        console.warn(
          `aesgcm request to ${endpoint} missing Encryption/Crypto-Key headers, decryption will fail on device`,
        );
      }
      const body = new Uint8Array(await request.arrayBuffer());
      return postAndRecord(endpoint, foldAesgcmBody(encryption, cryptoKey, body));
    }

    if (request.method === "PUT") {
      const endpoint = validateEndpoint(decodePath(url.pathname.slice(1)));
      if (!endpoint) return new Response(null, { status: 403 });
      return simplePush(endpoint, new Uint8Array(await request.arrayBuffer()));
    }

    return new Response(null, { status: 405 });
  },
};
