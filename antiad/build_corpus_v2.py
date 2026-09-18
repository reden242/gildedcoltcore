#!/usr/bin/env python3
"""Rebuild the anti-ad training corpus so that no opening token predicts the label.

WHY THIS EXISTS
---------------
The shipped model learned ``P(advertising | token[0])`` instead of anything about
advertising.  Measured on ``seed_corpus.tsv`` + ``tld_corpus.tsv``, the positive
pool opened with only 154 distinct tokens and 74% of it opened with one of a
handful of template verbs (``come``, ``pvp``, ``my``, ``join``, ...), while
``the`` opened 1,367 negatives and **0** positives.  Nothing in the corpus
contradicted "an advert never begins with *the*", so the model scored 0.02 on
``the join play.example.com now`` and 0.97 on ``discord is broken``.  Model score
correlated with the corpus conditional at Spearman rho 0.971.

The target invariant, stated as a thing that can be measured:

    For every frequent opening token w, P(advertising | token[0] = w) must be
    close to the base rate.

WHAT IT DOES
------------
Three transformations, all of them concatenations of text that already exists in
the workspace.  No corpus line is invented from a template - templated text is
what caused the defect.

1. **Position randomisation of positives.**  Every positive is available with a
   real chat fragment in front of it (a whole line from ``chat_corpus.txt``, or a
   leading 1-8 word slice of one), joined by a space, a comma, a conjunction or a
   sentence boundary.  The marker therefore appears at token 0 sometimes, clause
   initial after a comma often, and clause final often.

2. **Marker-opening negatives mined from real chat.**  Lines of
   ``chat_corpus.txt`` whose token[0] is an advertising word - ``join``,
   ``play``, ``free``, ``check``, ``server``, ``we``, ``my``, ``pvp``, ... - are
   labelled clean, because they are real players saying "join us tomorrow" and
   "the server is down".  This is the class the shipped model mutes.  Where a
   chat line contains such a word *later* in the line, the window starting at
   that word is also emitted, so an opener that real chat never uses at position
   0 can still be covered.

3. **Ad-like hosts inside ordinary chat.**  Clean corpus rows that mention a
   hostname benignly ("the wiki at imgur.best is old") are placed after and
   before real chat fragments, so a hostname is no longer evidence of an advert
   on its own.

The result is written as a self-contained pair of TSVs, ``seed_corpus.v2.tsv``
and ``tld_corpus.v2.tsv``, in the same ``label<TAB>text`` format the trainer
already reads.  The originals are never touched.  The trainer is pointed at the
v2 pair and no longer loads ``chat_corpus.txt`` separately, because the chat log
is now folded into the v2 files - that is what lets the six advertising lines
that were sitting in an "all clean" log be excluded instead of taught as clean.

Run:  python antiad/build_corpus_v2.py
"""

import collections
import os
import pathlib
import random
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent

SEED = 20260918
MAX_TOKENS = 24          # L2's window; longer rows are front-truncated by the model
MAX_FRAG_WORDS = 8       # "a leading 1-8 word slice"
PER_WORD_CAP = 900      # most rows any single opening token may own
PER_WORD_FLOOR = 15      # ... and the least, so thin openers still get examples
PLAIN_HOST_ROWS = 0  # benign-hostname rows opened by non-advertising chat
EXTRA_WINDOWS = 5000 # real-chat filler negatives (capped)
NEG_TARGET = 24700       # negatives wanted, before the train/val split
TRIGRAM = 3

TOKEN_RE = re.compile(r"[a-z0-9']+")
WORD_SPAN_RE = re.compile(r"[A-Za-z0-9']+")

# The words the invariant names.  POS_OPENERS currently open 0 positives and
# 2,247 negatives between them; NEG_OPENERS open 12,238 positives and 427.
POS_OPENERS = ["the", "ok", "and", "it", "just", "then", "anyone", "bro", "im", "tpa"]
NEG_OPENERS = ["join", "play", "come", "try", "visit", "ip", "free", "check", "hop",
               "server", "best", "we", "my", "new", "pvp"]
NAMED = POS_OPENERS + NEG_OPENERS

# A line of an "all clean" chat log that is in fact an advert.  Used ONLY to
# exclude such lines, never to generate text.  Deliberately narrow: a bare verb
# near a noun ("i can join on his acc but he cant join at all" is a real clean
# line) must not be swept in, or the log loses its own counterexamples.
PROMO_RES = [
    re.compile(r"\bjoin (my|our|the|a) ?(server|smp|realm)\b", re.I),
    re.compile(r"\bip in (my )?bio\b", re.I),
    re.compile(r"\bip in bio\b", re.I),
    re.compile(r"\bdm me\b.{0,24}\b(disc|discord|acc|account|rank|sell)", re.I),
    re.compile(r"\b(disc|discord)\b.{0,12}\b(dm|msg|message) me\b", re.I),
    re.compile(r"\bselling (my|acc|account)\b.{0,24}\b(dm|disc|discord)\b", re.I),
    re.compile(r"\b(best server|free ranks|free op|new server|come play)\b", re.I),
    re.compile(r"\b(join|come|play|visit|check|hop on)\b.{0,20}\b(n?o?w|fast|today|guys)\b"
               r".{0,20}\b(server|smp|realm)\b", re.I),
]

# Fragments that must never be used to open a NEGATIVE row: prefixing a benign
# hostname mention with "join my server" would manufacture an advert labelled
# clean, which is the exact opposite of what this rewrite is for.
FRAG_BAN = re.compile(
    r"\bjoin (my|our|the|a) ?(serv|server|smp|realm)\b"
    r"|\bip in bio\b|\bip\b.{0,8}\bbio\b|\bdm me\b|\bfree ranks\b"
    r"|\bselling (my|acc|account)\b|\bbest server\b|\bcome play\b", re.I)



# Filtering the chat log before it is mined for negatives.  "All clean" is a
# claim about the file, not about every line in it: this log really does contain
# server adverts ("guys join icemc.pro now", "join my server play.goonmc.goon"),
# and importing those as clean is the same mistake the shipped corpus made.
# Three rules, applied as a union:
#
#   1. the shipped L1 regex, ChatGuardModule.advertHit, dumped to a file by
#      antiad/ChatMineFilter.java - the method that ships, not a copy.  It is
#      coarse (it flags "tpa for mystery boxes best prices", which is trade spam
#      and legitimately clean for a *server*-advertising classifier), so it is
#      used only as an extra exclusion, never as the definition.
#   2. a dotted host, an IPv4, a host:port or a discord invite.  Deliberately
#      stricter than the IANA list, because the real defects are the ones with
#      no real TLD at all - "play.goonmc.goon", "raad.dih.stinks".
#   3. an allowlist, so a dot-regex does not eat good negatives: file extensions
#      ("latest.logs", "world.schem"), profanity evasion where the dot is doing
#      the evading ("fu.ck", "dr.sex"), and contractions ("I'm.gone").
DOTTED = re.compile(r"[a-z0-9][a-z0-9-]{1,62}\.[a-z][a-z0-9-]{1,62}", re.I)
IPV4 = re.compile(r"\b\d{1,3}(?:\.\d{1,3}){3}\b")
HOST_PORT = re.compile(r"\b[a-z0-9-]{2,63}(?:\.[a-z0-9-]{2,63})+:\d{1,5}\b", re.I)
INVITE = re.compile(r"\bdiscord(?:\.gg|\.com/invite)/[A-Za-z0-9-]+", re.I)
FILE_EXTS = {"log", "schem", "schematic", "yml", "yaml", "json", "txt", "jar", "zip",
             "png", "jpg", "jpeg", "gif", "cfg", "toml", "properties", "exe", "msi",
             "java", "class", "py", "sh", "bat", "md", "csv", "dat", "nbt",
             "mcfunction", "mcmeta", "mp3", "mp4", "wav", "avi", "pdf", "rar", "7z",
             "lock", "old", "bak", "tmp", "db", "sql", "ini"}
PROFANITY = ("fuck", "fuk", "fck", "sex", "shit", "sht", "bitch", "dick", "cock",
             "cunt", "piss", "faggot", "nigger", "nigga", "rape", "cum", "porn",
             "pedo", "pedophile", "tits", "boob", "slut", "whore")


def advert_hit_lines():
    """Lines the shipped advertHit flags, dumped by antiad/ChatMineFilter.java."""
    path = HERE / "chat_flagged.txt"
    if not path.exists():
        return set()
    out = set()
    for line in path.open(encoding="utf-8", errors="replace"):
        line = line.rstrip("\n").rstrip("\r")
        if line and not line.startswith("!"):
            out.add(line.strip())
    return out


def _dotted_allowed(m, text):
    head, tail = m.group(0).split(".", 1)
    if tail.lower().rstrip("s") in FILE_EXTS:
        return True
    if m.start() > 0 and text[m.start() - 1] in "'":
        return True
    blob = re.sub(r"[^a-z]", "", (head + tail).lower())
    return any(p in blob for p in PROFANITY)


def mined_advert(text, hits=None):
    """True if a line of a chat log must not be mined as a negative."""
    if hits and text.strip() in hits:
        return True
    if INVITE.search(text) or IPV4.search(text) or HOST_PORT.search(text):
        return True
    for m in DOTTED.finditer(text):
        if not _dotted_allowed(m, text):
            return True
    return False


def tokens(s):
    return TOKEN_RE.findall(s.lower())


def trigrams(ts):
    return set(tuple(ts[i:i + TRIGRAM]) for i in range(len(ts) - TRIGRAM + 1))


def read_tsv(path):
    rows = []
    for line in path.open(encoding="utf-8"):
        line = line.rstrip("\n").rstrip("\r")
        if "\t" not in line:
            continue
        label, text = line.split("\t", 1)
        text = text.strip()
        if not text:
            continue
        rows.append((label.strip().lower(), text))
    return rows


def read_iana_tlds():
    path = HERE / "tld_iana.txt"
    if not path.exists():
        return set()
    out = set()
    for line in path.open(encoding="utf-8"):
        t = line.strip().lower().lstrip(".")
        if t and not t.startswith("#"):
            out.add(t)
    return out


def leading_windows(text, limit=MAX_FRAG_WORDS):
    """The whole line plus every leading 1..limit word slice of it, verbatim.

    Slices are cut on the raw string so the fragment is the player's own text
    with only its trailing words removed - no re-casing, no punctuation edits.
    """
    out = [text]
    spans = [(m.start(), m.end()) for m in WORD_SPAN_RE.finditer(text)]
    for k in range(1, min(limit, len(spans)) + 1):
        out.append(text[:spans[k - 1][1]].strip())
    return out


def interior_windows(text, wanted, limit=MAX_FRAG_WORDS):
    """Windows that start on one of ``wanted`` when it sits inside the line."""
    spans = [(m.start(), m.end()) for m in WORD_SPAN_RE.finditer(text)]
    toks = [text[a:b].lower() for a, b in spans]
    out = []
    for i, tok in enumerate(toks):
        if i == 0 or tok not in wanted:
            continue
        end = spans[min(len(spans) - 1, i + limit - 1)][1]
        out.append(text[spans[i][0]:end].strip())
    return out


class Corpus:
    def __init__(self):
        self.pos_in = read_tsv(HERE / "seed_corpus.tsv")
        self.pos_in += read_tsv(HERE / "tld_corpus.tsv")
        self.chat = [l.strip() for l in
                     (ROOT / "chat_corpus.txt").open(encoding="utf-8") if l.strip()]

    def split(self):
        pos = []
        neg = []
        for label, text in self.pos_in:
            (neg if label == "clean" else pos).append(text)
        return dedup(pos), dedup(neg)


def dedup(rows):
    seen = set()
    out = []
    for r in rows:
        key = " ".join(tokens(r))
        if key and key not in seen:
            seen.add(key)
            out.append(r)
    return out


def main():
    rng = random.Random(SEED)
    tlds = read_iana_tlds()
    corpus = Corpus()
    pos_orig, neg_orig = corpus.split()
    chat = corpus.chat

    # -- 0. which "clean" chat lines are actually adverts --------------------
    hits = advert_hit_lines()

    def has_real_tld(text):
        for w in re.findall(r"[a-z0-9][a-z0-9-]*\.[a-z]{2,}", text.lower()):
            if w.rsplit(".", 1)[1] in tlds:
                return w
        return None

    chat_ad = []
    chat_clean = []
    for line in chat:
        if has_real_tld(line) or any(r.search(line) for r in PROMO_RES) \
                or mined_advert(line, hits):
            chat_ad.append(line)
        else:
            chat_clean.append(line)

    # -- 1. fragment pool, indexed by opening token -------------------------
    frags = collections.defaultdict(list)      # opener -> [fragment, ...]
    for line in chat_clean:
        for frag in leading_windows(line):
            ts = tokens(frag)
            if ts:
                frags[ts[0]].append(frag)
    for line in chat_clean:
        for frag in interior_windows(line, set(NEG_OPENERS)):
            ts = tokens(frag)
            if ts:
                frags[ts[0]].append(frag)
    for k in frags:
        frags[k] = sorted(set(frags[k]))

    # Fragments safe to put in FRONT of a negative: real chat that carries no
    # promotional collocation, because the result is a row labelled clean.
    safe_frags = collections.defaultdict(list)
    for opener, supply in frags.items():
        for frag in supply:
            if not FRAG_BAN.search(frag):
                safe_frags[opener].append(frag)
    frags_safe_all = sorted({f for v in safe_frags.values() for f in v})

    # -- 2. negative pool ----------------------------------------------------
    neg_pool = collections.defaultdict(list)   # opener -> [text, ...]
    for line in chat_clean:
        ts = tokens(line)
        if ts and len(ts) <= MAX_TOKENS:
            neg_pool[ts[0]].append(line)
    for line in chat_clean:
        for frag in interior_windows(line, set(NEG_OPENERS)):
            ts = tokens(frag)
            if ts and len(ts) <= MAX_TOKENS and not FRAG_BAN.search(frag):
                neg_pool[ts[0]].append(frag)
    for text in neg_orig:
        ts = tokens(text)
        if ts and len(ts) <= MAX_TOKENS:
            neg_pool[ts[0]].append(text)

    # Extra real chat windows, at the lengths chat actually uses, opening on any
    # word the invariant does not name.  The negative class has to be as large as
    # the positive class for the base rate to hold, and this is the only filler
    # that is real chat, carries no address and has no advertising shape: the
    # alternative - prefixing a benign hostname with chat - teaches "chat words
    # followed by an address are clean", which is exactly the pattern the
    # positional pairs test.  Measured: that variant cost 0.98 of pair score.
    named_set = set(NAMED)
    extra_windows = []
    for line in chat_clean:
        spans = [(m.start(), m.end()) for m in WORD_SPAN_RE.finditer(line)]
        toks = [line[a:b].lower() for a, b in spans]
        for i in range(len(toks)):
            if toks[i] in named_set:
                continue
            end = spans[min(len(spans) - 1, i + MAX_FRAG_WORDS - 1)][1]
            window = line[spans[i][0]:end].strip()
            if len(tokens(window)) <= MAX_TOKENS and not FRAG_BAN.search(window):
                extra_windows.append(window)
    extra_windows = dedup(extra_windows)
    rng.shuffle(extra_windows)
    for row in extra_windows[:EXTRA_WINDOWS]:
        neg_pool[tokens(row)[0]].append(row)

    # transformation 3: a benign hostname carried into ordinary chat.  The
    # fragment in front must be ordinary chat, not a pitch, or the row would be
    # an advert labelled clean.
    hosts = [t for t in neg_orig if has_real_tld(t) and len(tokens(t)) <= MAX_TOKENS]
    host_rows = []
    for w in NEG_OPENERS:
        supply = safe_frags.get(w, [])
        if not supply:
            continue
        # Top up to the cap, never past it: rows opening with a named word that
        # are past the cap would drag P(advertising | w) BELOW the base rate,
        # which is the same defect with the sign flipped.
        want = max(0, PER_WORD_CAP - len(neg_pool.get(w, [])))
        if want == 0:
            continue
        made = 0
        for i in range(want):
            frag = supply[i % len(supply)]
            host = hosts[rng.randrange(len(hosts))]
            for sep, order in ((", ", "frag-host"), (" and ", "frag-host"),
                               (" ", "host-frag"), (". ", "host-frag")):
                if made >= want:
                    break
                row = frag + sep + host if order == "frag-host" else host + sep + frag
                if len(tokens(row)) <= MAX_TOKENS and tokens(row)[0] == w:
                    host_rows.append(row)
                    made += 1
        if made == 0:
            continue
    for row in host_rows:
        neg_pool[tokens(row)[0]].append(row)

    # Host rows opened by chat that is NOT advertising vocabulary: the bulk of
    # the negative class, and the only filler that cannot move a named word's
    # prior.  This is what makes the class balance reachable without either
    # starving the log or over-weighting any single opener.
    plain_hosts = []
    plain_openers = [f for f in frags_safe_all if tokens(f) and tokens(f)[0] not in
                     set(NAMED)]
    for i in range(PLAIN_HOST_ROWS):
        frag = plain_openers[(i * 17) % len(plain_openers)]
        host = hosts[rng.randrange(len(hosts))]
        for sep, order in ((" ", "frag-host"), (", ", "frag-host"),
                           (" ", "host-frag"), (". ", "host-frag")):
            row = frag + sep + host if order == "frag-host" else host + sep + frag
            if len(tokens(row)) <= MAX_TOKENS:
                plain_hosts.append(row)
                break
    for row in plain_hosts:
        neg_pool[tokens(row)[0]].append(row)

    # -- 3. the negatives whose opening token the invariant names -----------
    # Taken first and kept whole: P(advertising | opener) for these words is what
    # the positive side is then built to match.  The cap keeps one word ("tpa",
    # 775 real chat lines) from dominating the class.
    named_negs = []
    kept = set()
    for w in NAMED:
        supply = dedup(neg_pool.get(w, []))
        rng.shuffle(supply)
        take = min(len(supply), PER_WORD_CAP)
        for row in supply[:take]:
            if row not in kept:
                kept.add(row)
                named_negs.append(row)
    rest_negs = []
    for w, rows in neg_pool.items():
        if w in NAMED:
            continue
        rest_negs.extend(rows)
    # Deliberately NOT the named-opener overflow: a row that opens with "join"
    # and is labelled clean still moves P(advertising | "join"), and past the cap
    # it moves that prior down towards zero - the same positional collapse with
    # the sign flipped.  The class is filled from non-named openers instead.
    rest_negs = [r for r in dedup(rest_negs) if r not in kept]
    rng.shuffle(rest_negs)
    neg_open = collections.Counter(tokens(t)[0] for t in named_negs)

    # -- 4. choose the positives --------------------------------------------
    pos_by_open = collections.defaultdict(list)
    for text in pos_orig:
        ts = tokens(text)
        if ts:
            pos_by_open[ts[0]].append(text)
    short_orig = [t for t in pos_orig if tokens(t) and len(tokens(t)) <= MAX_TOKENS]

    # 4b. top up any shortfall with the ad moved behind a chat fragment
    queue = list(short_orig)
    rng.shuffle(queue)

    # P(advertising | opener) is what the invariant constrains, so the positive
    # count for a named opener is the negative count for that opener.  Where the
    # corpus has no negative supplier at all ("visit" opens 724 positive rows
    # and not one row of any kind in any real log in this workspace) the floor
    # applies and the prior stays at 1.0 - measured and reported, not hidden.
    pos_target = {}
    for w in NAMED:
        pos_target[w] = max(PER_WORD_FLOOR, min(PER_WORD_CAP, neg_open.get(w, 0)))

    separators = [" ", ", ", " and ", ". ", " but "]
    positives = []
    used_open = collections.Counter()

    def by_budget(frags):
        """frags bucketed by token length, so a lookup is O(1) not a rescan."""
        buckets = [[] for _ in range(MAX_TOKENS + 1)]
        for f in frags:
            n = len(tokens(f))
            if 1 <= n <= MAX_TOKENS:
                buckets[n].append(f)
        for b in buckets:
            b.sort()
        return buckets

    def pick(buckets, budget, key):
        if budget < 1:
            return None
        for b in range(min(budget, MAX_TOKENS), 0, -1):
            if buckets[b]:
                return buckets[b][key % len(buckets[b])]
        return None

    safe_all_buckets = by_budget(frags_safe_all)
    named_buckets = {w: by_budget(safe_frags.get(w, [])) for w in NAMED}
    plain_frags = [f for f in frags_safe_all if tokens(f) and tokens(f)[0] not in set(NAMED)]
    plain_buckets = by_budget(plain_frags)

    def prefixed(ad, w, k):
        """ad with a real chat fragment whose first word is w in front of it.

        One token of the budget is reserved for the separator: " and " and
        " but " are tokens too, and a row that overshoots MAX_TOKENS is silently
        front-truncated by the model, which would undo the re-opening.  The
        first v2 build forgot this and lost 416 advertising rows outright.
        """
        budget = MAX_TOKENS - len(tokens(ad)) - 1
        frag = pick(named_buckets[w], budget, k * 7 + len(ad))
        if frag is None:
            return None
        sep = separators[(k // 5) % len(separators)]
        row = frag + sep + ad
        if len(tokens(row)) > MAX_TOKENS:
            row = frag + " " + ad
        return row if len(tokens(row)) <= MAX_TOKENS else None

    # 4a. rows whose own opening token already is the one asked for
    for w in NAMED:
        pool = pos_by_open.get(w, [])
        rng.shuffle(pool)
        for ad in pool[:pos_target[w]]:
            if used_open[w] >= pos_target[w]:
                break
            positives.append(ad)
            used_open[w] += 1

    # 4b. top up any shortfall with the ad moved behind a chat fragment
    queue = list(short_orig)
    rng.shuffle(queue)
    for w in NAMED:
        k = 0
        while used_open[w] < pos_target[w] and k < 60 * (pos_target[w] + 1):
            ad = queue[k % len(queue)]
            k += 1
            if tokens(ad)[0] == w:
                continue
            row = prefixed(ad, w, k)
            if row is None:
                continue
            positives.append(row)
            used_open[w] += 1

    # 4c. the remainder: every remaining real ad row, once.  An ad whose opener
    #     is a named word that has already met its target is re-opened behind
    #     ordinary chat rather than dropped - that is the whole point of the
    #     rewrite, and dropping them instead is what cost the first v2 build a
    #     third of its "smp" rows.  The re-opening uses fragments whose own
    #     opener is NOT a named word, so it cannot push a named word's prior
    #     back off the base rate.  Ads left verbatim are those whose opener the
    #     invariant does not name - typo'd hostnames ("c0m3", "pvpz0n3") and are
    #     genuine advertising evidence, so they are kept as they are.
    sep_no = [" ", ", ", " and ", ". "]
    # 4c stops at the negative supply, so the class balance holds without ever
    # inventing a negative or exceeding the trainer's 5 MB model budget.  Only
    # the tail is trimmed; 4a/4b are what carry the per-opener invariant.
    pos_budget = len(dedup(named_negs)) + len(rest_negs)
    for i, ad in enumerate(queue):
        if len(positives) >= pos_budget:
            break
        ts = tokens(ad)
        if not ts:
            continue
        w = ts[0]
        if w not in NAMED or used_open[w] < pos_target[w]:
            positives.append(ad)
            used_open[w] += 1
            continue
        frag = pick(plain_buckets, MAX_TOKENS - len(ts) - 1, i * 13 + len(ad))
        if frag is None:
            continue
        row = frag + sep_no[i % len(sep_no)] + ad
        if len(tokens(row)) > MAX_TOKENS:
            row = frag + " " + ad
        if len(tokens(row)) <= MAX_TOKENS:
            positives.append(row)
            used_open[tokens(row)[0]] += 1

    positives = dedup(positives)
    negs = dedup(named_negs)[:len(positives)]
    n_from_rest = len(positives) - len(negs)
    if n_from_rest > 0:
        negs.extend(rest_negs[:n_from_rest])
    rng.shuffle(positives)
    rng.shuffle(negs)

    # -- 5. write -----------------------------------------------------------
    half = len(positives) // 2
    out_a = HERE / "seed_corpus.v2.tsv"
    out_b = HERE / "tld_corpus.v2.tsv"
    with out_a.open("w", encoding="utf-8", newline="\n") as fa, \
            out_b.open("w", encoding="utf-8", newline="\n") as fb:
        for i, text in enumerate(positives):
            (fa if i < half else fb).write("advertising\t%s\n" % text)
        for i, text in enumerate(negs):
            (fa if i < half else fb).write("clean\t%s\n" % text)

    # -- 6. report ----------------------------------------------------------
    print("chat lines                 %6d" % len(chat))
    print("  excluded as real adverts %6d" % len(chat_ad))
    for line in chat_ad[:12]:
        print("    %s" % line[:96].encode("ascii", "replace").decode("ascii"))
    print("positives                  %6d  (from %d originals)"
          % (len(positives), len(pos_orig)))
    print("negatives                  %6d  (chat %d + corpus %d + host variants %d)"
          % (len(negs), len(chat_clean), len(neg_orig),
             len(host_rows) + len(plain_hosts)))
    print("distinct positive openers  %6d" % len(pos_by_open))
    print("distinct negative openers  %6d" % len(neg_open))
    print()

    before_pos = collections.Counter(tokens(t)[0] for t in pos_orig)
    before_neg = collections.Counter(tokens(t)[0] for t in neg_orig)
    before_neg.update(tokens(t)[0] for t in chat)
    after_pos = collections.Counter(tokens(t)[0] for t in positives)
    after_neg = collections.Counter(tokens(t)[0] for t in negs)

    rows = []
    print("%-8s %8s %8s %7s | %8s %8s %7s" %
          ("word", "pos_bef", "neg_bef", "P_bef", "pos_aft", "neg_aft", "P_aft"))
    for w in NAMED:
        pb, nb = before_pos.get(w, 0), before_neg.get(w, 0)
        pa, na = after_pos.get(w, 0), after_neg.get(w, 0)
        f = lambda a, b: (a / (a + b)) if (a + b) else float("nan")
        print("%-8s %8d %8d %7.3f | %8d %8d %7.3f"
              % (w, pb, nb, f(pb, nb), pa, na, f(pa, na)))
        rows.append((w, pb, nb, f(pb, nb), pa, na, f(pa, na)))

    table = HERE / "corpus_v2_prior.tsv"
    with table.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write("word\tpos_before\tneg_before\tp_before\tpos_after\tneg_after\tp_after\n")
        for r in rows:
            fh.write("%s\t%d\t%d\t%.4f\t%d\t%d\t%.4f\n" % r)
    print()
    print("base rate after            %.3f" % (len(positives) / (len(positives) + len(negs))))
    print("wrote %s, %s, %s" % (out_a.name, out_b.name, table.name))

    return 0


if __name__ == "__main__":
    sys.exit(main())
