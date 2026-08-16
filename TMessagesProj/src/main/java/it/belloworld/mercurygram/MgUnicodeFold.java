package it.belloworld.mercurygram;

import java.text.Normalizer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mercurygram: folds text written with decorated Unicode "fonts" down to plain ASCII, so that
 * searching for "Cucina italiana" finds a group actually named
 * "ℂᑌℂℐℕᗅ ℐᝨᗅℒℐᗅℕᗅ".
 *
 * Those alphabets come from two different places:
 *
 * - compatibility variants of the Latin letters (Letterlike Symbols, Mathematical Alphanumeric
 *   Symbols, fullwidth, circled, ...), which NFKD decomposition maps back on its own;
 * - unrelated scripts picked purely because a glyph looks like a Latin letter (Cherokee, Lisu,
 *   Canadian Aboriginal Syllabics, Tagbanwa, ...), which only a lookalike table can map.
 *
 * The output feeds LocaleController.getTranslitString, whose own table covers precomposed Latin
 * characters only and walks the string one UTF-16 unit at a time, so it handles neither case
 * (Mathematical Alphanumerics are not even single UTF-16 units).
 */
public class MgUnicodeFold {

    // Below this codepoint the upstream transliteration table already owns the mapping.
    private static final int FOLD_FROM = 0x0250;

    /**
     * Lookalike codepoint followed by its ASCII letter, derived from the Unicode confusables data
     * (UTS #39, https://www.unicode.org/Public/security/latest/confusables.txt) by keeping only
     * the entries that map to a single ASCII letter, that NFKD does not already handle, and that
     * the upstream transliteration table does not already map (its value wins where they differ).
     *
     * Scripts with a large living user base (Cyrillic, Greek, the Indic family, CJK, ...) are left
     * out on purpose: their letters are real text, not decoration, and folding them would break
     * both the Cyrillic transliteration the upstream table does and any search in those scripts.
     * The two Tagbanwa entries are added by hand, that block is missing from the confusables data.
     */
    private static final String LOOKALIKE_PAIRS =
            "ɑaɣyɩiϨ2Ϭ6ϭoᎠdᎡrᎢtᎥiᎩy"
            + "ᎪaᎫjᎬeᎳwᎷmᎻhᎽyᏀgᏂhᏃzᏎ4"
            + "ᏏbᏒrᏔwᏕsᏙvᏚsᏞlᏟcᏢpᏦkᏧd"
            + "Ꮾ6ᏳgᏴbᐯvᑌuᑭpᑯdᑲbᒍjᒪlᒿ2"
            + "ᕁxᕼhᕽxᖇrᖯbᖴfᗅaᗞdᗪdᗰmᗷb"
            + "ᚷxᛁlᛕkᛖmᝨtᝪoⲂbⲅrⲎhⲒlⲓi"
            + "ⲔkⲘmⲚnⲜ3ⲞoⲟoⲢpⲣpⲤcⲥcⲦt"
            + "ⲨyⲩyⲬxⲽwⳄ3Ⳋ9ⳋ9Ⳍ3ⳎpⳏpⳐl"
            + "Ⳓ6ⳓ6Ⳝ6ⴸvⴹeⵏlⵔoⵕqⵝxꓐbꓑp"
            + "ꓒdꓓdꓔtꓖgꓗkꓙjꓚcꓜzꓝfꓟmꓠn"
            + "ꓡlꓢsꓣrꓦvꓧhꓪwꓫxꓬyꓮaꓰeꓲl"
            + "ꓳoꓴuꛟvꝚ2Ꝫ3Ꝯ9ꞘfꞙfꞟuꞫ3Ʝj"
            + "ꞳxꞴbꬲeꬵfꬽoꭇrꭈrꭎuꭒuꭚyꭵi"
            + "ꮁrꮃwꮓzꮩvꮪsꮯc𐊂b𐊆e"
            + "𐊇f𐊊l𐊐x𐊒o𐊕p𐊖s"
            + "𐊗t𐊠a𐊡b𐊢c𐊥f𐊫o"
            + "𐊰m𐊱t𐊲y𐊴x𐋏h𐌁b"
            + "𐌂c𐌉l𐌑m𐌕t𐌗x𐌚8"
            + "𐐄o𐐕c𐐛l𐐠s𐐬o𐐽c"
            + "𐑈s𐒴r𐓂o𐓎u𐓒7𐓪o"
            + "𐓶u𐔓n𐔖o𐔘k𐔜c𐔝v"
            + "𐔥f𐔦l𐔧x𑜆v𑜊w𑜎w"
            + "𑜏w𑢠v𑢢f𑢣l𑢤y𑢦e"
            + "𑢩z𑢬9𑢮e𑢯4𑢲l𑢵o"
            + "𑢸u𑢻5𑢼t𑣀v𑣁s𑣂f"
            + "𑣃i𑣄z𑣆7𑣈o𑣊3𑣌9"
            + "𑣕6𑣖9𑣗o𑣘u𑣜y𖼈v"
            + "𖼊t𖼖l𖼨l𖼵r𖼺s𖼻3"
            + "𖽀a𖽂u𖽃y";

    // Memoized per-codepoint results ("" means leave untouched), pre-seeded with the lookalike
    // table. getTranslitString runs per keystroke over every dialog name and over every message
    // indexed for search; without the memoization, every real-script codepoint above FOLD_FROM
    // would pay a Normalizer round trip per call just to conclude it needs no folding.
    private static final Map<Integer, String> FOLD_CACHE = new ConcurrentHashMap<>();

    static {
        for (int i = 0; i < LOOKALIKE_PAIRS.length(); ) {
            final int cp = LOOKALIKE_PAIRS.codePointAt(i);
            i += Character.charCount(cp);
            FOLD_CACHE.put(cp, String.valueOf(LOOKALIKE_PAIRS.charAt(i)));
            i++;
        }
    }

    /**
     * Returns {@code src} with every decorated codepoint replaced by its plain lowercase ASCII
     * equivalent. Plain characters, and characters from scripts we have no mapping for, are
     * returned untouched - case included, so ASCII text comes back identical and the very same
     * instance is returned when nothing needed folding.
     */
    public static String fold(String src) {
        if (src == null) {
            return null;
        }
        StringBuilder dst = null;
        final int len = src.length();
        for (int i = 0; i < len; ) {
            final int cp = src.codePointAt(i);
            final String replacement = maybeDecorated(cp) ? replacement(cp) : "";
            if (replacement.isEmpty()) {
                if (dst != null) {
                    dst.appendCodePoint(cp);
                }
            } else {
                if (dst == null) {
                    dst = new StringBuilder(len).append(src, 0, i);
                }
                dst.append(replacement);
            }
            i += Character.charCount(cp);
        }
        return dst == null ? src : dst.toString();
    }

    // Cyrillic and the CJK Unified Ideographs are the two biggest bodies of real text the app
    // indexes, and neither block contains a lookalike entry or a compatibility decomposition,
    // so skip even the cache lookup (and its Integer boxing) for them.
    private static boolean maybeDecorated(int cp) {
        return cp >= FOLD_FROM && (cp < 0x400 || cp > 0x52f) && (cp < 0x4e00 || cp > 0x9fff);
    }

    private static String replacement(int cp) {
        String r = FOLD_CACHE.get(cp);
        if (r == null) {
            FOLD_CACHE.put(cp, r = computeReplacement(cp));
        }
        return r;
    }

    private static String computeReplacement(int cp) {
        final String single = new String(Character.toChars(cp));
        final String decomposed = Normalizer.normalize(single, Normalizer.Form.NFKD);
        if (decomposed.equals(single)) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            final char c = decomposed.charAt(i);
            // the marks NFKD split off are decoration too, drop them
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            }
            // A non-ASCII base means this is real text, not decoration: Cyrillic "ё"
            // decomposes to "е" (breaking the upstream "ё" -> "yo" transliteration),
            // Hangul syllables decompose to jamo, kana lose their voicing mark. Leave those to
            // the upstream tables, which own everything that is not plain ASCII.
            if (c >= 0x80) {
                return "";
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
