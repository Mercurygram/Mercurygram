#!/bin/bash
set -e

# Idempotency: check the tracked header that the patch modifies.
# git submodule update resets tracked files (like aes.h) but leaves untracked
# ones (like ssl/aes_ige.c), so checking only aes.h gives a reliable answer.
grep -qw AES_ige_encrypt boringssl/include/openssl/aes.h && exit

# Remove any leftover aes_ige.c from an interrupted or partial previous run
# before applying the patch (patch would fail if the file already exists).
rm -f boringssl/ssl/aes_ige.c

# Apply AES-IGE mode patch (needed for Telegram's MTProto protocol).
# Base BoringSSL does not include AES-IGE; this adds ssl/aes_ige.c
# and registers it in gen/sources.cmake.
patch -d boringssl -p1 < patches/boringssl/0001-add-AES-IGE-mode.patch

echo "BoringSSL: AES-IGE patch applied"
