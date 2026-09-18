#!/usr/bin/env python3
"""Two DoS fixes in advertForm: the DOT_WORD blowup and the unbounded input.

## What was measured

Main thread, current source, via antiad/eval/l1 WProbe/surface:

    advertForm on a pure space run     400 chars ->   154 ms
                                       800 chars -> 1,196 ms
                                     1,600 chars -> 9,227 ms   (DOT_WORD alone: 8,184)
                                     3,200 chars -> >60 s  TIMEOUT

    book   1 page  (1,023 chars) ->  2,063 ms
    book  10 pages (10,239)      -> 20,566 ms
    book 100 pages (102,399)     -> >120 s TIMEOUT
    sign   4 lines (1,539)       ->    448 ms
    chat   256 chars             ->     79 ms

`DOT_WORD` is `\\s*[\\[(<{]?\\s*(?:dot|d0t|punto|point)\\s*[\\])>}]?\\s*`. Every
run of whitespace is unbounded and four of them surround a literal that may be
absent, so on a long run of spaces the engine retries every split of that run at
every starting position. Bounding each run to `{0,3}` keeps the pattern's
meaning - real evasion is a space or two around "dot" - and makes it linear.

Separately, `DOMAIN` recurses once per label and throws StackOverflowError at
about 1,800 labels (~4,000 chars), and there is NO catch site on the path:
`advertHit` -> `screenAdvertising` -> `screen` -> the event handlers, plus
`offends` -> `/textguard test`. The only `catch (Throwable)` is inside
`AntiAdPipeline.verdict`, which wraps `contextualVerdict` only - the regex stage
runs before it, so the error escapes the event handler entirely. A 100-page book
reaches 102,399 chars and unbounded `/textguard test` reaches any length.

Capping the input does not fix the quadratic on its own (a 2,048 cap still
stalls ~20 s), which is why both changes are here. The cap is for the recursion:
1,024 chars is exactly one full book page, so no single surface loses content,
and it leaves better than 2x margin under the observed ~4,000-char threshold.
The second cap is because NFKD can expand what it is given.

Run:  python antiad/patch_dos.py [--check]
"""

import pathlib
import sys

OLD_DOTWORD = r'''    private static final Pattern DOT_WORD = Pattern.compile(
            "\\s*[\\[(<{]?\\s*(?:dot|d0t|punto|point)\\s*[\\])>}]?\\s*", Pattern.CASE_INSENSITIVE);
'''

NEW_DOTWORD = r'''    private static final Pattern DOT_WORD = Pattern.compile(
            // Every run is bounded. Unbounded, this was quadratic: on a 1,600
            // space run it alone cost 8,184 ms of the 9,227 ms advertForm total,
            // and a 100-page book stalled the main thread past 120 s.
            "\\s{0,3}[\\[(<{]?\\s{0,3}(?:dot|d0t|punto|point)\\s{0,3}[\\])>}]?\\s{0,3}",
            Pattern.CASE_INSENSITIVE);

    /**
     * The most text advertForm will look at, before folding.
     *
     * <p>DOMAIN recurses once per label and overflows the stack at roughly
     * 1,800 labels - about 4,000 characters of dots and single characters - and
     * nothing on the call path catches it: the regex stage runs before the one
     * {@code catch (Throwable)} in AntiAdPipeline. 1,024 is a full book page,
     * the largest text any single surface can hand over, so nothing legitimate
     * is cut and there is better than 2x margin under the threshold.
     */
    private static final int MAX_ADVERT_FORM = 1024;
'''

OLD_ENTRY = r'''    static String advertForm(String input) {
        String s = Normalizer.normalize(input, Normalizer.Form.NFKD)
'''

NEW_ENTRY = r'''    static String advertForm(String input) {
        if (input.length() > MAX_ADVERT_FORM) input = input.substring(0, MAX_ADVERT_FORM);
        String s = Normalizer.normalize(input, Normalizer.Form.NFKD)
'''

OLD_CAP2 = r'''        s = ZERO_WIDTH.matcher(s).replaceAll("");
        StringBuilder b = new StringBuilder(s.length());
'''

NEW_CAP2 = r'''        s = ZERO_WIDTH.matcher(s).replaceAll("");
        // NFKD expands what it is given, so the bound has to be reapplied on the
        // folded form rather than assumed from the input length.
        if (s.length() > MAX_ADVERT_FORM * 2) s = s.substring(0, MAX_ADVERT_FORM * 2);
        StringBuilder b = new StringBuilder(s.length());
'''

COLT = "ColtCore/src/main/java/com/coltcore/core/modules/ChatGuardModule.java"
GILD = "GildedCore-final/src/main/java/com/gildedmc/core/modules/ChatGuardModule.java"

SUBS = [("DOT_WORD + cap const", OLD_DOTWORD, NEW_DOTWORD),
        ("input cap", OLD_ENTRY, NEW_ENTRY),
        ("post-NFKD cap", OLD_CAP2, NEW_CAP2)]

check_only = "--check" in sys.argv

for rel in (COLT, GILD):
    path = pathlib.Path(rel)
    raw = path.read_bytes()
    crlf = raw.count(b"\r\n") == raw.count(b"\n") and raw.count(b"\n") > 0
    text = raw.decode("utf-8").replace("\r\n", "\n")
    print(rel)
    staged = text
    for name, old, new in SUBS:
        found = staged.count(old)
        print(f"  {name:<20} occurrences={found}")
        if found != 1:
            raise SystemExit(f"ABORT {rel}: {name} matched {found} times, expected 1")
        staged = staged.replace(old, new)
    if check_only:
        continue
    path.write_bytes(staged.replace("\n", "\r\n" if crlf else "\n").encode("utf-8"))
    print(f"  wrote ({'CRLF' if crlf else 'LF'})")

print("done.")
