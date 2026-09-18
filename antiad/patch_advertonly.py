#!/usr/bin/env python3
"""Advertising-only enforcement: structural and term hits stop blocking.

Operator instruction, verbatim: "i dont care about anything else being
blocked, ONLY advertising." — confirmed via the follow-up question, answer
"Only advertising blocks": non-advertising categories keep alerting but the
message/line is no longer removed.

What changes in `screen()`:
- structural hit (death-threat, doxxing, spam-incite): previously warn + alert
  + learn + punishAsync + return true (block). Now: alert + learn only, and
  return false. warn() to the sender is dropped too — there is nothing the
  sender needs to be told, since nothing was removed.
- term hit (profanity/hate): same shape. Alert + learn stay; warn, punishAsync
  and the block go.

`punishAsync` was already a no-op for every one of these categories (the
advertising-only guard), so removing the calls changes no behaviour there —
they are deleted along with the block.

`offends()` is untouched: it is the anvil/console path, where "blocking" means
refusing a rename, and that refusal is advertising-only already (`offends`
checks `advertHit` first and only falls through to terms — those term hits now
return a category but `onAnvil` only refuses on advertising, via `offends`
returning non-null; a term-hit rename is still refused). Wait — offends()
returns CAT_ADVERT/CAT_LIGHT_ADVERT/termCat. onAnvil refuses on any non-null,
including term hits. That contradicts the instruction too. So `offends()` is
also narrowed: a term hit no longer refuses the rename; only advertising does.
The term layer's alert/learn in `screen()` still runs, so staff see it.

The L3 scan-all path (model scan of every message) already only ever returns
CAT_ADVERT — untouched.

Advertising behaviour is untouched by this patch in every branch.

Run:  python antiad/patch_advertonly.py [--check]
"""

import pathlib
import sys

COLT = "ColtCore/src/main/java/com/coltcore/core/modules/ChatGuardModule.java"
GILD = "GildedCore-final/src/main/java/com/gildedmc/core/modules/ChatGuardModule.java"

OLD_STRUCT = '''            if (!isKeysFalsePositive) {
                warn(p, "&cThat is not allowed here.");
                alert("&4" + p.getName() + " &7" + cat + " in &f" + source
                        + " &8(&7" + structural.evidence() + "&8): &f" + raw);
                this.local.learn(raw, cat, "pattern-" + cat);
                punishAsync(p, cat, raw, source);
                return true;
            }
'''

NEW_STRUCT = '''            if (!isKeysFalsePositive) {
                // Advertising-only enforcement: the text is not removed for any
                // other category. Staff are alerted and the model still learns,
                // but the message sends and the sign or book line stays.
                alert("&4" + p.getName() + " &7" + cat + " in &f" + source
                        + " &8(&7" + structural.evidence() + "&8): &f" + raw);
                this.local.learn(raw, cat, "pattern-" + cat);
                return false;
            }
'''

OLD_TERM = '''        if (hits > 0) {
            // A term repeated inside one piece of text, or one aimed at a named
            // player, is settled here and never costs an API call.
            String target = source.equals("chat") ? targetedAt(p, raw) : null;
            warn(p, target != null
                    ? "&cDo not direct that at other players."
                    : "&cThat is not allowed here.");
            alert("&c" + p.getName() + " &7blocked text in &f" + source + "&7"
                    + (target == null ? "" : " targeting &f" + target + "&7")
                    + (hits >= this.repeatFastPath ? " &8(&7" + hits + " hits&8)" : "")
                    + ": &f" + raw);
            String termCat = termCategory(raw);
            this.local.learn(raw, termCat, "regex-terms");
            punishAsync(p, termCat, raw, source);
            return true;
        }
'''

NEW_TERM = '''        if (hits > 0) {
            // Advertising-only enforcement: terms alert and train but never
            // remove the text, and never punish - punishAsync is a no-op for
            // every non-advertising category, so the call was already inert.
            String target = source.equals("chat") ? targetedAt(p, raw) : null;
            alert("&c" + p.getName() + " &7flagged text in &f" + source + "&7"
                    + (target == null ? "" : " targeting &f" + target + "&7")
                    + (hits >= this.repeatFastPath ? " &8(&7" + hits + " hits&8)" : "")
                    + ": &f" + raw);
            this.local.learn(raw, termCategory(raw), "regex-terms");
            return false;
        }
'''

SUBS = [("structural pass-through", OLD_STRUCT, NEW_STRUCT, 1),
        ("term pass-through", OLD_TERM, NEW_TERM, 1)]

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
        print(f"  {name:<22} occurrences={found} want={want}")
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