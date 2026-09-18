#!/usr/bin/env python3
"""Remove the dead cancelMessage field and its config read.

The field is write-only (write-once from config, never read) and both its
readers in onChat and onCommandText were replaced with unconditional
event.setCancelled(true) by patch_content.py. Retained in config.yml as
inert for compatibility — it documents the old behaviour.

Run:  python antiad/patch_dead.py [--check]
"""

import pathlib
import sys

COLT = "ColtCore/src/main/java/com/coltcore/core/modules/ChatGuardModule.java"
GILD = "GildedCore-final/src/main/java/com/gildedmc/core/modules/ChatGuardModule.java"

OLD_FIELD = "    private boolean cancelMessage = true;\n"
NEW_FIELD = ""

OLD_CONFIG = "        this.cancelMessage = c.getBoolean(\"cancel-message\", true);\n"
NEW_CONFIG = ""

SUBS = [("dead field", OLD_FIELD, NEW_FIELD, 1),
        ("dead config", OLD_CONFIG, NEW_CONFIG, 1)]

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
        print(f"  {name:<14} occurrences={found} want={want}")
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