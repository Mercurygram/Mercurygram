// SPDX-License-Identifier: GPL-2.0-or-later
//
// Run with: node --test

import assert from "node:assert/strict";
import test from "node:test";

import worker from "./worker.mjs";

import {
  clampFcmPayload,
  decodePath,
  fcmSendUrl,
  foldAesgcmBody,
  isIpInCidrs,
  parseCidrs,
  signVapidJwt,
  validateEndpoint,
} from "./worker.mjs";

const FCM = "https://fcm.googleapis.com/fcm/send/";
const FCM_AUDIENCE_URL = "https://fcm.googleapis.com";

test("CIDR allowlist matches v4 and v6 Telegram ranges", () => {
  const nets = parseCidrs("# comment\n91.108.4.0/22\n149.154.160.0/20\n2001:b28:f23d::/48\n");
  assert.ok(isIpInCidrs("91.108.7.255", nets));
  assert.ok(isIpInCidrs("149.154.167.51", nets));
  assert.ok(!isIpInCidrs("91.108.8.0", nets));
  assert.ok(!isIpInCidrs("8.8.8.8", nets));
  assert.ok(isIpInCidrs("2001:b28:f23d:f001::e", nets));
  assert.ok(!isIpInCidrs("2001:b28:f23e::1", nets));
  assert.ok(!isIpInCidrs("not an ip", nets));
});

test("endpoints outside http(s), or carrying credentials, are refused", () => {
  assert.equal(validateEndpoint("https://ntfy.example/topic"), "https://ntfy.example/topic");
  assert.equal(validateEndpoint("file:///etc/passwd"), null);
  assert.equal(validateEndpoint("https://user:pass@evil.example/x"), null);
  assert.equal(validateEndpoint("not a url"), null);
});

test("fcm tokens stay under the send prefix", () => {
  assert.equal(fcmSendUrl("e5fNOEHUN4E:APA91bEKcyXKGhix"), `${FCM}e5fNOEHUN4E:APA91bEKcyXKGhix`);
  assert.equal(fcmSendUrl(""), null);
  assert.equal(fcmSendUrl(".."), null);
  assert.equal(fcmSendUrl("../../etc/passwd"), null);
  assert.equal(fcmSendUrl("//evil.example/x"), null);
  assert.equal(fcmSendUrl("https://evil.example/x"), null);
});

test("the fold prefixes the aesgcm headers to the ciphertext", () => {
  const folded = foldAesgcmBody("salt=abc", "dh=def", new Uint8Array([1, 2, 3]));
  const text = new TextDecoder().decode(folded);
  assert.equal(text, "aesgcm\nEncryption: salt=abc\nCrypto-Key: dh=def\n\x01\x02\x03");
});

test("an oversized fcm payload becomes a bare wake-up", () => {
  const big = new Uint8Array(4097);
  assert.equal(clampFcmPayload(`${FCM}token`, big).length, 0);
  assert.equal(clampFcmPayload(`${FCM}token`, new Uint8Array(4096)).length, 4096);
  // Only FCM has the limit; a distributor endpoint is left alone.
  assert.equal(clampFcmPayload("https://ntfy.example/topic", big).length, 4097);
});

test("the VAPID header verifies against the advertised public key", async () => {
  const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, [
    "sign",
    "verify",
  ]);
  const raw = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey));
  const publicKey = Buffer.from(raw).toString("base64url");

  const exp = Math.floor(Date.now() / 1000) + 12 * 60 * 60;
  const header = await signVapidJwt(pair.privateKey, publicKey, exp);

  const [t, k] = header.replace("vapid ", "").split(",");
  assert.equal(k, `k=${publicKey}`);
  const token = t.replace("t=", "");
  const [headerB64, claimsB64, signatureB64] = token.split(".");
  assert.deepEqual(JSON.parse(Buffer.from(headerB64, "base64url").toString()), {
    typ: "JWT",
    alg: "ES256",
  });
  assert.deepEqual(JSON.parse(Buffer.from(claimsB64, "base64url").toString()), {
    aud: "https://fcm.googleapis.com",
    exp,
    sub: "https://mercurygram.org/",
  });
  assert.ok(
    await crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      pair.publicKey,
      Buffer.from(signatureB64, "base64url"),
      new TextEncoder().encode(`${headerB64}.${claimsB64}`),
    ),
  );
});

// -- routing ------------------------------------------------------------------

const CIDR_TXT = "91.108.4.0/22\n149.154.160.0/20\n";
const TELEGRAM_IP = "149.154.167.51";

/** Runs `fn` with a stubbed global fetch, collecting every forwarded request. */
async function withUpstream(fn, respond = () => new Response("ok", { status: 200 })) {
  const real = globalThis.fetch;
  const seen = [];
  globalThis.fetch = async (input, init = {}) => {
    const url = typeof input === "string" ? input : input.url;
    if (url.startsWith("https://core.telegram.org/")) return new Response(CIDR_TXT);
    seen.push({ url, init });
    return respond(url, init);
  };
  try {
    return await fn(seen);
  } finally {
    globalThis.fetch = real;
  }
}

const call = (method, path, { ip = TELEGRAM_IP, headers = {}, body } = {}) =>
  worker.fetch(
    new Request(`https://gateway.example${path}`, {
      method,
      headers: ip === null ? headers : { "CF-Connecting-IP": ip, ...headers },
      body,
    }),
    {},
  );

test("only Telegram IPs get past the allowlist", async () => {
  await withUpstream(async () => {
    assert.equal((await call("PUT", "/https%3A%2F%2Fntfy.example%2Ftopic", { ip: null })).status, 400);
    assert.equal((await call("PUT", "/https%3A%2F%2Fntfy.example%2Ftopic", { ip: "8.8.8.8" })).status, 403);
    assert.equal((await call("GET", "/")).status, 405);
  });
});

test("POST /aesgcm folds the headers and normalizes the status to 201", async () => {
  await withUpstream(async (seen) => {
    const response = await call("POST", "/aesgcm?e=https%3A%2F%2Fntfy.example%2Ftopic", {
      headers: { Encryption: "salt=abc", "Crypto-Key": "dh=def" },
      body: "ciphertext",
    });
    assert.equal(response.status, 201);
    assert.equal(response.headers.get("Location"), "https://ntfy.example/topic");
    assert.equal(seen.length, 1);
    assert.equal(seen[0].url, "https://ntfy.example/topic");
    assert.equal(seen[0].init.headers["Content-Encoding"], "aes128gcm");
    assert.equal(
      new TextDecoder().decode(seen[0].init.body),
      "aesgcm\nEncryption: salt=abc\nCrypto-Key: dh=def\nciphertext",
    );

    // The PUT right after it is a duplicate wake-up and never reaches upstream.
    const put = await call("PUT", "/https%3A%2F%2Fntfy.example%2Ftopic", { body: "version=1" });
    assert.equal(put.status, 200);
    assert.equal(seen.length, 1);
  });
  assert.equal((await call("POST", "/aesgcm")).status, 400);
});

test("an uncorrelated PUT is forwarded as a wake-up", async () => {
  await withUpstream(async (seen) => {
    const response = await call("PUT", "/https%3A%2F%2Fntfy.example%2Fsecret", { body: "version=7" });
    assert.equal(response.status, 200);
    assert.equal(seen.length, 1);
    assert.equal(seen[0].url, "https://ntfy.example/secret");
    assert.equal(new TextDecoder().decode(seen[0].init.body), "version=7");
  });
});

test("/fcm answers 503 until the VAPID pair is configured", async () => {
  await withUpstream(async (seen) => {
    // Fresh module instance: the signed-push test below leaves a key and a JWT in
    // the worker's isolate-lifetime globals, so sharing this one would make the
    // assertion depend on test order.
    const fresh = (await import("./worker.mjs?unconfigured")).default;
    const response = await fresh.fetch(
      new Request("https://gateway.example/fcm/tok:en", {
        method: "POST",
        headers: { "CF-Connecting-IP": TELEGRAM_IP },
      }),
      {},
    );
    assert.equal(response.status, 503);
    assert.equal(seen.length, 0);
  });
});

test("a malformed percent escape is refused, not a 500", async () => {
  await withUpstream(async (seen) => {
    assert.equal((await call("PUT", "/%zz")).status, 403);
    assert.equal(seen.length, 0);
  });
  // The /fcm leg refuses on the VAPID check before it looks at the token, so the
  // decode guard is asserted on the pieces it feeds.
  assert.equal(decodePath("%e0%a4%a"), null);
  assert.equal(fcmSendUrl(decodePath("%e0%a4%a")), null);
  assert.equal(validateEndpoint(decodePath("%zz")), null);
});

test("a CIDR line with no usable prefix length is dropped, not read as /0", () => {
  assert.deepEqual(parseCidrs("1.2.3.4/\n5.6.7.8/x\n9.10.11.12/33\n"), []);
  assert.ok(!isIpInCidrs("8.8.8.8", parseCidrs("1.2.3.4/\n")));
});

test("POST /fcm signs the push with the configured VAPID pair", async () => {
  const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, [
    "sign",
    "verify",
  ]);
  const jwk = await crypto.subtle.exportKey("jwk", pair.privateKey);
  const raw = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey));
  const env = {
    VAPID_PRIVATE_KEY: jwk.d,
    VAPID_PUBLIC_KEY: Buffer.from(raw).toString("base64url"),
  };

  await withUpstream(async (seen) => {
    const response = await worker.fetch(
      new Request("https://gateway.example/fcm/e5fNOEHUN4E:APA91bEK", {
        method: "POST",
        headers: {
          "CF-Connecting-IP": TELEGRAM_IP,
          Encryption: "salt=abc",
          "Crypto-Key": "dh=def",
        },
        body: "ciphertext",
      }),
      env,
    );
    assert.equal(response.status, 201);
    assert.equal(seen.length, 1);
    assert.equal(seen[0].url, `${FCM}e5fNOEHUN4E:APA91bEK`);

    const auth = seen[0].init.headers.Authorization;
    const [t, k] = auth.replace("vapid ", "").split(",");
    assert.equal(k, `k=${env.VAPID_PUBLIC_KEY}`);
    const [headerB64, claimsB64, signatureB64] = t.replace("t=", "").split(".");
    assert.ok(
      await crypto.subtle.verify(
        { name: "ECDSA", hash: "SHA-256" },
        pair.publicKey,
        Buffer.from(signatureB64, "base64url"),
        new TextEncoder().encode(`${headerB64}.${claimsB64}`),
      ),
    );
    assert.equal(JSON.parse(Buffer.from(claimsB64, "base64url").toString()).aud, FCM_AUDIENCE_URL);
  });
});
