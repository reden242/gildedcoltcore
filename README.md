# ColtCore and GildedCore — the anti-advertising filter, rebuilt on a text-native Millennium 5

This workspace contains two independent Paper plugins:

- `ColtCore/` — ColtCore, brand color `#00FF00`
- `GildedCore-final/` — GildedCore, brand color `#EEBB01`

The two trees are kept in lockstep: every change is applied to both, modulo
package/branding renames (`com.coltcore.*` ↔ `com.gildedmc.*`, `coltcore.net` ↔
`gildedmc.net`). The trained model file is **byte-identical** in both jars.

Version 1.14.0. Java 21, Paper 1.21.4.

---

## What changed in this pass

1. **The classifier was rebuilt on the Millennium 5 engine.** Layer 2 and layer 3
   no longer share a fastText-shaped bag-of-n-grams model. They share
   `MillenniumNet`, a text-native port of the Millennium 5 recurrent engine
   (see §2).
2. **The HTTP LLM is gone.** Layer 3 was an OpenAI-compatible call to a
   `millenium-5` server on loopback. It is now the same in-process classifier
   reading the player's recent lines. No endpoint, no API key, no timeout, no
   rate limit, no sidecar to keep alive, and no thread parked on a socket.
3. **CheatDetector is gone**, from the code and from both configs, and
   `ConfigUpdater` strips it out of a live server's own `config.yml` on start.

---

## 1. The pipeline, as it actually runs

```
Stage 0  normalize      advertForm(): lowercase, zero-width strip, homoglyph fold,
                        disguised-dot reconstruction, leet fold, spaced-keyword collapse
   ↓
L1       pattern scan   IPv4 (:port), domains, obfuscated dots, Discord invites.
                        No hit → the message passes here, at zero model cost.
   ↓
L2       classifier     MillenniumNet on the message alone (≤24 tokens).
                        ≥ 0.85 → block.  < 0.40 → pass, logged.  between → L3.
   ↓
L3       context        MillenniumNet on the message plus the player's last 5 lines.
   ↓
L4       fallback       LocalContextAggregator, the pre-existing heuristics.
```

Six surfaces route through the same screen: chat, signs (lines joined), books
(pages + title), anvil renames (block only, no punishment — it fires per
keystroke), item lore, and configured private-message commands.

**Fail-open by construction.** A missing or unreadable model disables L2 and L3
and the message passes. Nothing in the classifier can punish on a failure.

### Decision matrix

| Condition | Action |
|---|---|
| No L1 hit, `scan-mode: always`, L3 confident (≥0.85) or lift rule fires | block, `flag-model-scan` |
| No L1 hit, `scan-mode: always`, L3 no opinion | pass |
| No L1 hit, `scan-mode: band` | pass, no model cost |
| L1 hit, L2 ≥ 0.85 | block |
| L1 hit, L2 < 0.40 | pass, logged (`pass-l2-clear`) |
| L2 in 0.40–0.85, L3 flags ≥ 0.60 | block, `flag-l3`, survives the regex double-check |
| L2 in 0.40–0.85, L3 answers and clears | pass, logged (`pass-l3-clear`) |
| L2 in 0.40–0.85, L3 unavailable | L4 aggregator decides |
| Model missing or fails to load | L2 + L3 off, legacy `LinearModel` path |

Every decision is written to `antiad.db` (`antiad_log`) with the surface, the
raw text, the L1 evidence, the L2 probability, the L3 reason and the action.

---

## 2. The engine — Millennium 5, rebuilt for text

The engine is a reimplementation of the Millennium 5 network from
[kireikosasha/MX-Project](https://github.com/kireikosasha/MX-Project)
(`kireiko.dev.millennium.ml`), not a vendored copy. Its stock input contract is
movement-specific — `ObjectML[0]` is yaw deltas, `ObjectML[1]` pitch deltas,
features are `tanh(yaw / 100)` and `hypot` magnitudes over a hardcoded 16-entry
`SCALERS` table — and none of that transfers to a chat message, so the input
side is new while the network and the optimiser are the original design:

| kept from Millennium 5 | replaced for text |
|---|---|
| stacked Bi-LSTM, 2 layers | one timestep per **word**, not per tick |
| attention pooling over timesteps | token vector = word row **+ mean of its hashed char-4-gram rows** |
| LayerNorm | FNV-1a 32-bit unsigned over UTF-8, `row = hash % 4096` |
| AdamW | **no padding and no mask** — attention runs over real timesteps only |
| label smoothing 0.05, gradient clip 5.0 | truncation keeps the **tail**, not the head |

Two of those choices are load-bearing:

- **Subword rows.** A host on a TLD the model never saw still produces a
  sensible vector from how it is spelled, which is why 1,438-TLD coverage
  generalises instead of being memorised.
- **No mask.** There is no mask anywhere to get wrong, and one set of weights
  serves both a single line and a six-line context window — which is exactly
  what the two callers need.

### Shape and size

| | |
|---|---|
| vocabulary | **3,828 words** (+1 unknown row) |
| subword buckets | 4,096 |
| embedding / hidden | 48 / 48 |
| Bi-LSTM layers | 2 |
| char n-gram length | 4 |
| parameters | **473,905** |
| on disk | **1.83 MB** |
| temperature | **1.00** |

---

## 3. What was measured

The model ships a training split of `antiad/seed_corpus.tsv` (20,280 rows),
`antiad/tld_corpus.tsv` (16,681 generated rows covering every IANA-delegated
TLD) and `chat_corpus.txt` (11,676 real clean chat lines), stratified 90/10.

`java TrainMillennium eval antiad/antiad.m5.bin` on the held-out 4,863 samples
(2,408 advertising, 2,455 clean):

| | |
|---|---|
| precision | **1.0000** |
| recall | **1.0000** |
| clean false-flag rate | **0.0000** |
| advertising min (p01) | **0.969** |
| clean max | **0.026** |
| advertising at or above the 0.85 block threshold | **100.000%** |
| clean at or above it | **0.000%** |

Complete separation on this split. That is a statement about the corpus, not
about your players — see §5.

### Rare-TLD spot check, real model, real weights

`java -cp "ColtCore/target/classes;antiad" antiad/TldCheck.java /antiad.m5.bin`

```
AD rare tld      0.973  join coolpvp.zip
AD rare tld      0.973  come play minescape.museum
AD rare tld      0.973  server ip is dragonmc.abbott
AD obfuscated    0.920  server ip is d r a g o n m c . m u s e u m
CLEAN rare tld   0.025  the docs at papermc.museum explain it
CLEAN rare tld   0.024  reddit.zw is a bad website lol
CLEAN rare tld   0.024  is github.pnc down for anyone else
CLEAN mention    0.024  i saw a thread on stackoverflow.abbott
```

Every advertising line clears the 0.85 block threshold; every benign line is
below the 0.40 clear threshold. No line lands in the band.

---

## 4. Layer 3, and a design that was thrown away

The obvious way to give L3 context is to feed `context + message` as one
sequence. **That was built, measured and discarded**, and it failed in both
directions:

| case | concatenated | message alone |
|---|---:|---:|
| innocent `ok` after five advertising lines | **0.804** — would be flagged | clean |
| `wanna join my smp? ip in bio` after ordinary chat | **0.183** — missed | 0.741 |

The cause is arithmetic, not tuning: five context lines are about twenty tokens
against a three-token reply, so the context wins the pooled representation. A
short message cannot outvote a long context.

L3 is **message-anchored** instead. The message is scored on its own at the same
0.85 / 0.40 thresholds L2 uses, and context has to earn its influence:

1. **Message ≥ 0.85 → flag.** Not a restatement of L2: the band caller scores
   the *normalised* text, and the scan-all caller has not scored the message at
   all — it arrives here precisely because no cheaper layer found an address.
2. **Message in 0.40–0.85 *and* at least half the context lines are themselves
   ≥ 0.85 → flag.** An oddity in isolation is not worth acting on; the same
   oddity in a run of adverts is a pitch.
3. **Otherwise, flag only if appending the message *raises* the advertising
   score of what precedes it** (lift ≥ 0.35, combined ≥ 0.85). Requiring the
   score to *rise*, rather than to be high, is what protects the innocent reply:
   the advertising context already scores high and the reply does not lift it.

### Honest status of rules 2 and 3

**On the current corpus, rules 2 and 3 never fire.** All six context cases
resolve on rule 1 alone:

```
FLAG  confident     msg 0.951  adCtx 0.00  lift -0.000  [wanna join my smp? ip in bio]
pass  clean + lift  msg 0.023  adCtx 1.00  lift -0.189  [lol no thanks]
pass  clean + lift  msg 0.024  adCtx 1.00  lift -0.016  [ok]
pass  clean + lift  msg 0.024  adCtx 0.00  lift -0.000  [reddit.com is a bad website lol]
pass  clean + lift  msg 0.023  adCtx 1.00  lift -0.616  [anyone wanna buy my diamonds]
FLAG  confident     msg 0.973  adCtx 0.20  lift +0.017  [play.coolpvp.xyz]
```

The model separates the training distribution so completely that no message
lands in the 0.40–0.85 band at all (band mass **0.000%** on the held-out
split). Rules 2 and 3 are therefore **guards against out-of-distribution input
— phrasing the corpus has never seen — not a demonstrated win.** They cost a
few forward passes and cannot fire on text the model is confident about. Do not
read this section as a measurement of them.

The same honesty applies to the earlier temperature question. A previous pass
selected T by maximising ambiguous-band mass, which is a *fastText-era*
requirement; with the recurrent model it compressed every positive into
0.788–0.822 — inside the band — and silently disabled L2 entirely. Training
always assumes `p == sigmoid(logit)`, so **T = 1 is the model's own
calibration**, and the exporter now refuses to ship unless ≥ 90% of
advertising actually reaches the 0.85 block threshold.

---

## 5. Known limits

1. **The held-out numbers are from generated and logged text, not from your
   players.** The TLD corpus is synthetic by construction. Complete separation
   on it says the model learned the shape of a pitch, not that it has seen how
   your community talks.
2. **Rules 2 and 3 are unexercised** (§4). Expect the first real out-of-band
   message to be the first test of them.
3. **`scan-mode: always` scores every message.** It is a handful of forward
   passes over a model under 2 MB, in-process, so there is no network time —
   but it is not free, and a busy server will show it in profiling.
4. **Signs, books and lore inherit the chat path** and therefore the chat
   numbers. No separate benchmark was run for them.
5. **Anvil renames block but never punish.** A rename attempt is not a public
   message and does not escalate the ladder.
6. **The slur path is unaffected by this work** and still ships
   `chat-guard.terms` empty. See `ColtCore/README.md` §3.

---

## 6. CheatDetector removal

Removed from the code and from both configs. What went:

- the six join-gate fields, the `java.util.logging` capture handler, and the
  reflection helper that reached into CheatDetector's classes;
- `onJoinGateMove`, the gate branches in `completeJoin` / `onJoinGateQuit` /
  `onLogin`, and the `runAfterCheatDetector` hand-off in
  `IntegratedCoreModuleX`;
- `cheatdetector-loading` from both `config.yml` files.

What deliberately stayed: `joinPacketIsolation.unlock`, `VanishSupport`,
`join-alerts`, and the `IntegratedCoreModuleX` join broadcast — all of which
were entangled with the gate but are independent of it.

**Live servers are cleaned on start.** `ConfigUpdater` rebuilds `config.yml`
from the bundled template and strikes three things outright:

```java
"cheatdetector-loading",
"anti-ad.llm",                  // whole section, not its leaves
"anti-ad.l3.min-call-gap-ms"
```

Two details matter and are easy to get wrong:

- **The section is struck whole.** Emptying `anti-ad.llm` would leave an empty
  section, and an empty section is indistinguishable from the damage the old
  updater caused — `isDamaged()` would rebuild the file on *every* start.
- **`anti-ad.l2.model-resource` is migrated, not struck.** The key survives; only
  its value is dead. The carry loop would otherwise faithfully preserve
  `/antiad.ft.bin` as the owner's choice, producing a live server whose L2
  loads nothing and silently passes everything — the one failure mode that
  looks exactly like a working filter.

---

## 7. Config

`anti-ad:` (config-version **45** in ColtCore / **42** in GildedCore).
`ConfigUpdater` brings live configs forward automatically, with a timestamped
backup in `config-backups/` first.

```yaml
anti-ad:
  own-domain: 'coltcore.net'          # gildedmc.net in GildedCore
  l2:
    enabled: true
    model-resource: '/antiad.m5.bin'  # the MillenniumNet binary
  l3:
    enabled: true
    scan-mode: always                 # 'band' = only the 0.40-0.85 band
    context-messages: 5               # how many previous lines L3 is shown
  log:
    keep-days: 30
```

The `anti-ad.llm` section, `l3.min-call-gap-ms` and `l3.scan-timeout-seconds` no
longer exist and are ignored if present.

---

## 8. Retraining

Training is offline and CPU-only. There are no pretrained vectors to download —
the text-native engine builds its own embeddings from the same corpora.

```powershell
# 0. Optional: refresh the TLD coverage corpus
curl -o antiad/tld_iana.txt https://data.iana.org/TLD/tlds-alpha-by-domain.txt
python antiad/tld_corpus.py

# 1. Dump the seed corpus out of the plugin classes
mvn -f ColtCore/pom.xml compile
javac -cp ColtCore/target/classes -d antiad antiad/DumpCorpus.java
java -cp "antiad;ColtCore/target/classes" DumpCorpus antiad/seed_corpus.tsv

# 2. Train, gate, and write the parity fixture. Trains in about two minutes.
javac -cp ColtCore/target/classes -d antiad antiad/TrainMillennium.java
java -cp "antiad;ColtCore/target/classes" TrainMillennium train antiad/antiad.m5.bin

# 3. Reproduce the numbers in §3 and §4
java -cp "antiad;ColtCore/target/classes" TrainMillennium eval antiad/antiad.m5.bin

# 4. Ship to both trees
copy antiad\antiad.m5.bin ColtCore\src\main\resources\
copy antiad\antiad.m5.bin GildedCore-final\src\main\resources\
```

`train` refuses to write a model unless all four gates pass:

| gate | threshold |
|---|---|
| advertising precision | ≥ 0.97 |
| advertising recall | ≥ 0.90 |
| clean false-flag rate | ≤ 0.10 |
| advertising reaching the 0.85 block threshold | ≥ 0.90 |

The last one is the gate that would have caught the temperature mistake. The
export also fails above a 5 MB budget.

**The trainer and the plugin share `MillenniumNet`.** Tokenisation, hashing and
the forward pass are the same code in both, so training and serving cannot
drift; the saved-then-reloaded model is checked against the in-memory one and
the fixture is written to `antiad/m5-vectors-check.tsv`.

---

## 9. Command permissions — unchanged

`/keyall`, `/kitall` and `/shardall` are operator-only in both plugins
(`default: op` in `plugin.yml`, plus the `COMMAND_PERMISSIONS` map and the
`authorise()` gate in each main class). A normal player cannot run them, and a
`ProxiedCommandSender` — a player proxying via `/execute` — is refused like any
other player. Console remains allowed: the purchase automation calls
`/shardall` from the console.

Grant through LuckPerms if a non-operator ever needs them:

- `coltcore.admin.keyall` / `coltcore.admin.kitall` / `coltcore.admin.shardall`
- `gildedcore.admin.keyall` / `gildedcore.admin.kitall` / `gildedcore.admin.shardall`

---

## 10. Removed in earlier passes

- The command whitelist feature. (A third-party "CommandWhitelist" plugin on
  the server is a separate thing and must be removed from `plugins/` directly.)
- External AI provider modules, remote classifier and evidence-store
  integrations, and cross-brand AI naming.

---

## 11. Build and verify

```powershell
mvn -f ColtCore/pom.xml clean package -DskipTests
mvn -f GildedCore-final/pom.xml clean package -DskipTests
```

Artifacts:

- `ColtCore/target/ColtCore-1.14.0.jar`
- `GildedCore-final/target/GildedCore-1.14.0.jar`

Both jars embed the identical `antiad.m5.bin` (1.83 MB). To confirm the loader
agrees with the shipped file, run the two commands in §3 — `TldCheck` loads the
model through the same `MillenniumNet.loadResource` the plugin uses, out of the
same jar-relative path.

For a live check, set `chat-guard.dry-run: true` first and watch
`antiad.db`'s `antiad_log` table before letting it act.
