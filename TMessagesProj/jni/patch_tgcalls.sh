#!/bin/bash
set -e

FILE=tgcalls/tgcalls/group/AudioStreamingPartPersistentDecoder.cpp

# Idempotency: the comment below is what this patch installs.
grep -q "SIGSEGVs the tgc-media" "$FILE" && exit 0

# decode() calls avcodec_send_packet() without checking _codecContext, which
# the constructor leaves null when the stream's audio codec fails to open.
# Latent upstream bug (TelegramMessenger/tgcalls master is identically
# unguarded), so the submodule is patched here instead.
patch -d tgcalls -p1 < patches/tgcalls/0001-guard-null-codec-context-in-decode.patch

echo "tgcalls: null codec context guard applied"
