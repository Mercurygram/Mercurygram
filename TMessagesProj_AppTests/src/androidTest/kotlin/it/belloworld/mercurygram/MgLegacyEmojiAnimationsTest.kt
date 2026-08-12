package it.belloworld.mercurygram

import it.belloworld.mercurygram.emoji.MgLegacyEmojiAnimations
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.telegram.tgnet.TLRPC

class MgLegacyEmojiAnimationsTest {

    private fun stickerSet(vararg entries: Pair<String, Long>): TLRPC.TL_messages_stickerSet {
        val set = TLRPC.TL_messages_stickerSet()
        for ((emoticon, id) in entries) {
            val pack = TLRPC.TL_stickerPack()
            pack.emoticon = emoticon
            pack.documents.add(id)
            set.packs.add(pack)
            val document = TLRPC.TL_document()
            document.id = id
            set.documents.add(document)
        }
        return set
    }

    @Test
    fun resolvesDocumentsByEmoji() {
        val set = stickerSet("🍑" to 11L, "🍇" to 22L)

        assertSame(set.documents[0], MgLegacyEmojiAnimations.documentFor(set, "🍑"))
        assertSame(set.documents[1], MgLegacyEmojiAnimations.documentFor(set, "🍇"))

        assertNull(MgLegacyEmojiAnimations.documentFor(set, "🎉"))
        assertNull(MgLegacyEmojiAnimations.documentFor(TLRPC.TL_messages_stickerSet(), "🍑"))
        assertNull(MgLegacyEmojiAnimations.documentFor(null, "🍑"))
        assertNull(MgLegacyEmojiAnimations.documentFor(set, null))
    }
}
