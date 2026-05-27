package it.belloworld.mercurygram

import it.belloworld.mercurygram.translate.MgTranslateEntities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.telegram.tgnet.TLRPC

class MgTranslateEntitiesTest {

    private fun textUrl(offset: Int, length: Int, url: String) =
        TLRPC.TL_messageEntityTextUrl().also {
            it.offset = offset
            it.length = length
            it.url = url
        }

    private fun source(text: String, vararg entities: TLRPC.MessageEntity) =
        TLRPC.TL_textWithEntities().also {
            it.text = text
            it.entities = ArrayList(entities.toList())
        }

    private fun labelOf(out: TLRPC.TL_textWithEntities, index: Int): String {
        val e = out.entities[index]
        return out.text.substring(e.offset, e.offset + e.length)
    }

    @Test
    fun keepsBothHiddenLinksAcrossTheRoundTrip() {
        val src = source(
            "70 Up: curtain falls\nArticle, Comments",
            textUrl(21, 7, "https://example.com/a"),
            textUrl(30, 8, "https://example.com/c")
        )

        val protectedText = MgTranslateEntities.protect(src)
        assertEquals("70 Up: curtain falls\n{{0}}, {{1}}", protectedText.text)
        assertFalse(protectedText.nothingToTranslate)

        val out = MgTranslateEntities.restore(protectedText, "70 Up: cala il sipario\n{{0}}, {{1}}")!!
        assertEquals("70 Up: cala il sipario\nArticle, Comments", out.text)
        assertEquals(2, out.entities.size)
        assertEquals("Article", labelOf(out, 0))
        assertEquals("Comments", labelOf(out, 1))
        assertEquals("https://example.com/a", out.entities[0].url)
        assertEquals("https://example.com/c", out.entities[1].url)
        // the source message keeps its own offsets
        assertEquals(21, src.entities[0].offset)
    }

    @Test
    fun reanchorsRunsTheEngineReordered() {
        val src = source("see Article then Comments", textUrl(4, 7, "a"), textUrl(17, 8, "c"))
        val protectedText = MgTranslateEntities.protect(src)

        val out = MgTranslateEntities.restore(protectedText, "prima {{1}} poi {{0}}")!!
        assertEquals("prima Comments poi Article", out.text)
        assertEquals("Comments", labelOf(out, 0))
        assertEquals("c", out.entities[0].url)
        assertEquals("Article", labelOf(out, 1))
        assertEquals("a", out.entities[1].url)
    }

    @Test
    fun givesUpWhenTheEngineAteOrDuplicatedASentinel() {
        val src = source("see Article then Comments", textUrl(4, 7, "a"), textUrl(17, 8, "c"))
        val protectedText = MgTranslateEntities.protect(src)

        assertNull(MgTranslateEntities.restore(protectedText, "vedi {{0}} poi"))
        assertNull(MgTranslateEntities.restore(protectedText, "vedi {{0}} poi {{1}} e {{1}}"))
    }

    @Test
    fun reportsNothingToTranslateForALinkOnlyMessage() {
        val src = source("Article, Comments", textUrl(0, 7, "a"), textUrl(9, 8, "c"))
        val protectedText = MgTranslateEntities.protect(src)

        assertEquals("{{0}}, {{1}}", protectedText.text)
        assertTrue(protectedText.nothingToTranslate)
        assertEquals("Article, Comments", protectedText.unchanged().text)
        assertEquals(2, protectedText.unchanged().entities.size)
    }

    @Test
    fun protectsOnlyTheOutermostRunOfOverlappingEntities() {
        val bold = TLRPC.TL_messageEntityBold().also { it.offset = 4; it.length = 7 }
        val inner = textUrl(4, 3, "inner")
        val src = source("see Article now", textUrl(4, 7, "outer"), inner, bold)

        val protectedText = MgTranslateEntities.protect(src)
        assertEquals("see {{0}} now", protectedText.text)

        val out = MgTranslateEntities.restore(protectedText, "vedi {{0}} ora")!!
        assertEquals("vedi Article ora", out.text)
        assertEquals(1, out.entities.size)
        assertEquals("outer", out.entities[0].url)
    }

    @Test
    fun keepsUtf16OffsetsWithASurrogatePairBeforeTheLink() {
        // "🚀" is one code point but two UTF-16 code units
        val src = source("🚀 Article", textUrl(3, 7, "https://example.com/a"))
        val protectedText = MgTranslateEntities.protect(src)
        assertEquals("🚀 {{0}}", protectedText.text)

        val out = MgTranslateEntities.restore(protectedText, "🚀 {{0}}")!!
        assertEquals("🚀 Article", out.text)
        assertEquals(3, out.entities[0].offset)
        assertEquals(7, out.entities[0].length)
        assertEquals("Article", labelOf(out, 0))
    }

    @Test
    fun leavesTextWithoutLinkEntitiesUntouched() {
        val bold = TLRPC.TL_messageEntityBold().also { it.offset = 0; it.length = 3 }
        val protectedText = MgTranslateEntities.protect(source("abc def", bold))

        assertEquals("abc def", protectedText.text)
        assertFalse(protectedText.nothingToTranslate)
        val out = MgTranslateEntities.restore(protectedText, "ghi jkl")!!
        assertEquals("ghi jkl", out.text)
        assertTrue(out.entities.isEmpty())
    }
}
