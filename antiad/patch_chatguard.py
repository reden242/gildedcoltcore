#!/usr/bin/env python3
"""Two measured anti-ad fixes, applied to ChatGuardModule in both trees.

Fix A - advertForm() blanked EVERY space in a message as soon as any of
        server|discord|invite|website|address appeared anywhere in it, so
        "join play.coolpvp.xyz for free ranks server" scored 0.0227 instead of
        0.9718. One ordinary word was a complete layer-2 bypass. Narrowed to
        runs of single characters, which is the letter-spacing evasion it was
        written for; whole words are never joined.

Fix B - L3 was fed the RAW message while L2 is fed advertForm(). L3 is the only
        layer that reads every message and it read it rawest, so the NFKD
        folding that L1 and L2 depend on was absent exactly where it mattered
        most: "join <fullwidth>play.coolpvp.xyz for free ranks" scored 0.9718 at
        L2 and 0.0294 at L3. Latent while L2 blocks first, live the moment
        anti-ad.l2.enabled is false.

Atomic: every substitution is counted and asserted before anything is written,
so a mismatch aborts with the file untouched.

Run:  python antiad/patch_chatguard.py            # apply
      python antiad/patch_chatguard.py --check    # report counts, write nothing
"""

import pathlib
import sys

COLT = "ColtCore/src/main/java/com/coltcore/core/modules/ChatGuardModule.java"
GILD = "GildedCore-final/src/main/java/com/gildedmc/core/modules/ChatGuardModule.java"

# --------------------------------------------------------------------------
# Fix A - the spaced-keyword collapse
# --------------------------------------------------------------------------

OLD_A = r'''        s = foldKnownLeet(s);
        String collapsed = s.replaceAll("\\s+", "");
        if (!collapsed.equals(s) && SPACED_KEYWORD.matcher(collapsed).find()) s = collapsed;
        return s;
    }
'''

NEW_A = r'''        s = foldKnownLeet(s);
        s = collapseSpacedKeywords(s);
        return s;
    }

    /**
     * Joins runs of single characters - "p l a y . c o o l p v p . x y z" - so
     * a letter-spaced address is one token again.
     *
     * <p>Only single characters are joined, never whole words. This replaced a
     * stripper that deleted EVERY space in the message as soon as any of
     * {@code server|discord|invite|website|address} appeared anywhere in it, so
     * "join play.coolpvp.xyz for free ranks server" was scored as
     * "joinplay.coolpvp.xyzforfreeranksserver" - three unknown words, 0.0227
     * where the un-collapsed text scores 0.9718. One ordinary English word was
     * therefore a complete layer-2 bypass, and the word an advertiser is most
     * likely to type anyway.
     *
     * <p>Letter-spaced text is still joined, whatever else the message holds,
     * because a run of single characters is joined unconditionally. Joining a
     * run can only ever create an address for layer one, never hide one: the
     * separators are dropped, so "p l a y.c o o l p v p" becomes
     * "play.coolpvp" and not the reverse.
     */
    private static String collapseSpacedKeywords(String s) {
        Matcher runs = SPACED_RUN.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        int last = 0;
        boolean joined = false;
        while (runs.find()) {
            out.append(s, last, runs.start()).append(runs.group().replaceAll("\\s+", ""));
            last = runs.end();
            joined = true;
        }
        if (!joined) return s;
        return out.append(s, last, s.length()).toString();
    }
'''

# --------------------------------------------------------------------------
# Fix A - the pattern the new helper needs
# --------------------------------------------------------------------------

OLD_B = r'''    private static final Pattern SPACED_KEYWORD = Pattern.compile(
            "discord|server|invite|website|address");
'''

NEW_B = r'''    private static final Pattern SPACED_KEYWORD = Pattern.compile(
            "discord|server|invite|website|address");
    /**
     * A run of single alphanumerics separated by whitespace - "s e r v e r",
     * "d i s c o r d", "p l a y". Three characters minimum, so an ordinary
     * "a b" pair is left alone.
     */
    private static final Pattern SPACED_RUN = Pattern.compile(
            "\\b(?:[a-z0-9]\\s+){2,}[a-z0-9]\\b");
'''

# --------------------------------------------------------------------------
# Fix B - feed L3 the same folded text L1 and L2 see
# --------------------------------------------------------------------------

OLD_C = r'''            AntiAdPipeline.ModelVerdict verdict = this.antiAd.l3Check(
                    player.getUniqueId(), raw);
'''

NEW_C = r'''            AntiAdPipeline.ModelVerdict verdict = this.antiAd.l3Check(
                    player.getUniqueId(), advertForm(raw));
'''

OLD_D = r'''            AntiAdPipeline.ModelVerdict verdict = this.antiAd.l3ScanEveryMessage(
                    p.getUniqueId(), raw);
'''

NEW_D = r'''            AntiAdPipeline.ModelVerdict verdict = this.antiAd.l3ScanEveryMessage(
                    p.getUniqueId(), advertForm(raw));
'''

TARGETS = [
    (COLT, [("A collapse", OLD_A, NEW_A), ("A pattern", OLD_B, NEW_B),
            ("B band", OLD_C, NEW_C), ("B scan-all", OLD_D, NEW_D)]),
    (GILD, [("A collapse", OLD_A, NEW_A), ("A pattern", OLD_B, NEW_B),
            ("B band", OLD_C, NEW_C), ("B scan-all", OLD_D, NEW_D)]),
]

check_only = "--check" in sys.argv


def patch(rel, subs):
    path = pathlib.Path(rel)
    # Universal-newline read, so the substitutions are written with plain \n
    # and the terminator is restored on write. Both files are uniform: Colt is
    # CRLF end to end, Gilded is LF end to end.
    raw = path.read_bytes()
    crlf = raw.count(b"\r\n") == raw.count(b"\n")
    text = raw.decode("utf-8").replace("\r\n", "\n")

    staged = text
    for name, old, new in subs:
        found = staged.count(old)
        print(f"  {name:<12} occurrences={found}")
        if found != 1:
            raise SystemExit(f"ABORT {rel}: {name} matched {found} times, expected 1")
        staged = staged.replace(old, new)

    if check_only:
        print(f"  {rel}: all four anchors unique - patch would apply cleanly")
        return

    path.write_bytes(staged.replace("\n", "\r\n" if crlf else "\n").encode("utf-8"))
    print(f"  wrote {rel} ({'CRLF' if crlf else 'LF'})")


for rel, subs in TARGETS:
    print(rel)
    patch(rel, subs)

print("done.")
