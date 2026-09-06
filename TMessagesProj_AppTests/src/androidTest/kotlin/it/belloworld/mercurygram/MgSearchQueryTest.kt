package it.belloworld.mercurygram

import it.belloworld.mercurygram.search.MgSearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.telegram.tgnet.TLRPC
import java.util.Calendar

class MgSearchQueryTest {

    private fun midnight(year: Int, month: Int, day: Int): Int {
        val calendar = Calendar.getInstance()
        calendar.set(year, month - 1, day, 0, 0, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return (calendar.timeInMillis / 1000).toInt()
    }

    @Test
    fun plainTextPassesThrough() {
        val parsed = MgSearchQuery.parse("just some words")
        assertEquals("just some words", parsed.q)
        assertNull(parsed.filter)
        assertNull(parsed.fromUsername)
        assertEquals(0, parsed.minDate)
        assertEquals(0, parsed.maxDate)
    }

    @Test
    fun typeMapsToTheMatchingFilter() {
        assertEquals(TLRPC.TL_inputMessagesFilterPhotos::class.java, MgSearchQuery.parse("type:photo").filter?.javaClass)
        assertEquals(TLRPC.TL_inputMessagesFilterVideo::class.java, MgSearchQuery.parse("type:video").filter?.javaClass)
        assertEquals(TLRPC.TL_inputMessagesFilterVoice::class.java, MgSearchQuery.parse("type:voice").filter?.javaClass)
        assertEquals(TLRPC.TL_inputMessagesFilterRoundVideo::class.java, MgSearchQuery.parse("type:round").filter?.javaClass)
        assertEquals(TLRPC.TL_inputMessagesFilterMusic::class.java, MgSearchQuery.parse("type:music").filter?.javaClass)
        assertEquals(TLRPC.TL_inputMessagesFilterGif::class.java, MgSearchQuery.parse("type:gif").filter?.javaClass)
        assertEquals(TLRPC.TL_inputMessagesFilterDocument::class.java, MgSearchQuery.parse("type:file").filter?.javaClass)
        assertEquals(TLRPC.TL_inputMessagesFilterDocument::class.java, MgSearchQuery.parse("type:doc").filter?.javaClass)
        assertEquals(TLRPC.TL_inputMessagesFilterUrl::class.java, MgSearchQuery.parse("type:link").filter?.javaClass)
        assertEquals(TLRPC.TL_inputMessagesFilterContacts::class.java, MgSearchQuery.parse("type:contact").filter?.javaClass)
        assertEquals(TLRPC.TL_inputMessagesFilterGeo::class.java, MgSearchQuery.parse("type:location").filter?.javaClass)
        assertEquals(TLRPC.TL_inputMessagesFilterPoll::class.java, MgSearchQuery.parse("type:poll").filter?.javaClass)
        assertEquals(TLRPC.TL_inputMessagesFilterMyMentions::class.java, MgSearchQuery.parse("type:mention").filter?.javaClass)
        assertEquals(TLRPC.TL_inputMessagesFilterPinned::class.java, MgSearchQuery.parse("type:pinned").filter?.javaClass)
    }

    @Test
    fun operatorsAreStrippedFromTheText() {
        val parsed = MgSearchQuery.parse("cake from:alice type:photo after:2026-01-01")
        assertEquals("cake", parsed.q)
        assertEquals("alice", parsed.fromUsername)
        assertEquals(TLRPC.TL_inputMessagesFilterPhotos::class.java, parsed.filter?.javaClass)
        assertEquals(midnight(2026, 1, 1), parsed.minDate)
    }

    @Test
    fun fromStripsTheAtSign() {
        assertEquals("alice_bob", MgSearchQuery.parse("from:@alice_bob").fromUsername)
    }

    @Test
    fun keysAndKeywordValuesAreCaseInsensitive() {
        val parsed = MgSearchQuery.parse("FROM:Alice TYPE:Photo")
        assertEquals("Alice", parsed.fromUsername)
        assertEquals(TLRPC.TL_inputMessagesFilterPhotos::class.java, parsed.filter?.javaClass)
    }

    @Test
    fun beforeAndAfterUseLocalMidnight() {
        val parsed = MgSearchQuery.parse("after:2026-01-01 before:2026-06-15")
        assertEquals(midnight(2026, 1, 1), parsed.minDate)
        assertEquals(midnight(2026, 6, 15), parsed.maxDate)
    }

    @Test
    fun dateIsAOneDayRange() {
        val parsed = MgSearchQuery.parse("date:2026-03-10")
        assertEquals(midnight(2026, 3, 10), parsed.minDate)
        assertEquals(midnight(2026, 3, 11), parsed.maxDate)
    }

    @Test
    fun todayAndYesterdayResolve() {
        val today = Calendar.getInstance()
        val expectedToday = midnight(today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH))
        assertEquals(expectedToday, MgSearchQuery.parse("after:today").minDate)
        val yesterday = Calendar.getInstance()
        yesterday.add(Calendar.DAY_OF_MONTH, -1)
        val expectedYesterday = midnight(yesterday.get(Calendar.YEAR), yesterday.get(Calendar.MONTH) + 1, yesterday.get(Calendar.DAY_OF_MONTH))
        val parsed = MgSearchQuery.parse("date:yesterday")
        assertEquals(expectedYesterday, parsed.minDate)
        assertEquals(expectedToday, parsed.maxDate)
    }

    @Test
    fun invalidTokensStayLiteral() {
        assertEquals("type:banana", MgSearchQuery.parse("type:banana").q)
        assertEquals("before:2024-13-99", MgSearchQuery.parse("before:2024-13-99").q)
        assertEquals("from:@ from:a..b", MgSearchQuery.parse("from:@ from:a..b").q)
        assertEquals("size:>5MB", MgSearchQuery.parse("size:>5MB").q)
        assertNull(MgSearchQuery.parse("type:banana").filter)
    }

    @Test
    fun duplicateKeyLastWins() {
        val parsed = MgSearchQuery.parse("type:photo type:video")
        assertEquals(TLRPC.TL_inputMessagesFilterVideo::class.java, parsed.filter?.javaClass)
        assertEquals("", parsed.q)
    }

    @Test
    fun hashtagQueriesPassThroughUntouched() {
        val parsed = MgSearchQuery.parse("#tag from:alice")
        assertEquals("#tag from:alice", parsed.q)
        assertNull(parsed.fromUsername)
        val cashtag = MgSearchQuery.parse("\$TON type:photo")
        assertEquals("\$TON type:photo", cashtag.q)
        assertNull(cashtag.filter)
    }

    @Test
    fun whitespaceCollapses() {
        assertEquals("two words", MgSearchQuery.parse("  two   type:photo   words  ").q)
    }
}
