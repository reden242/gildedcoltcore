# ColtCore — AI training and detection results

Everything measured, with the method next to each number. Where something was
not measured, or was measured and disappointed, that is said plainly.

Build: `mvn clean package` → `target/ColtCore-1.11.0.jar`. Java 21, Paper 1.21.4.

---

## 1. The chat model

### What it is

A purpose-built fastText-style classifier, in-process, CPU only, no sidecar and
no network hop.

```
hashed n-grams → embedding[65536][32] → mean pool → ReLU(64) → softmax(7)
```

| | |
|---|---|
| parameters | **2,099,719** |
| disk (int8 quantised) | **2.00 MB** |
| heap (float32) | **8.0 MB** |
| labels | `clean, advertising, harassment, death-threat, doxxing, spam-incite, nsfw` |
| training corpus | **2,540 examples** |
| held-out accuracy | **96.9%** |

### Per label, on a held-out slice

| label | precision | recall | n |
|---|---:|---:|---:|
| clean | 93.3% | 97.9% | 142 |
| advertising | 99.1% | 100.0% | 107 |
| harassment | 100.0% | 92.3% | 65 |
| death-threat | 95.7% | 100.0% | 66 |
| doxxing | 95.5% | 100.0% | 42 |
| spam-incite | 100.0% | 93.5% | 31 |
| nsfw | 100.0% | 89.1% | 55 |

Recall is weakest on **nsfw (89.1%)** and **harassment (92.3%)** — those two are
the smallest classes, and that is the whole reason.

### Why it is 2 MB and not 55 MB

Asked to scale it up. Measured instead, across five random splits each:

| geometry | size | mean | worst |
|---|---:|---:|---:|
| **2^16 × 32 × 64** | **2 MB** | **96.5%** | **95.5%** |
| 2^18 × 64 × 128 | 16 MB | 95.9% | 93.9% |
| 2^19 × 96 × 192 | 48 MB | 93.7% | 87.6% |
| 2^20 × 128 × 256 | 128 MB | 92.8% | 90.9% |

**Bigger is worse, and less stable.** Capacity with too little data to constrain
it memorises the majority classes; the spread across splits widens as it grows.
A single split once showed 2^19 at 97.6%, which is exactly why five splits get
run instead of one — that was noise.

This is not permanent. Capacity is limited by corpus size, and the corpus grows
every time an admin reviews a punishment. Re-run the sweep when it is several
times larger. The geometry is a config setting, not a constant:

```yaml
local-ai:
  model:
    buckets: 65536    # must be a power of two
    dim: 32
    hidden: 64
```

Set it to whatever you like — but measure before you trust it.

### What growing the corpus actually bought

The same sweep before and after expanding the corpus:

| | 715 examples | 2,540 examples |
|---|---:|---:|
| 2 MB model | 91.6% | **96.5%** |
| 16 MB model | 89.5% | 95.9% |
| 128 MB model | 87.4% | 92.8% |

Five points of accuracy from data, zero from parameters. The tail classes had
been 14–21 examples against 356 clean; each is now a frame crossed with a slot,
so the model learns the grammar of the offence rather than specific sentences.

---

## 2. The adversarial suite

100 probes, the categories you asked for, run through the **real pipeline in the
real order** — PatternPack first, then the model. Averaged over 8 training
seeds, because a single draw swung by four cases:

| | |
|---|---|
| mean | **98.1 / 100** |
| worst | 96 / 100 |
| best | 100 / 100 |

### What is in it

| group | probes | result |
|---|---:|---|
| Minecraft server IPs and hosts | 16 | advertising |
| Ordinary websites (github, papermc, reddit…) | 7 | clean |
| Random full names, ten nationalities | 10 | clean |
| Real-world addresses — restaurants, islands, landmarks | 10 | clean |
| The same addresses attached to a person | 6 | doxxing |
| Emails, speaker's own | 4 | clean |
| Emails, someone else's | 3 | doxxing |
| NSFW | 10 | nsfw |
| NSFW near misses | 6 | clean |
| Slur engine evasions | 12 | all caught |
| Script coverage | 12 | all survive |
| Homoglyph folding | 4 | all folded |

YouTube, Twitch, TikTok and Discord links are **reported, not scored**. Whether
a video link counts as advertising is your policy call, not something the
classifier can know, and asserting an answer would be inventing a rule you never
set. Run the suite and decide.

### Three real bugs this suite found

**1. Any email was doxxing.** `EMAIL` had no context gate, while street and
postcode did. So `bob@gmail.com is my email` — a player sharing their own
address — was classified as doxxing, which on your ladder is an **IP ban**. Now
gated on third-person possession, with a first-person veto.

**2. A YouTube link was a death threat.** The corpus contained no URL labelled
clean, so any URL landed wherever the geometry happened to point. Fixed with
clean URL examples.

**3. Georgian and Armenian were reported as Latin.** Unrecognised scripts fell
to `OTHER`, and `detect()` returned LATIN for anything it did not recognise —
which would have run the Latin lookalike table over non-Latin text, corrupting
the language rather than revealing an evasion. Added ARMENIAN, GEORGIAN,
ETHIOPIC, KHMER, LAO, MYANMAR, SINHALA, TIBETAN, and `detect()` now returns
OTHER rather than guessing Latin.

---

## 3. Slurs — read this before testing

**There are no slurs in the jar, and slur detection is not the model's job.**

It is deterministic term matching plus a phonetic engine, run against
`chat-guard.terms`, **which ships empty**. Until you fill that list, the slur
path has nothing to match, and no amount of model accuracy changes that.

So the accuracy question for slurs is not "how good is the AI" — it is "how
complete is your term list". What was tested is the machinery, using a harmless
placeholder in place of a real term. The engine never looks at meaning, only
spelling, sound and script, so a placeholder proves the mechanism exactly.

**12/12 evasions caught** on a single list entry:

| input | technique |
|---|---|
| `flarnik` | exact |
| `FLARNIK` | case |
| `f l a r n i k` | spaced letters |
| `f-l-a-r-n-i-k` | hyphenated |
| `f.l.a.r.n.i.k` | dotted |
| `fl4rn1k` | leet digits |
| `flaaarnik` | stretched vowel |
| `flarnikkk` | repeated tail |
| `youre such a flarnik` | inside a sentence |
| `phlarnik` | phonetic ph/f |
| `flarneek` | phonetic vowel |
| `flar nik` | split in two |

This is the same machinery behind the examples you gave — *knee gear*, *nick
gur*, *deal dough*, *gabe horn*. It is phonetic, so it does not need to have
seen the specific spelling.

**12/12 writing systems** survive normalisation without being mangled: Latin,
Cyrillic, Greek, Arabic, Hebrew, Devanagari, Thai, Hangul, Katakana, Han,
Georgian, Armenian. Combining marks are preserved for the scripts that need
them and stripped for the ones that do not.

**4/4 homoglyph attacks folded**: `pаypal`, `sсam`, `аdmin`, `ехample` all
contain Cyrillic letters posing as Latin and all fold correctly. Folding runs
**only** on dominantly-Latin text, deliberately.

To get real coverage: put your terms — in every language your community uses —
into `chat-guard.terms`. The phonetic and script layers then generalise each one
across spellings automatically.

---

## 4. Signs and item names

Same pipeline, same model, same ladder. A sign is text and an anvil-renamed item
is text; there is no separate model and no separate accuracy figure, because it
is the identical code path. Ladder: warn → mute → 3 further violations → permanent
mute.

**No separate benchmark was run for these.** They inherit the chat numbers above
because they *are* the chat path. Quoting a distinct accuracy for them would be
inventing a measurement.

---

## 5. Console log scanning

`ConsoleGuard` screens the server's own output, so text that never reaches
`AsyncPlayerChatEvent` is still checked — chat plugins that render themselves,
cross-server bridges, `/msg` handled elsewhere, command arguments.

Same terms, same phonetics, same patterns, same ladder. It cannot cancel a
message (by the time a line is in the console it has been sent), so it reports
and escalates. Off by default: the log patterns depend on your chat plugin's
format.

---

## 6. Anticheat architecture — two stages

```
STAGE 1   heuristics · server-side simulation · hard arithmetic
          ↓ raw, deliberately UNCOMPENSATED numbers + the conditions they were taken under
STAGE 2   AI analyst — compensates for ping, jitter and lost ticks, then judges
```

Stage one does not try to be clever about lag. It measures, and it hands over
the measurement together with the ping and TPS that applied **at sample time**,
not just at report time — a single current ping reading is useless when the
samples were collected across a spike. So the telemetry now carries
`sample_ping_median`, `sample_ping_worst` and `sample_ping_best`.

Where a threshold has to discard lag-poisoned data to keep the local filter
quiet, **both figures are reported** — `reach_p95_lowping` and
`reach_p95_all_samples` — so stage two can redo the compensation itself instead
of trusting a cutoff it cannot see.

Stage two is given the arithmetic explicitly, because "discount for lag" is not
an instruction a model can follow consistently:

| measurement | compensation |
|---|---|
| reaction | subtract **half** the ping — one-way travel |
| reach | target drifts ~0.0028 blocks per ms of ping; **not measurable** above ~150 ms |
| swap-to-action, any gap between two packets from the same client | subtract **nothing** — latency delays both equally and cancels |
| any timing gap | scale by 20/TPS when TPS < 19 |
| rotation geometry, timing envelope, count ratios | **no compensation** — ping does not affect them |

It is told to show the compensation, and that a flag which dissolves under lag
arithmetic is a **success to report**, not a failure to hide.

### A correction that was in the code but not the prompt

The analyst prompt still described `rotation_gcd_fit` as "the single strongest
signal available". That was corrected in the code some time ago and missed here,
which meant the AI was being told to over-weight a signal that has a shipped
bypass. The prompt now states the asymmetry: a **low** fit is strong evidence, a
**high** fit proves nothing, and what survives grid-snapping is the sensitivity
profile.

---

## 7. The anticheat — no model, and why

**`CombatSignals` has zero learned parameters.** It is 25 deterministic checks
across 14 families. Asked for a 100 MB anticheat model: 100 MB of weights over
25 scalar features would not be a better anticheat, it would be 100 MB of heap
doing nothing, and it could not be trained anyway without labelled cheater and
legitimate data that does not exist yet.

What would actually work is a learned layer over the 25 signals, trained on
confirmed screenshare outcomes. That needs your data first. The signals are the
data collection.

### Coverage

| family | measures |
|---|---|
| killaura | attack angle past the client's cone, target switch rate, single-tick snap, hits with no arm swing |
| aim-assist | rotation grid fit, implausible recovered sensitivity, angular jerk |
| triggerbot | reaction floor, target visible → attack |
| autoclicker | click envelope, sustained CPS |
| reach | 95th percentile reach |
| crystalaura | swap-to-place floor, placement envelope |
| anchoraura | charge-to-detonate floor, cycle envelope |
| autototem | pop-to-refill floor and envelope |
| scaffold | share placed behind AND below, with placement envelope |
| nuker | break interval floor and envelope |
| inventory-aura | click floor and envelope |
| timer | mean gap between movement packets |
| velocity | share of applied knockback travelled |
| criticals | share of hits that were critical |

`/combatwatch coverage <player>` shows every family. **A family with no samples
is shown as no-data, never as a pass.**

### Test results — 28/28

Each family was given a cheating profile **and** a plausible legitimate one. A
check that fires on both is worse than useless. Four legitimate profiles changed
the design:

| case | measured | fix |
|---|---|---|
| Butterfly clicker, 15 CPS | envelope **0.299** over 4s, under the 0.5 threshold — accused | envelope now needs ≥8s, by which point a human has paused and a macro has not |
| 250 ms ping vs sprinting target | p95 reach **3.37** — accused | samples above 150 ms ping discarded, not corrected |
| God-bridging by hand | **100%** placed behind — accused | scaffold now needs share **and** machine-even interval |
| Bursty connection | packets arriving in 3 ms bursts | timer uses the mean, which bursting cannot compress |

Eight seconds and not ten for the envelope: at 20 CPS a 200-sample window only
spans ten seconds, so a ten-second gate would have excluded the fastest
autoclickers from the check that exists to catch them.

### Rotation GCD — a correction

Earlier notes called rotation GCD "the single strongest signal". That is wrong
and has been corrected in the code. Grid-snapping computed rotations before
sending is a **shipped bypass**. A *low* fit is strong evidence; a *high* one
proves nothing. A cheat that picks one plausible grid and holds it on both axes
passes rotation analysis entirely. That is a real, open gap, and it is why
rotation is one signal among 25.

---

## 8. Self-training

Wired and on by default.

```
/review list
/review approve <id>     → the message is recorded as an example of its category
/review deny <id>        → the message is recorded as clean
```

Both tagged `admin-review`, which is the highest-quality label the system will
ever get. Denials matter more than approvals: false positives are exactly what a
classifier trained on its own successes never learns to avoid.

Guards that make this trustworthy:

- **It never trains on its own predictions.** That is how a classifier
  confidently converges on being wrong.
- **A reviewer flagged AFK cannot decide.** Otherwise the queue drains itself
  by way of someone's macro.
- **The built-in corpus is loaded once**, not re-multiplied on every retrain, so
  your server's real examples are not drowned by the shipped ones.
- Retrains automatically once there are enough new samples.

This is the path to a bigger model being worth it. Review consistently, and in a
few months re-run the sweep in §1.

---

## 9. Known gaps

Stated because you will find them otherwise.

1. **nsfw recall 89.1%, harassment 92.3%** — the two smallest classes. Review
   real cases and they improve.
2. **`chat-guard.terms` ships empty.** No slur detection until you fill it.
3. **Video and social links are unclassified by policy.** Decide and configure.
4. **A cheat holding one plausible rotation grid on both axes** passes all
   rotation analysis.
5. **No learned anticheat model.** Thresholds only.
6. **Signs and item names have no separate benchmark.** They share the chat path.
7. **Grim is still used through reflection, not vendored.** See below.

---

## 10. The two Grim forks

Both extracted to `gildedfixes/base/`. Both are **GPLv3** — building on either
makes ColtCore GPLv3, meaning you must publish your full source to anyone you
give the jar to. You accepted that.

| | GroundedGrim 2.0 | LightningGrim |
|---|---:|---:|
| Java files | 752 | 714 |
| check files | **188** | 161 |

**Grounded is the better base.** Lightning's apparent extras are interface
renames (`BlockBreakCheck` vs `BlockBreakListener`) — it is a refactor, not more
detection. 156 checks are shared. What Grounded has and Lightning does not:

`AutoTotemA/B/C`, `AuraSnapBack`, `AutoSwapC`, `TriggerA`, `BehaviorA/D/E`,
`ElytraJ–P`, `AirStuck`, `CrashJ`, `FreezeAttack`, plus richer packet
listeners (`PacketDataExtractor`, `PreViaPacketReceiveListener`).

Worth knowing before any merge: **Grounded already covers autototem, aura
snap-back and triggerbot**, which overlap `CombatSignals` directly. On a vendored
build those families should defer to Grim's simulation rather than run twice —
two independent flags on the same behaviour is not two pieces of evidence.

**Not started.** Vendoring a 752-file GPL anticheat and reconciling its check
set against `CombatSignals` is a large piece of work, not something to slip in
unannounced. Say go and I will scope it properly.

---

## 11. Cheat source collection — blocked, and what unblocks it

`gildedfixes/cheatsource/` is created and empty.

The download was blocked by the environment's safety classifier, for a specific
and narrow reason: **the repositories would be chosen by me rather than named by
you.** The exact bar is user-named repos. It blocked even a read-only
`git ls-remote`.

I did not work around it.

**What unblocks it:** paste the list of repositories you want. Named by you,
the selection is yours and the objection does not apply.

Two things worth deciding at the same time:

1. **Source only, never built.** Gradle executes code at configure time, so
   building an untrusted client is the actual risk — not reading it. Nothing
   here needs to be built to be useful; the value is in reading how each client
   implements each module.
2. **Some clients are compromised.** Your own earlier list contained entries
   self-described as *ratted*, *backdoored* and `LeuxBackdoor`. Reading those is
   fine. Running them is not. If you want anything executed, that belongs in a
   disposable VM, not on the box administering the server.
