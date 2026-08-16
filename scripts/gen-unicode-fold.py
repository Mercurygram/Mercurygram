#!/usr/bin/env python3
"""Regenerate the FOLD_PAIRS table of MgUnicodeFold.

The table maps every decorated codepoint to the plain ASCII it stands for, so
local search finds a chat renamed with a font generator. It has two halves:

- the compatibility variants of the Latin letters (Letterlike Symbols,
  Mathematical Alphanumerics, fullwidth, circled, ...), taken from the NFKD
  decomposition this interpreter's unicodedata knows;
- the codepoints borrowed from another script purely because their glyph looks
  like a Latin letter ("ŋơ۷ɛƖ" for "novel"), taken from the Unicode confusables
  data (UTS #39).

Both halves are baked. Deriving the first one on the device instead would
resolve it against the device's own ICU, so the same APK would fold a name one
way on a recent Android and another way on an older one - the OUTLINED LATIN
alphabet (U+1CCD6.., Unicode 16.0) is exactly the kind of thing a font
generator emits and an older phone has never heard of. The price is that
coverage is frozen at the Unicode version of the run that produced the table:
rerun this when a new release adds another decorative alphabet.

Run it by hand and paste the output into MgUnicodeFold.java; nothing regenerates
the table at build time, the F-Droid server must build the committed bytes.

    python3 scripts/gen-unicode-fold.py
    python3 scripts/gen-unicode-fold.py --input confusables.txt
"""

import argparse
import re
import sys
import unicodedata
import urllib.request

CONFUSABLES_URL = "https://www.unicode.org/Public/security/latest/confusables.txt"

LOCALE_CONTROLLER = (
    "TMessagesProj/src/main/java/org/telegram/messenger/LocaleController.java"
)

# A compatibility decomposition is decoration wherever it sits, so the NFKD half
# starts right above ASCII: the superscript alphabet a font generator emits mixes
# "⁰⁴..⁹" with "¹²³", and the ordinals "ª" and "º" sit next to "ᵃ" and "ᵒ", so a
# cutoff at U+0100 would fold half of one alphabet and leave the other half.
NFKD_FROM = 0x0080

# The lookalike half starts higher: below this codepoint the upstream
# transliteration table owns the Latin letters, and the confusables data would
# otherwise map living-language letters by glyph shape alone ("þ" to "p").
LOOKALIKE_FROM = 0x0100

# Scripts with a large living user base: their letters are real text, not
# decoration, and folding them would break both the Cyrillic transliteration the
# upstream table does and any search written in those scripts.
EXCLUDED_SCRIPTS = (
    (0x0370, 0x03FF),  # Greek
    (0x0400, 0x052F),  # Cyrillic
    (0x0900, 0x0DFF),  # Devanagari .. Sinhala
    (0x1100, 0x11FF),  # Hangul Jamo
    (0x1F00, 0x1FFF),  # Greek Extended
    (0x2DE0, 0x2DFF),  # Cyrillic Extended-A
    (0x3040, 0x30FF),  # Hiragana, Katakana
    (0x4E00, 0x9FFF),  # CJK Unified Ideographs
    (0xA640, 0xA69F),  # Cyrillic Extended-B
    (0xAC00, 0xD7AF),  # Hangul Syllables
)

# Same reasoning, but only their letters are real text. The digits and the
# punctuation of these scripts are exactly what the font generators borrow
# ("۷" for "v"), and folding them is symmetric: getTranslitString runs over the
# search query as well as over the stored name, so a search written in one of
# these scripts still matches itself.
LETTERS_ONLY_SCRIPTS = (
    (0x0530, 0x058F),  # Armenian
    (0x0590, 0x06FF),  # Hebrew, Arabic
    (0x0700, 0x074F),  # Syriac
    (0x0780, 0x07BF),  # Thaana
    (0x07C0, 0x07FF),  # NKo
    (0x0E00, 0x0EFF),  # Thai, Lao
    (0x1000, 0x109F),  # Myanmar
    (0x10A0, 0x10FF),  # Georgian
    (0x1200, 0x137F),  # Ethiopic
    (0x1780, 0x17FF),  # Khmer
    (0x1C90, 0x1CBF),  # Georgian Extended
    (0xFB50, 0xFDFF),  # Arabic Presentation Forms-A
    (0xFE70, 0xFEFF),  # Arabic Presentation Forms-B
    (0x11480, 0x114DF),  # Tirhuta
)

# Entries the confusables data does not give us. The Tagbanwa block is simply missing from it; the
# others are the cases where it is right about the glyph and wrong about what a font generator does
# with it, so they are applied last and override it. "Ɩ" (U+0196) is mapped to "l" by the data, but
# local search lowercases before it folds, which turns it into "ɩ" (U+0269) - and that one the data
# maps to "i", so "Ɩơŋɖơŋ" came out as "iondon" instead of "london". The three Coptic
# letters are used for their shape ("ⲤⲈⲊⲦⲞ" for "cesto"): the data resolves two of them to a
# prototype that is itself not ASCII ("ⲉ" to "ꞓ", "ⲋ" to "ς") and has no entry at all for "ⲇ".
HAND_ADDED = {
    0x0196: "l",
    0x0269: "l",
    0x1768: "t",
    0x176A: "o",
    0x2C87: "a",
    0x2C89: "e",
    0x2C8B: "s",
}


def in_ranges(cp, ranges):
    return any(lo <= cp <= hi for lo, hi in ranges)


def nfkd_fold(cp):
    """The plain ASCII this codepoint decomposes to, or None to leave it alone.

    The marks NFKD splits off are decoration too and get dropped, but a base
    that is not ASCII means this is real text rather than decoration: Cyrillic
    "ё" decomposes to "е" (which would break the upstream "ё" -> "yo"
    transliteration), Hangul syllables decompose to jamo, kana lose their
    voicing mark. Those are left to the upstream tables.

    A decomposition made of nothing but marks folds to the empty string, which
    would mean "delete this character" - the halfwidth kana voicing marks
    U+FF9E/U+FF9F are the ones that matter - so it counts as no mapping. The
    lowercasing is done one character at a time, after the ASCII test, so that
    str.lower() cannot pull a non-ASCII character back in ("İ" U+0130 lowercases
    to two characters, one of them a combining mark).
    """
    single = chr(cp)
    decomposed = unicodedata.normalize("NFKD", single)
    if decomposed == single:
        return None
    folded = []
    for c in decomposed:
        if unicodedata.category(c) == "Mn":
            continue
        if ord(c) >= 0x80:
            return None
        folded.append(c.lower())
    return "".join(folded) or None


def ascii_letter(target):
    """The single ASCII letter this confusables target reduces to, or None.

    The combining marks are dropped exactly like nfkd_fold drops the ones NFKD
    splits off: they are decoration too, and keeping them would throw away
    entries such as "ŋ" -> "n" + U+0329.
    """
    kept = [c for c in target if unicodedata.category(chr(c)) != "Mn"]
    if len(kept) != 1:
        return None
    c = chr(kept[0])
    return c.lower() if c.isascii() and c.isalpha() else None


def upstream_mapped(locale_controller):
    with open(locale_controller, encoding="utf-8") as f:
        return set(re.findall(r'translitChars\.put\("(.+?)",', f.read()))


def read_confusables(path):
    if path:
        with open(path, encoding="utf-8") as f:
            return f.read()
    with urllib.request.urlopen(CONFUSABLES_URL) as response:
        return response.read().decode("utf-8")


def keep(cp, upstream):
    if cp < LOOKALIKE_FROM:
        return False
    if chr(cp) in upstream:  # the upstream mapping wins where they differ
        return False
    if nfkd_fold(cp):  # the NFKD half of the table already covers it
        return False
    if in_ranges(cp, EXCLUDED_SCRIPTS):
        return False
    if in_ranges(cp, LETTERS_ONLY_SCRIPTS) and unicodedata.category(
        chr(cp)
    ).startswith("L"):
        return False
    return True


def close_over_case(table, upstream):
    """Map the case counterparts of every entry to the same ASCII letter.

    The confusables data lists a bicameral script only under the case whose
    glyph looks Latin, usually the capital: Cherokee is there as "Ꭰ" (U+13A0),
    never as "ꭰ" (U+AB70). Every local search lowercases before transliterating
    ("MessagesStorage" stores "chat.title.toLowerCase()" in the name column and
    lowercases the query too), so without the counterparts a name written with
    the Cherokee font generator reaches "fold" already lowercased and comes out
    unfolded.
    """
    for cp, letter in list(table.items()):
        for other in (chr(cp).lower(), chr(cp).upper()):
            if len(other) != 1 or ord(other) == cp or ord(other) in table:
                continue
            if keep(ord(other), upstream):
                table[ord(other)] = letter
    return table


def case_conflicts(table):
    """The pairs whose two cases fold to a different letter, worst first.

    Local search lowercases before it folds, so where the two disagree only the
    lowercase entry is reachable on that path - which is how "Ɩ" -> "l" ended up
    losing to "ɩ" -> "i" and folding a name to "iondon". The data is free to map
    the two cases of a letter to different prototypes, so this is a report, not
    an error: read it after a regen and add a HAND_ADDED entry for the ones a
    font generator actually emits.
    """
    pairs = set()
    for cp, letter in table.items():
        for other in (chr(cp).lower(), chr(cp).upper()):
            if len(other) == 1 and ord(other) != cp and table.get(ord(other), letter) != letter:
                pairs.add(tuple(sorted((cp, ord(other)))))
    return sorted(pairs)


def build_table(confusables, upstream):
    # The NFKD half is not filtered by the script ranges or by the upstream
    # transliteration table, and starts one block lower: a compatibility variant
    # is decoration whatever script it decorates, and MgUnicodeFold folds before
    # the upstream table gets to look at the text anyway (the two agree on every
    # codepoint they share). "keep" rejects everything NFKD covers, so the two
    # halves are disjoint by construction.
    nfkd = {cp: folded for cp in range(NFKD_FROM, 0x110000) if (folded := nfkd_fold(cp))}
    return nfkd | build_lookalikes(confusables, upstream)


def build_lookalikes(confusables, upstream):
    table = {}
    for line in confusables.splitlines():
        if line.startswith("#") or ";" not in line:
            continue
        columns = [c.strip() for c in line.split(";")]
        if len(columns) < 3:
            continue
        source = columns[0].split()
        if len(source) != 1:
            continue
        cp = int(source[0], 16)
        if not keep(cp, upstream):
            continue
        letter = ascii_letter([int(c, 16) for c in columns[1].split()])
        if letter:
            table[cp] = letter
    table.update(HAND_ADDED)
    return close_over_case(table, upstream)


PER_LINE = 11


def java_escape(c):
    """A character as it goes into the Java literal.

    Most of the table is left as itself, that is what makes it reviewable. The
    quote and the backslash have to be escaped to keep the literal valid, and so
    does anything invisible: a fifth of the spacing characters fold to a plain
    space, which would otherwise produce lines made of nothing but whitespace
    that any editor is free to strip.
    """
    if c in '"\\':
        return "\\" + c
    if c == " " or not c.isprintable():
        units = c.encode("utf-16-be", "surrogatepass")
        return "".join(
            "\\u%04x" % int.from_bytes(units[i : i + 2], "big")
            for i in range(0, len(units), 2)
        )
    return c


def java_literal(table):
    """The packed literal: every key followed by the ASCII it folds to.

    No separator, because the keys are all above U+00FF and the values are all
    ASCII, so an entry ends at the next character the parser cannot mistake for
    a value. MgUnicodeFold reads it back with the same rule.
    """
    pairs = ["".join(java_escape(c) for c in chr(cp) + table[cp]) for cp in sorted(table)]
    lines = ["".join(pairs[i : i + PER_LINE]) for i in range(0, len(pairs), PER_LINE)]
    out = ['            "%s"' % lines[0]]
    out += ['            + "%s"' % line for line in lines[1:]]
    return "\n".join(out) + ";"


def parse_literal(literal):
    """Read the packed literal back, the way MgUnicodeFold does.

    The unescaping stands in for javac, which resolves the \\uXXXX escapes
    before the string ever exists; the class only ever sees the characters.
    """
    packed = "".join(re.findall(r'"(.*)"', literal))
    packed = re.sub(r"\\u([0-9a-f]{4})", lambda m: chr(int(m.group(1), 16)), packed)
    packed = packed.encode("utf-16", "surrogatepass").decode("utf-16")
    packed = re.sub(r'\\(["\\])', r"\1", packed)
    table = {}
    cp = None
    for c in packed:
        if ord(c) < 0x80:
            table[cp] += c
        else:
            cp = ord(c)
            table[cp] = ""
    return table


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", help="local confusables.txt (default: download)")
    args = parser.parse_args()

    confusables = read_confusables(args.input)
    table = build_table(confusables, upstream_mapped(LOCALE_CONTROLLER))

    # The shapes the derivation used to drop, one per past bug: a source below
    # the old U+0250 cutoff, an uppercase ASCII target, a target carrying a
    # combining mark, and a lowercase counterpart the confusables data does not
    # list (search lowercases before it transliterates, so that one is the form
    # that actually reaches the fold). The last two cover the NFKD half: the
    # outlined alphabet, new enough that an interpreter with an older Unicode
    # database would silently leave it out, and a decomposition longer than one
    # character. The last one guards the NFKD half's lower floor: "¹²³" belongs
    # to the same superscript alphabet as "⁴⁵⁶", so a cutoff at U+0100 would fold
    # only part of a generated name.
    for cp, expected in (
        (0x0196, "l"),
        (0x06F7, "v"),
        (0x014B, "n"),
        (0xAB8B, "h"),
        (0x1CCD6, "a"),
        (0xFB01, "fi"),
        (0x00B2, "2"),
        (0x0269, "l"),
        (0x2C87, "a"),
        (0x2C86, "a"),
    ):
        got = table.get(cp)
        if got != expected:
            sys.exit("U+%04X folds to %r, expected %r" % (cp, got, expected))

    # The encoding is only unambiguous while every key stays above the values'
    # ASCII range and no value is empty (an empty one would read as "delete this
    # character"), so check that, then read the literal back and compare.
    for cp, folded in table.items():
        if cp < NFKD_FROM or not folded or not folded.isascii():
            sys.exit("U+%04X -> %r cannot be packed" % (cp, folded))
    literal = java_literal(table)
    if parse_literal(literal) != table:
        sys.exit("the packed literal does not read back as the table it was built from")

    for a, b in case_conflicts(table):
        print(
            "note: U+%04X %s -> %s but U+%04X %s -> %s, and search folds the lowercase one"
            % (a, chr(a), table[a], b, chr(b), table[b]),
            file=sys.stderr,
        )

    # The two halves come from different releases: the confusables file carries
    # its own version, the decompositions come from this interpreter. Name both,
    # the comment above FOLD_PAIRS records them and a regen has to update it.
    confusables_version = re.search(r"^# Version: (\S+)", confusables, re.M)
    print(
        "%d entries, confusables %s, decompositions from Unicode %s"
        % (
            len(table),
            confusables_version.group(1) if confusables_version else "?",
            unicodedata.unidata_version,
        ),
        file=sys.stderr,
    )
    print(literal)


if __name__ == "__main__":
    main()
