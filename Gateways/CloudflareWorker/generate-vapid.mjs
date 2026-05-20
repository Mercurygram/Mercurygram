#!/usr/bin/env node
// SPDX-License-Identifier: GPL-2.0-or-later

// Mints a VAPID key pair in the format both gateways expect, using nothing but
// the Node that wrangler already needs. Same output as
// `aesgcm-proxy --generate-vapid`, for people who do not run the Rust gateway.
//
//   node generate-vapid.mjs
//
// The private key is the base64url raw 32-byte P-256 scalar (the JWK "d"), the
// public key the base64url uncompressed point that Play Services is handed.

const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, [
  "sign",
  "verify",
]);

const { d } = await crypto.subtle.exportKey("jwk", pair.privateKey);
const point = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey));

console.log(`VAPID_PRIVATE_KEY=${d}`);
console.log(`VAPID_PUBLIC_KEY=${Buffer.from(point).toString("base64url")}`);
console.log();
console.log("Keep the private key secret: it goes in the gateway only.");
console.log("Paste the public key into Settings, Notifications and Sounds.");
