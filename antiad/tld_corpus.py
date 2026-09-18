"""Builds training rows that teach the classifier every TLD, not just .com.

The shipped TLD list is deliberately short, and that is a bypass: the pitch
"join my server at coolpvp.<tld>" works from any of the ~1,400 delegated
TLDs, and the obscure ones are exactly where free hosts and throwaway servers
live. A classifier trained on .com/.net/.gg only has never seen those shapes.

Two halves, and the second half matters more than the first:

  advertising  each TLD used in an invitation, across the frames and the
               obfuscations the pipeline normalizes (written dot, spaced
               letters, leet, hyphen subdomains)
  clean        each TLD used in a benign mention - docs, a video, a bad
               website someone is complaining about. Without these the model
               would learn "unfamiliar TLD = advertising" and start muting
               people for mentioning a niche site in passing, which is the
               false positive that gets a filter turned off.

Output: tld_corpus.tsv, label<TAB>text, read by TrainMillennium alongside
seed_corpus.tsv.
"""
import io
import pathlib
import random
import sys

HERE = pathlib.Path(__file__).parent
IANA = HERE / "tld_iana.txt"
OUT = HERE / "tld_corpus.tsv"

# Generic server-name stems. Deliberately bland - the TLD is the variable
# under test, so the stem must not itself be a giveaway.
STEMS = [
    "coolpvp", "funserver", "craftkingdom", "survivalcraft", "minescape",
    "pvpzone", "skymc", "anarchy", "bedwars", "dragonmc", "goldrush",
    "icecraft", "junglemc", "lunarmc", "nethermc", "emerald", "foxcraft",
    "playmc", "mcserver", "bestpvp", "opcraft", "megasmp", "craftmoon",
]

AD_FRAMES = [
    "join {host}", "come to {host}", "play on {host}", "try {host}",
    "best server is {host}", "free ranks at {host}", "everyone join {host}",
    "new server {host}", "server ip is {host}", "ip {host}",
    "my server is {host}", "we moved to {host}", "visit {host}",
    "pvp server {host}", "looking for players at {host}",
    "hop on {host}", "check out {host}", "come play {host}",
]

# Benign sentences that mention a host without inviting anyone to it. The
# complaint/negative ones are the hardest and the most valuable.
CLEAN_FRAMES = [
    "the docs at {host} explain it", "{host} is a bad website lol",
    "i read it on {host}", "that {host} tutorial helped",
    "the wiki at {host} is old", "check {host} later",
    "someone linked {host} in a video", "the mod is hosted on {host}",
    "{host} loads slowly for me", "is {host} down for anyone else",
    "i found the answer on {host}", "the changelog is at {host}",
    "that image is from {host}", "{host} is not a minecraft site",
    "google it or look at {host}", "i saw a thread on {host}",
    "the issue is tracked at {host}", "their blog {host} is interesting",
]

CLEAN_HOST_STEMS = [
    "reddit", "youtube", "github", "wikipedia", "stackoverflow", "papermc",
    "spigotmc", "curseforge", "modrinth", "planetminecraft", "imgur",
    "twitch", "medium", "pastebin", "namemc", "fandom", "vimeo", "steam",
]

COMMON = {"com", "net", "org", "io", "gg", "co", "uk", "xyz", "pro", "me"}


def obfuscations(host: str):
    """The same host in the disguises normalization is designed to undo."""
    name, _, tld = host.partition(".")
    out = [host]
    if tld:
        out.append(f"{name} dot {tld}")
        out.append(f"{name} (dot) {tld}")
        out.append(" ".join(host))
        leet = name.replace("o", "0").replace("i", "1").replace("e", "3")
        if leet != name:
            out.append(f"{leet}.{tld}")
    return out


def main():
    if not IANA.exists():
        print(f"missing {IANA} - download the IANA list first", file=sys.stderr)
        return 1
    tlds = [line.strip().lower() for line in io.open(IANA, encoding="utf-8")
            if line.strip() and not line.startswith("#")]
    tlds = [t for t in tlds if t.isascii() and t.isalpha() and t.islower()]
    print(f"{len(tlds)} TLDs")

    rng = random.Random(20260918)
    rows = []

    # Every TLD gets advertising coverage; rare TLDs get a second pass because
    # they are the ones the model has no other reason to have seen.
    for tld in tlds:
        passes = 1 if tld in COMMON else 2
        for _ in range(passes):
            host = f"{rng.choice(STEMS)}.{tld}"
            for frame in rng.sample(AD_FRAMES, 4):
                rows.append(("advertising", frame.format(host=host)))
            rows.append(("advertising", obfuscations(host)[-1]))

    for tld in tlds:
        host = f"{rng.choice(CLEAN_HOST_STEMS)}.{tld}"
        for frame in rng.sample(CLEAN_FRAMES, 3):
            rows.append(("clean", frame.format(host=host)))

    rng.shuffle(rows)
    with io.open(OUT, "w", encoding="utf-8", newline="\n") as f:
        for label, text in rows:
            f.write(f"{label}\t{text}\n")
    ads = sum(1 for label, _ in rows if label == "advertising")
    print(f"wrote {OUT}: {len(rows)} rows ({ads} advertising / {len(rows)-ads} clean)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
