#!/usr/bin/env python3
"""Two findings from the punishment-reachability audit.

## 1. The trees disagree on the anvil surface

Colt, the considered version, does `offends(name) != null` -> clear the result
slot and warn the player. Gilded requires `offends(name) != null` AND then
`screen(p, name, "item-name")` before clearing. So Gilded detects strictly less
(the conjunction of two detectors is a subset of either), pays for a full
`screen()` - which runs the 24-token classifier - on **every keystroke in the
anvil**, and never warns the player. All three differences are losses. Mirrored
to Colt's form.

`screens` here also means Gilded's anvil was a third live route into
`punishAsync`; that route closes with the change.

## 2. `punishAsync` computed a socket address it never used

The advertising-only guard sits *below* this:

    final String name = p.getName();
    final String uuid = p.getUniqueId().toString();
    final String ip = MuteStore.addressOf(p);

`MuteStore.addressOf` reads the player's socket address and formats it, and
`punishAsync` is reached from `screen()` on the **main thread** for signs and
books. Since the guard returns for every non-advertising category and
advertising no longer calls `punishAsync` at all, that work is always discarded.
Hoisting the guard above it removes it from the main thread.

Not changed here, deliberately: the two `punishAsync(p, cat, raw, source)` calls
in `screen()` are now no-ops past the guard. They are left in place so that
flipping the single advertising-only guard restores the old behaviour, and
because deleting them would make `punishAsync`, `applyRung`, `advertDoubleCheck`,
`announce` and `ReviewModule.submit` unreachable code with no remaining purpose.

Run:  python antiad/patch_surfaces.py [--check]
"""

import pathlib
import sys

COLT = "ColtCore/src/main/java/com/coltcore/core/modules/ChatGuardModule.java"
GILD = "GildedCore-final/src/main/java/com/gildedmc/core/modules/ChatGuardModule.java"

OLD_ANVIL_GILD = '''        if (offends(name) == null) return;
        if (screen(p, name, "item-name")) event.setResult(null);
    }
'''

NEW_ANVIL_GILD = '''        // Just do not allow the rename. No punishment: a rename attempt is not a
        // public message, so it does not escalate the ladder on its own.
        if (offends(name) != null) {
            event.setResult(null);
            warn(p, "&cYou cannot rename an item to that.");
        }
    }
'''

OLD_GUARD = '''    private void punishAsync(Player p, String category, String text, String source,
                             boolean regexConfirmed) {
        final String name = p.getName();
        final String uuid = p.getUniqueId().toString();
        final String ip = MuteStore.addressOf(p);
        // ADVERTISING-ONLY MODE: every other category alerts but never punishes.
        if (!CAT_ADVERT.equals(category)) {
            this.plugin.getLogger().info("[TextGuard] " + category + " for " + name
                    + " left unpunished (advertising-only mode).");
            return;
        }
'''

NEW_GUARD = '''    private void punishAsync(Player p, String category, String text, String source,
                             boolean regexConfirmed) {
        final String name = p.getName();
        // ADVERTISING-ONLY MODE: every other category alerts but never punishes.
        // This guard sits above the address lookup on purpose: `addressOf` reads
        // and formats the player's socket address, and this method is reached
        // from `screen()` on the main thread for signs and books, so doing that
        // work above a guard that always returns for these categories meant a
        // per-message allocation on the server thread whose result was thrown
        // away unread.
        if (!CAT_ADVERT.equals(category)) {
            this.plugin.getLogger().info("[TextGuard] " + category + " for " + name
                    + " left unpunished (advertising-only mode).");
            return;
        }
        final String uuid = p.getUniqueId().toString();
        final String ip = MuteStore.addressOf(p);
'''

check_only = "--check" in sys.argv
fail = False

for rel, subs in ((COLT, [("guard hoist", OLD_GUARD, NEW_GUARD, 1)]),
                  (GILD, [("guard hoist", OLD_GUARD, NEW_GUARD, 1),
                          ("anvil mirror", OLD_ANVIL_GILD, NEW_ANVIL_GILD, 1)])):
    path = pathlib.Path(rel)
    raw = path.read_bytes()
    crlf = raw.count(b"\r\n") == raw.count(b"\n") and raw.count(b"\n") > 0
    text = raw.decode("utf-8").replace("\r\n", "\n")
    print(rel)
    staged = text
    local_fail = False
    for name, old, new, want in subs:
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
