#!/usr/bin/env python3
"""
Probe Telegram server's minimum accepted expires_in for the PFS temp-key
handshake (req_DH_params with p_q_inner_data_temp_dc).

Used to decide whether Mercurygram's "Reduce network tracking" feature can
safely shorten TEMP_AUTH_KEY_EXPIRE_TIME below the upstream default of 24h
without the server rejecting the handshake and triggering bindFailed ->
cleanUp -> potential logout.

Implementation
--------------
Uses Telethon's TCP transport, RSA, AES-IGE, and TL serialization, but
overrides the perm-key-only `do_authentication` with the temp-key flow
from Telethon PR #4618 (habcawa, partially reverted Nov 2025). The
relevant patch is inlined here so this script is self-contained — no fork
install needed.

PR reference: https://github.com/LonamiWebs/Telethon/pull/4618

Setup
-----
    python3 -m venv .venv
    .venv/bin/pip install telethon
    .venv/bin/python scripts/probe-temp-key-ttl.py

Optional env: APP_ID, APP_HASH (default to Mercurygram's bundled values).

Output
------
One line: `floor=<seconds>` for the lowest accepted expires_in, or
`floor=86400 (no shortening possible)` if every value below 24h rejected.

No login or phone number required — the probe runs against the
unauthenticated handshake endpoint only. Failed handshakes have zero
side-effect on any account.
"""

import asyncio
import os
import struct
import sys
import time
from hashlib import sha1


# Probe ladder: try shortest first, stop at first PASS.
LADDER = [60, 300, 900, 1800, 3600, 7200, 21_600, 43_200, 86_400]

# Telegram official DC2 (Amsterdam). TTL policy is identical across DCs.
DC_IP = "149.154.167.50"
DC_PORT = 443


async def probe_one(expires_in: int) -> tuple[bool, str]:
    """Run one temp-key handshake with the given expires_in. Returns (ok, msg)."""
    try:
        from telethon.network.connection.tcpfull import ConnectionTcpFull
        from telethon.network.mtprotoplainsender import MTProtoPlainSender
        from telethon.crypto import AES, AuthKey, Factorization, rsa
        from telethon.errors import SecurityError
        from telethon.extensions import BinaryReader
        from telethon.tl.functions import (
            ReqPqMultiRequest,
            ReqDHParamsRequest,
            SetClientDHParamsRequest,
        )
        from telethon.tl.types import (
            ResPQ,
            PQInnerDataTempDc,
            ServerDHParamsOk,
            ServerDHInnerData,
            ClientDHInnerData,
            DhGenOk,
            DhGenRetry,
            DhGenFail,
        )
        import logging
    except ImportError as e:
        return False, f"telethon import failed: {e} (pip install telethon)"

    class _Loggers(dict):
        def __missing__(self, k):
            v = self[k] = logging.getLogger("ttl-probe." + k.rsplit(".", 1)[-1])
            return v
    loggers = _Loggers()
    conn = ConnectionTcpFull(DC_IP, DC_PORT, dc_id=2, loggers=loggers)
    try:
        await asyncio.wait_for(conn.connect(), timeout=10)
    except Exception as e:
        return False, f"connect: {e}"

    sender = MTProtoPlainSender(conn, loggers=loggers)
    try:
        # Step 1: req_pq_multi
        nonce = int.from_bytes(os.urandom(16), "big", signed=True)
        res_pq = await asyncio.wait_for(
            sender.send(ReqPqMultiRequest(nonce=nonce)), timeout=15
        )
        if not isinstance(res_pq, ResPQ):
            return False, f"req_pq_multi returned {type(res_pq).__name__}"

        # Step 2: factor pq
        pq = int.from_bytes(res_pq.pq, "big")
        p, q = Factorization.factorize(pq)
        p, q = sorted((p, q))
        p_bytes = p.to_bytes(4, "big")
        q_bytes = q.to_bytes(4, "big")

        # Step 3: PQInnerDataTempDc with our expires_in (the key probe)
        new_nonce = int.from_bytes(os.urandom(32), "little", signed=True)
        # dc=2 for prod DC2; sign-flip the dc id if using ConnectionTcpFull
        # without media flag (irrelevant here — server only validates expires_in)
        inner = PQInnerDataTempDc(
            pq=res_pq.pq,
            p=p_bytes,
            q=q_bytes,
            nonce=res_pq.nonce,
            server_nonce=res_pq.server_nonce,
            new_nonce=new_nonce,
            dc=2,
            expires_in=expires_in,
        )
        inner_data = bytes(inner)

        # Step 4: encrypt with one of the server's RSA keys
        cipher_text = None
        target_fp = None
        for fp in res_pq.server_public_key_fingerprints:
            cipher_text = rsa.encrypt(fp, inner_data)
            if cipher_text is not None:
                target_fp = fp
                break
        if cipher_text is None:
            return False, "no matching server RSA key"

        # Step 5: req_DH_params
        server_dh = await asyncio.wait_for(
            sender.send(
                ReqDHParamsRequest(
                    nonce=res_pq.nonce,
                    server_nonce=res_pq.server_nonce,
                    p=p_bytes,
                    q=q_bytes,
                    public_key_fingerprint=target_fp,
                    encrypted_data=cipher_text,
                )
            ),
            timeout=15,
        )
        if not isinstance(server_dh, ServerDHParamsOk):
            return False, f"req_DH_params -> {type(server_dh).__name__}"

        # Step 6: derive tmp_aes_key/iv and decrypt server_DH_inner_data
        new_nonce_bytes = new_nonce.to_bytes(32, "little", signed=True)
        server_nonce_bytes = res_pq.server_nonce.to_bytes(16, "little", signed=True)
        key, iv = _gen_key_iv(new_nonce_bytes, server_nonce_bytes)
        plain = AES.decrypt_ige(server_dh.encrypted_answer, key, iv)
        # first 20 bytes are SHA1 of payload; payload starts at 20
        with BinaryReader(plain[20:]) as r:
            server_inner = r.tgread_object()
        if not isinstance(server_inner, ServerDHInnerData):
            return False, f"server_DH_inner not OK: {type(server_inner).__name__}"

        # Step 7: DH client side
        dh_prime = int.from_bytes(server_inner.dh_prime, "big")
        g = server_inner.g
        g_a = int.from_bytes(server_inner.g_a, "big")
        b = int.from_bytes(os.urandom(256), "big")
        g_b = pow(g, b, dh_prime)
        gab = pow(g_a, b, dh_prime)
        auth_key_bytes = gab.to_bytes(256, "big")

        client_inner = ClientDHInnerData(
            nonce=res_pq.nonce,
            server_nonce=res_pq.server_nonce,
            retry_id=0,
            g_b=g_b.to_bytes(256, "big"),
        )
        client_inner_bytes = bytes(client_inner)
        client_inner_hashed = sha1(client_inner_bytes).digest() + client_inner_bytes
        pad = os.urandom((-len(client_inner_hashed)) % 16)
        encrypted = AES.encrypt_ige(client_inner_hashed + pad, key, iv)

        # Step 8: set_client_DH_params
        result = await asyncio.wait_for(
            sender.send(
                SetClientDHParamsRequest(
                    nonce=res_pq.nonce,
                    server_nonce=res_pq.server_nonce,
                    encrypted_data=encrypted,
                )
            ),
            timeout=15,
        )
        if isinstance(result, DhGenOk):
            return True, "dh_gen_ok"
        if isinstance(result, DhGenRetry):
            return False, "dh_gen_retry"
        if isinstance(result, DhGenFail):
            return False, "dh_gen_fail (server rejected expires_in)"
        return False, f"unexpected: {type(result).__name__}"
    except Exception as e:
        return False, str(e)
    finally:
        try:
            await conn.disconnect()
        except Exception:
            pass


def _gen_key_iv(new_nonce: bytes, server_nonce: bytes) -> tuple[bytes, bytes]:
    """tmp_aes_key/iv derivation per MTProto 2.0 auth_key spec."""
    hash1 = sha1(new_nonce + server_nonce).digest()
    hash2 = sha1(server_nonce + new_nonce).digest()
    hash3 = sha1(new_nonce + new_nonce).digest()
    key = hash1 + hash2[:12]
    iv = hash2[12:] + hash3 + new_nonce[:4]
    return key, iv


async def main():
    print(f"# Probing Telegram DC2 ({DC_IP}) for temp-key expires_in floor", file=sys.stderr)
    print(f"# Ladder: {LADDER}", file=sys.stderr)

    floor = None
    for ttl in LADDER:
        ok, msg = await probe_one(ttl)
        verdict = "PASS" if ok else "FAIL"
        print(f"  {ttl:>6}s -> {verdict}: {msg}", file=sys.stderr)
        if ok and floor is None:
            floor = ttl
            break
        await asyncio.sleep(0.5)

    if floor is None:
        print("floor=86400 (no shortening possible)")
        return 1
    print(f"floor={floor}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
