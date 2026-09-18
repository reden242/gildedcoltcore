#!/usr/bin/env python3
"""MillenniumNet: the zero-token sentinel returned 0.5f.

`if (this.t == 0) return 0.5f;` is the "no [a-z0-9']+ tokens at all" branch.
0.5 sits inside the escalation band (BLOCK 0.85 / CLEAR 0.40), so a message
that carries no evidence whatever - "!!!", "...", "***", an emoji run, a
punctuation-only line, a fullwidth or mathematical-alphanumeric string - was
escalated to L3 as if it were borderline advertising. Worse, being in the band
is what lets L3 rule 2 fire: a punctuation-only message in a context where half
the recent lines already score >= 0.85 was flagged on the strength of its
neighbours, having said nothing itself.

Returning the clear end instead matches the fail-open posture everywhere else in
this layer, and removes the escalation. It does not make a non-Latin advert
detectable - neither value blocks, because the tokenizer has nothing to read -
it only stops noise from being treated as evidence.

This branch is reachable only when t == 0, so it cannot affect any forward pass
with at least one token. Every probability for a non-empty tokenisation is
bit-identical, and no weight in antiad.m5.bin is invalidated: NO RETRAIN.

Run:  python antiad/patch_millenniumnet.py [--check]
"""

import pathlib
import sys

OLD = "        if (this.t == 0) return 0.5f;"

NEW = """        // No [a-z0-9']+ tokens at all: pure punctuation, an emoji run, or a
        // script the tokenizer does not cover. There is no evidence here to
        // weigh, and 0.5 was not a neutral answer - it landed inside the
        // 0.40-0.85 band, so these messages were escalated to L3 and could be
        // flagged by rule 2 on the strength of the surrounding lines alone,
        // having said nothing themselves. Return the clear end, the same
        // fail-open direction the rest of this layer takes.
        if (this.t == 0) return 0f;"""

FILES = [
    "ColtCore/src/main/java/com/coltcore/core/modules/MillenniumNet.java",
    "GildedCore-final/src/main/java/com/gildedmc/core/modules/MillenniumNet.java",
]

check_only = "--check" in sys.argv

for rel in FILES:
    path = pathlib.Path(rel)
    raw = path.read_bytes()
    crlf = raw.count(b"\r\n") == raw.count(b"\n") and raw.count(b"\n") > 0
    text = raw.decode("utf-8").replace("\r\n", "\n")
    found = text.count(OLD)
    print(f"{rel}\n  sentinel occurrences={found}  terminator={'CRLF' if crlf else 'LF'}")
    if found != 1:
        raise SystemExit(f"ABORT {rel}: matched {found} times, expected 1")
    if check_only:
        continue
    path.write_bytes(text.replace(OLD, NEW)
                         .replace("\n", "\r\n" if crlf else "\n").encode("utf-8"))
    print("  wrote")

print("done.")
