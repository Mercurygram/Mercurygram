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
    }
}
