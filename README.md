# ColtCore and GildedCore — AI chat filter update

This workspace contains two independent Paper plugins:

- `ColtCore/` — ColtCore, brand color `#00FF00`
- `GildedCore-final/` — GildedCore, brand color `#EEBB01`

The two trees are kept in lockstep: every change is applied to both, modulo
package/branding renames (`com.coltcore.*` ↔ `com.gildedmc.*`, `coltcore.net` ↔
`gildedmc.net`). The trained model file is **byte-identical** in both jars.

## The anti-advertising pipeline (4 layers)

1. **Ingest and normalize (Stage 0)** — Paper `AsyncChatEvent` (plus sign,
   book, anvil and private-message surfaces) supplies the raw text.
   Normalization lowercases, strips zero-width characters, folds Cyrillic and
   Greek homoglyphs, reconstructs disguised dots (`play (dot) x (dot) com`),
   collapses spaced keywords and folds leet spellings (`advertForm()`).
2. **Pattern scan (L1)** — precompiled patterns check IPv4 (with optional
   port), domains, obfuscated dots and Discord invites. The server's own
   domain and the configured allow list are exempt. No L1 hit → the message
   passes at zero model cost.
3. **Neural intent classifier (L2)** — a genuine fastText-style neural
   network, `antiad.ft.bin` (~3.6 MB, embedded in the jar). Word embeddings
   initialized from **Common Crawl 300d subword vectors** (the 5.8 GB
   `crawl-300d-2M-subword.zip`, PCA-reduced to 100 dimensions at training
   time), hashed char 3–6-gram subword embeddings, mean pooling, softmax over
   `advertising` / `clean`. Confident advertising (≥0.85) → block; confident
   clean (<0.40) → pass logged; anything between is ambiguous and continues.
4. **Local LLM context check (L3)** — ambiguous messages go to a **locally
   hosted LLM** (`millenium-5` by default) through an OpenAI-compatible
   endpoint on loopback (`http://127.0.0.1:8080/v1/chat/completions`). The
   prompt carries the player's last 5 messages for context; the LLM answers
   strict JSON `{flag, confidence, reasoning}`. A confirmed flag rides the
   existing punishment ladder; a clear verdict passes with an evasion-suspicion
   log entry. Rate limit: one call per player per 10 s. **Fail-open
   everywhere** — timeout, unparseable answer, or rate limit means pass, never
   punish. When the LLM has no opinion, the local heuristic aggregator
   (`LocalContextAggregator`) decides as before.

### Decision matrix

| Condition | Action |
|---|---|
| No L1 hit | pass, no model cost |
| L1 hit, L2 < 0.40 | pass, logged (whitelist-tuning feedback) |
| L1 hit, L2 ≥ 0.85 | block (`advertising` / `light-advertising` ladder) |
| L2 in 0.40–0.85, LLM confirms | block, punishment survives the regex double-check |
| L2 in 0.40–0.85, LLM clears | pass, logged with reasoning |
| LLM unavailable / rate-limited | local heuristic aggregator decides, fail-open |
| Model missing or fails to load | L2 disabled, legacy LinearModel path, fail-open |

Surfaces routed through the same screen: chat, signs (lines joined), books
(pages + title), anvil item names, and configured private-message commands.
Every L2/LLM decision is written to `antiad.db` (`antiad_log` table) with the
surface, raw text, L1 evidence, L2 probability, LLM verdict and action taken.

## The model

- **Trained on**: `crawl-300d-2M-subword.zip` fastText Common Crawl vectors
  (training-time only, never shipped) + the seed corpus (11,261 advertising
  samples with obfuscation mutations) + 20,696 real clean chat lines.
- **Validation**: precision 0.999, recall 0.998 on the held-out split;
  temperature-scaled (T=4.0) so the ambiguous band 0.40–0.85 carries ~6.5% of
  confidence mass — that band is what triggers L3, and without the scaling the
  LLM would never fire.
- **Format**: custom `FTA1` binary (big-endian): header ints, UTF vocab and
  labels, int8-quantized input (words+30,000 subword buckets × 100d) and
  output matrices with per-matrix scales. Loader: `FastTextModel.java`, pure
  Java, no dependencies. The subword hash (FNV-1a 32-bit over UTF-8 bytes of
  `<gram>`, `row = vocabSize + hash % bucket`) is implemented identically in
  the Python trainer and the Java loader, and verified by a golden-vector
  parity test (`antiad/vectors-check.tsv`, 10 texts, delta 0.000000).
- **Hard budget**: export fails if the binary exceeds 5 MB (current: 3.62 MB).

### Retraining

```powershell
# 1. Reduce the crawl vectors (streams the 4.5 GB .vec straight from the zip)
python antiad/reduce_vecs.py

# 2. Dump the seed corpus from the plugin classes (after mvn compile)
javac -cp ColtCore/target/classes -d antiad antiad/DumpCorpus.java
java -cp "antiad;ColtCore/target/classes" DumpCorpus antiad/seed_corpus.tsv

# 3. Train + export + metric gates + parity vectors
python antiad/train_antiad.py

# 4. Copy to both trees and rebuild
copy antiad\antiad.ft.bin ColtCore\src\main\resources\
copy antiad\antiad.ft.bin GildedCore-final\src\main\resources\
```

Metric gates (export refuses otherwise): advertising precision ≥ 0.97,
recall ≥ 0.90, clean false-flag rate ≤ 0.10, and ≥ 3% of validation
confidence inside 0.40–0.85 after temperature scaling.

## The local LLM (millenium-5)

Any OpenAI-compatible chat-completions server works. Point
`anti-ad.llm.endpoint` at it and set `model`. The default expects a
`millenium-5` server on `127.0.0.1:8080`. Nothing leaves the machine; the
`Authorization: Bearer local` header is a placeholder for servers that require
one. If the endpoint is down the filter simply runs L1 + L2 + the heuristic
aggregator.

## Command permissions

`/keyall`, `/kitall`, `/shardall` are operator-only in both plugins
(`default: op` in plugin.yml, plus the `COMMAND_PERMISSIONS` map and the
`authorise()` gate in each main class). A normal player cannot run them.
Console remains allowed — the purchase automation calls `/shardall` and
`/rankgive` from the console, and a `ProxiedCommandSender` (a player proxying
via `/execute`) is refused like any other player.

Grant to players only through LuckPerms:

- `coltcore.admin.keyall` / `coltcore.admin.kitall` / `coltcore.admin.shardall`
- `gildedcore.admin.keyall` / `gildedcore.admin.kitall` / `gildedcore.admin.shardall`

## Removed

- Command whitelist feature (removed in an earlier pass; note that a separate
  third-party "CommandWhitelist" plugin on the server is unrelated to this
  codebase and must be removed from the server's `plugins/` folder directly)
- External AI provider modules and commands
- Remote classifier and evidence-store integrations
- Multi-category AI training unrelated to advertising
- Cross-brand AI naming and cross-brand server-domain allow-list entries

## Config

New `anti-ad:` section (config-version 42 in ColtCore / 39 in GildedCore;
`ConfigUpdater` merges it into live configs automatically):

```yaml
anti-ad:
  own-domain: 'coltcore.net'          # gildedmc.net in GildedCore
  l2: { enabled: true, model-resource: '/antiad.ft.bin' }
  l3: { enabled: true, min-call-gap-ms: 10000, context-messages: 5 }
  llm:
    endpoint: 'http://127.0.0.1:8080/v1/chat/completions'
    model: 'millenium-5'
    api-keys: [ 'local' ]
    timeout-seconds: 8
  log: { keep-days: 30 }
```

## Build

```powershell
mvn -f ColtCore clean package -DskipTests
mvn -f GildedCore-final clean package -DskipTests
```

Built artifacts:

- `ColtCore/target/ColtCore-1.9.1.jar`
- `GildedCore-final/target/GildedCore-1.9.1.jar`

Both jars embed the identical `antiad.ft.bin`. Verify with:

```powershell
java -cp verify-classes com.coltcore.core.modules.FastTextModel ^
     antiad/antiad.ft.bin antiad/vectors-check.tsv
```
