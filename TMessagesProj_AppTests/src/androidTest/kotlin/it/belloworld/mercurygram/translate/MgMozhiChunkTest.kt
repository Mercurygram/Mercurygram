package it.belloworld.mercurygram.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the request splitter that keeps every Mozhi request under the backend
 * engine's source-text limit. The transport itself is not exercised here.
 */
class MgMozhiChunkTest {

    private val max = MgMozhiClient.MG_ALT_MAX_CHARS_PER_REQUEST

    /** 4096 characters of Persian words separated by spaces and newlines. */
    private fun persianText(): String {
        val words = listOf("سلام", "دنیا", "این", "یک", "پیام", "طولانی", "است")
        val sb = StringBuilder()
        var i = 0
        while (sb.length < 4096) {
            sb.append(words[i % words.size])
            sb.append(if (i % 11 == 10) "\n" else " ")
            i++
        }
        return sb.substring(0, 4096)
    }

    @Test
    fun longTextSplitsUnderTheLimitAndRejoins() {
        val text = persianText()
        val parts = MgMozhiClient.chunk(text, max)
        assertTrue("expected more than one chunk, got ${parts.size}", parts.size > 1)
        for (part in parts) {
            assertTrue("chunk of ${part.length} chars exceeds $max", part.length <= max)
            assertTrue("empty chunk", part.isNotEmpty())
        }
        assertEquals(text, parts.joinToString(""))
    }

    @Test
    fun splitsOnWhitespaceWhenTheWindowHasAny() {
        val text = persianText()
        val parts = MgMozhiClient.chunk(text, max)
        // The last chunk ends at the end of the text, not at a boundary.
        for (part in parts.dropLast(1)) {
            val last = part[part.length - 1]
            assertTrue("chunk ends on '$last', not whitespace", last == ' ' || last == '\n')
        }
    }

    @Test
    fun textWithoutWhitespaceIsCutHard() {
        val text = "ا".repeat(max * 2 + 7)
        val parts = MgMozhiClient.chunk(text, max)
        assertEquals(3, parts.size)
        assertEquals(max, parts[0].length)
        assertEquals(max, parts[1].length)
        assertEquals(7, parts[2].length)
        assertEquals(text, parts.joinToString(""))
    }

    @Test
    fun shortTextStaysOneChunk() {
        val text = "سلام دنیا"
        assertEquals(listOf(text), MgMozhiClient.chunk(text, max))
    }

    @Test
    fun emptyTextProducesNoChunks() {
        assertTrue(MgMozhiClient.chunk("", max).isEmpty())
    }
}
