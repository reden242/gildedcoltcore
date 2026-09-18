#!/usr/bin/env python3
"""Two normalisation gaps left open by the earlier zero-width fix.

## 1. `words()` does not strip invisibles, so short terms are splittable

`normalise()` strips `INVISIBLE`; `words()` never did. `words()` ends with a
catch-all `else { b.append(' '); }` for anything that is not a letter, a digit
or a combining mark - and a zero-width character is none of those, so it becomes
a **space**. `shortTermHit` splits `Scripts.words(raw)` on whitespace and matches
each word against the term pattern, so `f<ZWSP>a<ZWSP>g` arrives as the three
tokens `f`, `a`, `g` and never matches `fag`. That is the "short terms are still
splittable" gap, and this is the mechanism.

## 2. `INVISIBLE` omits several invisible blocks

Added: U+2065, U+FFF9-U+FFFB (interlinear annotation), U+13430-U+1343F (Egyptian
hieroglyph format controls), U+E0001 (language tag) and U+E0020-U+E007F (tag
characters). None of these is typable, none appears in ordinary chat, and each
renders as nothing while sitting inside a word.

The plane-1 range is written `\\x{13430}` rather than `\\u13430` on purpose:
Java processes `\\uXXXX` in the *lexer*, before the string literal exists, so
`\\u13430` is a compile error - it reads as `\\u1343` followed by `0`.

Variation selectors U+FE00-U+FE0F are deliberately NOT added. They are invisible
but they carry emoji presentation, and stripping them would change how a
legitimate message renders.

Both changes are verified against the 11,676-line real chat corpus after
applying: `normalise` and `words` output must be unchanged on every line.

Run:  python antiad/patch_scripts.py [--check]
"""

import pathlib
import sys

OLD_INVISIBLE = '''    private static final java.util.regex.Pattern INVISIBLE = java.util.regex.Pattern.compile(
            "[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u00AD\\uFEFF\\u180E]");
'''

NEW_INVISIBLE = '''    private static final java.util.regex.Pattern INVISIBLE = java.util.regex.Pattern.compile(
            "[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2065\\u00AD\\uFEFF\\u180E\\uFFF9-\\uFFFB"
                    + "\\\\x{13430}-\\\\x{1343F}\\\\x{E0001}\\\\x{E0020}-\\\\x{E007F}]");
'''

OLD_WORDS = '''    public static String words(String input) {
        if (input == null || input.isEmpty()) return "";
        Script script = detect(input);
'''

NEW_WORDS = '''    public static String words(String input) {
        if (input == null || input.isEmpty()) return "";
        // Strip the invisible blocks first. Everything below treats a character
        // that is not a letter, a digit or a combining mark as a separator, so a
        // zero-width character inside a word was becoming a space and splitting
        // the word into single letters - which is how "f<ZWSP>a<ZWSP>g" walked
        // past the short-term matcher.
        input = INVISIBLE.matcher(input).replaceAll("");
        if (input.isEmpty()) return "";
        Script script = detect(input);
'''

COLT = "ColtCore/src/main/java/com/coltcore/core/modules/Scripts.java"
GILD = "GildedCore-final/src/main/java/com/gildedmc/core/modules/Scripts.java"

SUBS = [("INVISIBLE widen", OLD_INVISIBLE, NEW_INVISIBLE, 1),
        ("words() strip", OLD_WORDS, NEW_WORDS, 1)]

check_only = "--check" in sys.argv
fail = False

for rel in (COLT, GILD):
    path = pathlib.Path(rel)
    raw = path.read_bytes()
    crlf = raw.count(b"\r\n") == raw.count(b"\n") and raw.count(b"\n") > 0
    text = raw.decode("utf-8").replace("\r\n", "\n")
    print(rel)
    staged = text
    local_fail = False
    for name, old, new, want in SUBS:
        found = staged.count(old)
        print(f"  {name:<16} occurrences={found} want={want}")
        if found != want:
            local_fail = True
            fail = True
            continue
        staged = staged.replace(old, new)
    if local_fail or check_only:
        continue
    path.write_bytes(staged.replace("\n", "\r\n" if crlf else "\n").encode("utf-8"))
    print(f"  wrote ({'CRLF' if crlf else 'LF'})")

if fail:
    raise SystemExit("ABORT: an anchor did not match the expected count")
print("done.")
