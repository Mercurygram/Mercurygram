#!/usr/bin/env python3
"""Regenerate the LOOKALIKE_PAIRS table of MgUnicodeFold.java.

The table maps codepoints that only *look* like a Latin letter onto that
letter, so that searching "love island" finds a group actually named
"Ɩơ۷ɛ ıʂƖąŋɖ". Source is the Unicode confusables data (UTS #39); everything
NFKD or the upstream transliteration table already handles is left out, and
so is the ordinary text of scripts with a living user base.

Usage:
    python3 scripts/gen-unicode-fold.py [confusables.txt [Scripts.txt]]

Missing arguments are downloaded from unicode.org. The generated Java block
is printed on stdout; paste it into MgUnicodeFold.java, comment line
included - it records the confusables release the table came from.
"""

import bisect
import re
import sys
import unicodedata
import urllib.request
from pathlib import Path

CONFUSABLES_URL = "https://www.unicode.org/Public/security/latest/confusables.txt"
SCRIPTS_URL = "https://www.unicode.org/Public/UCD/latest/ucd/Scripts.txt"

LOCALE_CONTROLLER = (
    "TMessagesProj/src/main/java/org/telegram/messenger/LocaleController.java"
)

# Ordinary text of these scripts is not decoration, and folding it would break
# both the Cyrillic transliteration the upstream table does and any search in
# those scripts. Their digits stay foldable, "۷" for "v" and "٥" for "o" being
# staples of the font generators, and so do their styled variants (see
# DECORATIVE_FORMS): a mathematical bold alpha is decoration by construction.
LIVING_SCRIPTS = {
    "Adlam", "Arabic", "Armenian", "Bengali", "Bopomofo", "Cyrillic",
    "Devanagari", "Ethiopic", "Georgian", "Greek", "Gujarati", "Gurmukhi",
    "Han", "Hangul", "Hebrew", "Hiragana", "Kannada", "Katakana", "Khmer",
    "Lao", "Malayalam", "Mongolian", "Myanmar", "Nko", "Oriya", "Sinhala",
    "Syriac", "Tamil", "Telugu", "Thaana", "Thai", "Tibetan", "Vai",
}

# Compatibility decomposition tags that mark a codepoint as a styled variant of
# another one. The positional Arabic forms (<isolated>, <initial>, ...) are
# deliberately absent: those are how real Arabic text was encoded, not a font
# generator's output.
DECORATIVE_FORMS = ("<font>", "<circle>", "<square>", "<wide>", "<super>", "<sub>")

# Blocks the confusables data does not cover at all, mapped by hand.
MANUAL = {0x1768: "t", 0x176A: "o"}  # Tagbanwa na, ba

# Letters of a living language whose confusable prototype is a plain misreading:
# thorn is "th", not the "p" it happens to look like. Folding them would make
# ordinary Icelandic words match unrelated queries.
EXCLUDED = {0x00DE, 0x00FE}  # Þ þ

# MgUnicodeFold.maybeDecorated never even looks up these ranges, so an entry
# landing in one would be silently dead. Keep the two sides from drifting.
RUNTIME_BLIND = ((0x0400, 0x052F), (0x4E00, 0x9FFF))  # Cyrillic, CJK Unified

# A confusables prototype can itself be non-ASCII ("ɛ" resolves to "ꞓ", "ŋ" to
# "n̩"), so the mapping has to be followed to a fixpoint rather than looked up
# once. The cap only guards against a cycle in the data.
MAX_DEPTH = 6

LINE_WIDTH = 22  # codepoints per emitted Java string literal


def fetch(source, fallback_url):
    if source is None:
        source = fallback_url
    if source.startswith(("http://", "https://")):
        with urllib.request.urlopen(source) as response:
            return response.read().decode("utf-8-sig")
    return Path(source).read_text(encoding="utf-8-sig")


def fields_of(text):
    """The semicolon-separated data lines of a UCD file, comments stripped."""
    for line in text.splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            yield [field.strip() for field in line.split(";")]


def read_confusables(source):
    text = fetch(source, CONFUSABLES_URL)
    version = next(
        (line.split(":", 1)[1].strip()
         for line in text.splitlines() if line.startswith("# Version:")),
        "unknown",
    )
    mapping = {}
    for fields in fields_of(text):
        if len(fields) < 2 or " " in fields[0]:
            continue
        mapping[int(fields[0], 16)] = "".join(
            chr(int(part, 16)) for part in fields[1].split()
        )
    return version, mapping


def read_scripts(source):
    """(first codepoint, last codepoint, script name), sorted, for bisect."""
    ranges = []
    for fields in fields_of(fetch(source, SCRIPTS_URL)):
        low, _, high = fields[0].partition("..")
        ranges.append((int(low, 16), int(high or low, 16), fields[1]))
    ranges.sort()
    return ranges


def script_of(codepoint, ranges):
    index = bisect.bisect_right(ranges, (codepoint, 0x110000, "")) - 1
    if index >= 0 and codepoint <= ranges[index][1]:
        return ranges[index][2]
    return "Unknown"


def translit_chars():
    """The codepoints upstream LocaleController already transliterates."""
    source = (Path(__file__).resolve().parents[1] / LOCALE_CONTROLLER).read_text(
        encoding="utf-8"
    )
    keys = re.findall(r'translitChars\.put\("(.+?)", "', source)
    # Upstream reformatting those calls would silently shrink the set, and MG
    # would start shadowing upstream mappings with no signal at all.
    assert len(keys) > 300, "translitChars scrape found only %d keys" % len(keys)
    return {ord(key[0]) for key in keys}


def undecorate(text):
    """NFKD, minus the combining marks it splits off."""
    return "".join(
        char
        for char in unicodedata.normalize("NFKD", text)
        if unicodedata.category(char) != "Mn"
    )


def substitute(text, mapping):
    return "".join(mapping.get(ord(char), char) for char in text)


def resolve(codepoint, mapping):
    text = chr(codepoint)
    for _ in range(MAX_DEPTH):
        folded = undecorate(substitute(text, mapping))
        if folded == text:
            break
        text = folded
    return text


def build_table(mapping, translit, scripts):
    table = {}
    for codepoint in range(0x80, 0x110000):
        char = chr(codepoint)
        category = unicodedata.category(char)
        if category == "Cn" or codepoint in translit or codepoint in EXCLUDED:
            continue
        if any(low <= codepoint <= high for low, high in RUNTIME_BLIND):
            continue
        # Marks are never decoration, and folding one corrupts the search key of
        # every name that uses it: the anusvara of the Indic scripts carries a
        # confusable prototype ("o") but appears in ordinary words.
        if category.startswith("M"):
            continue
        # NFKD already yields plain ASCII: the runtime path owns this codepoint,
        # and its answer is the right one. Confusables would instead turn the
        # fullwidth and mathematical digits into "o" and "l".
        decomposed = undecorate(char)
        if len(decomposed) == 1 and ord(decomposed) < 0x80:
            continue
        resolved = resolve(codepoint, mapping)
        if len(resolved) != 1:
            continue
        letter = resolved.lower()
        if not ("a" <= letter <= "z" or "0" <= letter <= "9"):
            continue
        if (
            script_of(codepoint, scripts) in LIVING_SCRIPTS
            and category != "Nd"
            and not unicodedata.decomposition(char).startswith(DECORATIVE_FORMS)
        ):
            continue
        table[codepoint] = letter
    table.update(MANUAL)
    return table


def render(table, version):
    pairs = [chr(codepoint) + table[codepoint] for codepoint in sorted(table)]
    lines = [
        "".join(pairs[start:start + LINE_WIDTH])
        for start in range(0, len(pairs), LINE_WIDTH)
    ]
    out = [
        "    // %d entries, generated by scripts/gen-unicode-fold.py from"
        " confusables.txt %s" % (len(table), version),
        "    private static final String LOOKALIKE_PAIRS =",
    ]
    for index, line in enumerate(lines):
        prefix = "            " if index == 0 else "            + "
        terminator = ";" if index == len(lines) - 1 else ""
        out.append('%s"%s"%s' % (prefix, line, terminator))
    return "\n".join(out)


def main():
    args = sys.argv[1:] + [None, None]
    version, mapping = read_confusables(args[0])
    table = build_table(mapping, translit_chars(), read_scripts(args[1]))
    print(render(table, version))


if __name__ == "__main__":
    main()
