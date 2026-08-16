package it.belloworld.mercurygram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MgUnicodeFoldTest {

    @Test
    fun foldsADecoratedGroupTitle() {
        assertEquals("cucina italiana", MgUnicodeFold.fold("ℂᑌℂℐℕᗅ ℐᝨᗅℒℐᗅℕᗅ"))
    }

    @Test
    fun foldsTheMathematicalAlphabetsOutsideTheBasicPlane() {
        // bold, double-struck, fraktur and monospace all live above U+FFFF
        assertEquals("abc", MgUnicodeFold.fold("𝐀𝕓𝔠"))
        assertEquals("gruppo", MgUnicodeFold.fold("𝙶𝚛𝚞𝚙𝚙𝚘"))
    }

    @Test
    fun foldsFullwidthCircledAndLetterlike() {
        assertEquals("chat", MgUnicodeFold.fold("ｃｈａｔ"))
        assertEquals("chat", MgUnicodeFold.fold("ⓒⓗⓐⓣ"))
        assertEquals("e", MgUnicodeFold.fold("ℰ"))
    }

    @Test
    fun foldsTheLookalikesUpstreamLeavesOut() {
        // "Ɩ" (U+0196) and "ŋ" (U+014B) sit in Latin Extended, which the upstream table only
        // covers in patches, and "۷" is a Persian digit standing in for a "v". What is left over
        // is exactly what upstream transliterates, so the search key ends up "love island".
        assertEquals("🌴 lơvɛ ♥️ ıʂląnɖ🌴", MgUnicodeFold.fold("🌴 Ɩơ۷ɛ ♥️ ıʂƖąŋɖ🌴"))
    }

    @Test
    fun keepsPlainTextIdenticalAndAllocationFree() {
        val plain = "Cucina Italiana 2024"
        assertSame(plain, MgUnicodeFold.fold(plain))
    }

    @Test
    fun leavesRealScriptsAlone() {
        // folding these would break both Cyrillic transliteration and search in those scripts
        val cyrillic = "утро"
        assertSame(cyrillic, MgUnicodeFold.fold(cyrillic))
        val cjk = "中文群"
        assertSame(cjk, MgUnicodeFold.fold(cjk))
        // NFKD decomposes these, but the base is not ASCII: folding would turn "ё" into "е"
        // (breaking the upstream "ё" -> "yo" transliteration), Hangul syllables into jamo,
        // and strip the kana voicing mark
        val yo = "алёна"
        assertSame(yo, MgUnicodeFold.fold(yo))
        val hangul = "한국"
        assertSame(hangul, MgUnicodeFold.fold(hangul))
        val kana = "がく"
        assertSame(kana, MgUnicodeFold.fold(kana))
        // the table reaches below U+0250 now, so guard the scripts whose letters are confusable
        // with Latin ones by shape alone
        val arabic = "مرحبا"
        assertSame(arabic, MgUnicodeFold.fold(arabic))
        val hebrew = "שלום"
        assertSame(hebrew, MgUnicodeFold.fold(hebrew))
        val greek = "καλημέρα"
        assertSame(greek, MgUnicodeFold.fold(greek))
        // the anusvara is a confusable of "o" but appears in ordinary words, and "〇" is the
        // Chinese numeral zero of "二〇二五年", not a decorated "o"
        val telugu = "సందేశం"
        assertSame(telugu, MgUnicodeFold.fold(telugu))
        val year = "二〇二五年"
        assertSame(year, MgUnicodeFold.fold(year))
    }
}
