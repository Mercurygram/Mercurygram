package it.belloworld.mercurygram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.telegram.messenger.LocaleController

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
        // a ligature is one codepoint standing for two letters
        assertEquals("uffici", MgUnicodeFold.fold("uﬃci"))
    }

    @Test
    fun foldsTheWholeSuperscriptAlphabet() {
        // the superscript generator emits "¹²³" from Latin-1 and "⁰⁴..⁹" from the superscripts
        // block, so a table starting at U+0100 would fold the name only halfway
        assertEquals("gruppo 123456", MgUnicodeFold.fold("ᴳʳᵘᵖᵖᵒ ¹²³⁴⁵⁶"))
        // the Spanish and Portuguese ordinals sit in Latin-1 next to their superscript twins
        assertEquals("1a 3o", MgUnicodeFold.fold("1ª 3º"))
    }

    @Test
    fun foldsAnAlphabetNewerThanTheDevice() {
        // U+1CCD6 OUTLINED LATIN CAPITAL LETTER A, added in Unicode 16.0: the table is baked, so
        // this folds on every device instead of only on the ones whose ICU is new enough
        assertEquals("aula", MgUnicodeFold.fold("𜳖ula"))
    }

    @Test
    fun foldsTheLookalikesTheConfusablesFiltersUsedToDrop() {
        // Ɩ U+0196: below the old U+0250 cutoff, and the upstream table has no entry for it
        assertEquals("l", MgUnicodeFold.fold("Ɩ"))
        // ۷ U+06F7: the confusables data maps it to an uppercase V
        assertEquals("v", MgUnicodeFold.fold("۷"))
        // ŋ U+014B: the confusables target is "n" plus a combining mark
        assertEquals("n", MgUnicodeFold.fold("ŋ"))
        // "Ɩ" lowercases to "ɩ" U+0269, which the confusables data maps to "i" instead, so this
        // used to fold to "iana" once local search lowercased it
        assertEquals("lana", MgUnicodeFold.fold("Ɩąŋą".lowercase()))
        // the Coptic letters a generator borrows: the confusables data resolves "ⲉ" and "ⲋ" to a
        // prototype that is not ASCII either, and has no entry at all for "ⲇ"
        assertEquals("cesto", MgUnicodeFold.fold("ⲤⲈⲊⲦⲞ".lowercase()))
    }

    @Test
    fun turnsTheDecorationAroundAWordIntoASeparator() {
        // local search only matches a query at the start of a word (MessagesStorage.localSearch
        // tests startsWith and " " + query), so an emoji glued onto the word has to become a
        // space or the word behind it is unreachable
        assertEquals(" cucina italiana ", MgUnicodeFold.fold("♥️cucina❤️italiana♥️"))
        // the variation selector and the zero width joiner of an emoji sequence are dropped, so
        // they cannot sit between the separator and the word either
        assertEquals("  chat", MgUnicodeFold.fold("❤️‍🔥chat"))
    }

    @Test
    fun foldsTheLowercasedFormLocalSearchActuallyPasses() {
        // the confusables data lists the Cherokee syllabary only under its capitals, but every
        // local search lowercases first: MessagesStorage stores chat.title.toLowerCase() in the
        // name column and lowercases the query too, so this is the form that reaches fold()
        val decorated = "ᏟᎻᎪᎢ"
        assertEquals("chat", MgUnicodeFold.fold(decorated))
        assertEquals("chat", MgUnicodeFold.fold(decorated.lowercase()))
    }

    @Test
    fun readsTheWholeTableBack() {
        // the table is one packed string with no separator, so a botched regen or a stray edit
        // would shift every entry after it: check the shape the packing relies on, against the
        // entry count the generator prints
        assertEquals(2528, MgUnicodeFold.KEYS.size)
        for (i in MgUnicodeFold.KEYS.indices) {
            val cp = MgUnicodeFold.KEYS[i]
            assertTrue("U+%04X".format(cp), cp >= 0x80)
            assertTrue("U+%04X".format(cp), i == 0 || cp > MgUnicodeFold.KEYS[i - 1])
            val folded = MgUnicodeFold.fold(String(Character.toChars(cp)))
            assertTrue("U+%04X -> %s".format(cp, folded), folded.isNotEmpty() && folded.all { it.code < 0x80 })
        }
    }

    @Test
    fun foldsADecoratedNameEndToEnd() {
        // ɛ, ʂ and ı are folded by the upstream transliteration table, not here
        val translit = LocaleController.getInstance().getTranslitString("🌴 ŋơ۷ɛƖ ♥️ ıʂơƖą🌴")
        assertTrue(translit, translit.contains("novel"))
        assertTrue(translit, translit.contains("isola"))
    }

    @Test
    fun leavesTheCasePreservingTranslitAlone() {
        // onlyEnglish is the Passport name path, not a search one: it re-capitalizes what its own
        // table folded, so folding to lowercase first would give "dd" here instead of "Dd"
        assertEquals("Dd", LocaleController.getInstance().getTranslitString("Đđ", true))
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
        // only the digits of these scripts are folded, their letters are real text too
        val armenian = "քաղաք"
        assertSame(armenian, MgUnicodeFold.fold(armenian))
    }
}
