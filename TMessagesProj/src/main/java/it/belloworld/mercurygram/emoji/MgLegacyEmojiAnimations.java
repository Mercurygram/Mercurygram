package it.belloworld.mercurygram.emoji;

import android.text.TextUtils;

import org.telegram.messenger.MediaDataController;
import org.telegram.tgnet.TLRPC;

/**
 * Restores the animated 🍑 emoji Telegram dropped server-side in 2022-2023.
 *
 * Only the big-emoji document was removed, nothing was ever blocked client-side, so
 * putting a document back makes the message an animated emoji again and the tap replay
 * comes with it, no extra code. The eggplant is not covered here: Telegram put it back
 * in its own animated emoji set, and the server document always wins over this fallback
 * anyway.
 *
 * The animation is not bundled: it lives in a Telegram sticker pack, preloaded by
 * MediaDataController next to the placeholder pack and cached in the stickers_dice
 * SQLite table, so after the first fetch everything works offline.
 */
public class MgLegacyEmojiAnimations {

    /** 🍑 big emoji document. */
    public static final String PACK = "MercurygramLegacyEmoji";

    /** U+1F351 PEACH. */
    private static final String PEACH = "\uD83C\uDF51";

    private MgLegacyEmojiAnimations() {
    }

    /**
     * Tail of {@link MediaDataController#getEmojiAnimatedSticker}: reached only when the
     * server ships no animation for {@code emoji}, which is already variation-selector
     * stripped there.
     *
     * Known ceiling: a chat opened before the pack lands (first launch after install, or
     * a cold start with no connectivity) renders plain text until it is reopened. The
     * diceStickersDidLoad broadcast only refreshes dice cells, it does not rebuild
     * message objects.
     */
    public static TLRPC.Document legacyDocument(int account, String emoji) {
        // Only the emoji this feature is about. The pack is remote data resolved by
        // short name, and Telegram frees a short name when its pack is deleted, so
        // without this an unrelated pack landing on that name would take over the big
        // emoji rendering of every emoji it happens to contain.
        if (!PEACH.equals(emoji)) {
            return null;
        }
        return documentFor(MediaDataController.getInstance(account).getStickerSetByEmojiOrName(PACK), emoji);
    }

    public static TLRPC.Document documentFor(TLRPC.TL_messages_stickerSet set, String emoji) {
        if (set == null || emoji == null) {
            return null;
        }
        for (int a = 0; a < set.packs.size(); a++) {
            TLRPC.TL_stickerPack pack = set.packs.get(a);
            if (pack.documents.isEmpty() || !TextUtils.equals(pack.emoticon, emoji)) {
                continue;
            }
            long id = pack.documents.get(0);
            for (int b = 0; b < set.documents.size(); b++) {
                if (set.documents.get(b).id == id) {
                    return set.documents.get(b);
                }
            }
        }
        return null;
    }
}
