#!/usr/bin/env python3
"""Block the content, remove every advertising punishment.

Two decisions from the operator, applied here:

  1. Nothing is punished for advertising any more. The mute ladder, the
     MuteStore write and the LiteBans bridge stay wired for the other
     categories - harassment, death threats, doxxing, spam - because the
     question was asked about advertising and answered about advertising.
     `blockAdvertising` keeps its training sample, its sender warning and its
     staff alert, and drops only the `punishAsync` call.

  2. The offending text is removed rather than the whole submission. Chat was
     already cancelled; the anvil rename was already refused. Signs and books
     used to join all their lines and cancel the entire event, so one bad line
     on page 40 of a book threw away the other 39. Now each line is screened on
     its own and only the lines that offend are blanked.

The trade is deliberate and worth stating: a sign or book is no longer screened
as one document, so a pitch split across lines so that no single line carries it
- "join my" / "server at" / "play.example.net" - is screened line by line. That
is weaker than the joined text. It is what "the offending line is removed"
means, and it is also strictly cheaper for anyone writing an innocent book with
one address in it.

Chat cancellation is made unconditional in the same pass. It was gated on
`cancel-message`, and a config that says "cancel the offending message" but
lets every detected message through is the same class of illusion as the
enforcement gaps already fixed in this module.

Run:  python antiad/patch_content.py [--check]
"""

import pathlib
import sys

COLT = "ColtCore/src/main/java/com/coltcore/core/modules/ChatGuardModule.java"
GILD = "GildedCore-final/src/main/java/com/gildedmc/core/modules/ChatGuardModule.java"

# ---------------------------------------------------------------- 1. no punish

OLD_PUNISH = '''        punishAsync(player, category, raw, source, llmConfirmed);
        return true;
    }
'''

NEW_PUNISH = '''        // No punishment for advertising. The content is stopped - the message
        // is not sent, the rename is refused, the offending sign part or book
        // line is blanked - and the sender warning and staff alert above keep
        // the record. Nothing goes to the ladder, so advertising costs a player
        // nothing but the message itself.
        return true;
    }
'''

# ------------------------------------------------------- 2. chat always blocks

OLD_CANCEL = "            if (this.cancelMessage) event.setCancelled(true);\n"
NEW_CANCEL = "            event.setCancelled(true);\n"

# ------------------------------------------------------------- 3. sign, by line

OLD_SIGN = '''        Player p = event.getPlayer();
        StringBuilder sb = new StringBuilder();
        for (String line : event.getLines()) {
            if (line != null && !line.isBlank()) sb.append(line).append(' ');
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) return;
        if (!this.enabled || p.hasPermission(PERM_BYPASS)) return;
        if (this.muteBlocksTextSources && blockedByMute(p)) { event.setCancelled(true); return; }
        if (screen(p, text, "sign")) event.setCancelled(true);
    }
'''

NEW_SIGN = '''        Player p = event.getPlayer();
        if (!this.enabled || p.hasPermission(PERM_BYPASS)) return;
        if (this.muteBlocksTextSources && blockedByMute(p)) { event.setCancelled(true); return; }

        // Screen and clear each line on its own. The sign is still placed, minus
        // whatever offended - a blank line reads as a mistake the player has to
        // fix, which is the same information as a refusal without the collateral
        // of losing the three good lines next to it.
        String[] lines = event.getLines();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isBlank()) continue;
            if (screen(p, line, "sign")) event.setLine(i, "");
        }
    }
'''

# ------------------------------------------------------------- 4. book, by line

OLD_BOOK = '''        Player p = event.getPlayer();
        BookMeta meta = event.getNewBookMeta();
        StringBuilder sb = new StringBuilder();
        if (meta.hasTitle() && meta.getTitle() != null) sb.append(meta.getTitle()).append(' ');
        for (String page : meta.getPages()) {
            if (page != null && !page.isBlank()) sb.append(page).append(' ');
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) return;
        if (!this.enabled || p.hasPermission(PERM_BYPASS)) return;
        if (this.muteBlocksTextSources && blockedByMute(p)) { event.setCancelled(true); return; }
        if (screen(p, text, "book")) event.setCancelled(true);
    }
'''

NEW_BOOK = '''        Player p = event.getPlayer();
        if (!this.enabled || p.hasPermission(PERM_BYPASS)) return;
        if (this.muteBlocksTextSources && blockedByMute(p)) { event.setCancelled(true); return; }

        BookMeta meta = event.getNewBookMeta();
        boolean touched = false;

        // The title is checked on its own: it is one line and blanking it is the
        // same refusal an anvil rename gets.
        if (meta.hasTitle() && meta.getTitle() != null && !meta.getTitle().isBlank()
                && screen(p, meta.getTitle(), "book")) {
            meta.setTitle("");
            touched = true;
        }

        // Each page's lines are screened one at a time so that clearing one does
        // not take the rest of the page with it - the old code joined the title
        // and every page into one string and cancelled the whole edit.
        List<String> pages = new ArrayList<>(meta.getPages());
        for (int i = 0; i < pages.size(); i++) {
            String page = pages.get(i);
            if (page == null || page.isBlank()) continue;
            String[] pageLines = page.split("\\n", -1);
            boolean pageTouched = false;
            for (int j = 0; j < pageLines.length; j++) {
                if (pageLines[j].isBlank()) continue;
                if (screen(p, pageLines[j], "book")) { pageLines[j] = ""; pageTouched = true; }
            }
            if (pageTouched) {
                pages.set(i, String.join("\\n", pageLines));
                touched = true;
            }
        }
        if (touched) {
            meta.setPages(pages);
            event.setNewBookMeta(meta);
        }
    }
'''

# ---------------------------------------------------------------- 5. config doc

OLD_CFG = '''  # DELETE the offending message. Chat is cancelled before anyone sees it, and
  # the same applies to signs, books and anvil renames - the sign is never
  # placed and the rename never applies.
  cancel-message: true
'''

NEW_CFG = '''  # REMOVE the offending text, and punish nobody for advertising.
  # Chat is cancelled before anyone sees it and an anvil rename is refused. A
  # sign's bad line is cleared and the sign still places; a book's bad line is
  # cleared and the book is still written. The sender is warned and staff are
  # alerted either way, but no mute ladder runs for advertising.
  # Retained for compatibility: chat is cancelled regardless of this value.
  cancel-message: true
'''

COLT_CFG = "ColtCore/src/main/resources/config.yml"
GILD_CFG = "GildedCore-final/src/main/resources/config.yml"

JAVA_SUBS = [("no punish", OLD_PUNISH, NEW_PUNISH, 1),
             ("chat cancel", OLD_CANCEL, NEW_CANCEL, 2),
             ("sign by line", OLD_SIGN, NEW_SIGN, 1),
             ("book by line", OLD_BOOK, NEW_BOOK, 1)]
CFG_SUBS = [("config doc", OLD_CFG, NEW_CFG, 1)]

check_only = "--check" in sys.argv
fail = False

for rel, subs in ((COLT, JAVA_SUBS), (GILD, JAVA_SUBS),
                  (COLT_CFG, CFG_SUBS), (GILD_CFG, CFG_SUBS)):
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
