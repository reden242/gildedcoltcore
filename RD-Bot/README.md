# RD-Bot — Discord support bot with Groq AI + token-protected web panel

RD-Bot lives inside tickets that your existing ticket bot (Tickets v2) creates.
It answers routine questions **only from your knowledge base + `prompt.md`**,
scores every answer with a confidence value, and the moment it is unsure — or
the topic needs a human — it stops answering and pings your staff role. Every
missed question is logged as a knowledge gap that staff can add to the
knowledge base in one click. Everything is configured through a web panel that
only opens with a secret token.

## What you get

- Answers in ticket channels (typing indicator first), with a short `Sources:`
  line and a configurable footer (default `Made by mtyri with love :heart:`).
- Escalations ping **only** the escalation role — never `@everyone`/`@here`,
  never user-controlled mentions.
- Knowledge-gap embeds in the gaps channel with **Add to Knowledge Base**
  (staff-only) and **Reply as staff** (modal, prefilled).
- Escalation logs + insight links in the log channel.
- Per-guild Groq key pool: sticky least-loaded assignment, per-key rate
  limits, 429/5xx failover with backoff, small/large model fallback, and
  "AI unavailable" escalation instead of silent failure.
- Panel pages: Knowledge Base (custom/web/discord/documents + limits),
  AI Insights, Escalations (settings + recent), Working Hours, Bot
  Personality, Prompt editor, API Keys & Usage, Test Chat.

## Prerequisites

- Java 21 (e.g. Eclipse Temurin 21), Maven 3.9+
- A Discord application + bot token (below)
- One or more Groq API keys (https://console.groq.com → API Keys)
- SQLite needs nothing — the JDBC driver ships inside the fat jar

## 1. Create the Discord app

1. https://discord.com/developers/applications → New Application (name it
   **RD-Bot**).
2. Bot → Reset Token → copy it into `config.yml` (`discord.token`) or export
   `RDBOT_BOT_TOKEN`. Tick **MESSAGE CONTENT INTENT** (required — without it
   the bot cannot read ticket messages).
3. OAuth2 → URL Generator → scopes `bot` + `applications.commands`, bot
   permissions: **View Channels, Send Messages, Send Messages in Threads,
   Embed Links, Attach Files, Read Message History, Add Reactions,
   Mention Everyone** (only the staff-role mention is ever used),
   **Manage Nicknames** (optional, for the panel nickname field).
4. Invite URL → open it → pick your support server.
5. Copy your user id (Developer Mode → right-click yourself → Copy User ID)
   into `discord.owner-id` — this enables `/panel-token regenerate`.

Required gateway intents (already coded in `Bot.java`): **Guilds, Guild
Messages, Message Content**. No privileged member intent is needed.

## 2. Configure

```sh
cp config.example.yml config.yml
```

Minimum to fill in:

```yaml
discord:
  token: "MT..."            # or RDBOT_BOT_TOKEN env
  owner-id: "450734213767888913"
  escalation-role-id: "<mods role id>"
  gaps-channel-id: "<channel id>"
  log-channel-id: "<channel id>"

ai:
  keys:
    - "gsk_..."              # or RDBOT_GROQ_KEY_1, _2, ...
```

Key pool notes:

- Put several keys in `ai.keys`. New guilds are pinned to the least-loaded
  key and stay there (stored in SQLite), unless the key cools down.
- Explicit pinning: `ai.guild-keys: { "<guildId>": [0, 2] }` limits which pool
  indexes a guild may use.
- `requests-per-minute` / `tokens-per-minute` apply per key with a short
  queue; one busy guild cannot starve others because assignment is per guild
  and the limiter is per key.

Ticket detection (`discord.tickets`): any combination works — parent category
IDs, channel-name prefixes (`ticket-` matches Tickets v2 defaults), or
keywords in the channel topic. Staff replies can pause the bot per channel.

Edit `prompt.md` for your server (IP, commands, how-tos, staff role, rules).
Placeholders like `{{server_ip}}` are filled from the panel's Prompt page.

## 3. Build and run

```sh
mvn package
java -jar target/RD-Bot.jar
```

First start prints the panel link **once**:

```
http://0.0.0.0:12022/?token=DelFlowPVyTUZb9wxBGL7MWAPAChCTxHWMAIuBPCNNh27ggqf
```

Open it → the server sets an `HttpOnly` cookie and redirects to `/` (token
gone from the URL). Later visits use the cookie; **Log out** clears it. Rotate
anytime with `/panel-token regenerate` (owner-only, ephemeral) — old cookies
die immediately. Only SHA-256 hashes are stored in `panel-tokens.json`.

Panel admins (`panel.admin-user-ids` in `config.yml`, plus the owner) can run
every command without a staff role and can DM themselves a login link with
`/panel link` — each link is a fresh token that stays valid alongside the
others (up to 20 stored). If a member can't receive the DM, they must enable
"Allow direct messages from server members" first.

Files created next to the jar: `rdbot.db` (SQLite), `panel-tokens.json`,
`prompt.md` (the shipped example, edit freely).

## 4. systemd service

```ini
[Unit]
Description=RD-Bot Discord support bot
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=rd-bot
WorkingDirectory=/opt/rd-bot
Environment=RDBOT_BOT_TOKEN=MT...
Environment=RDBOT_GROQ_KEY_1=gsk_...
ExecStart=/usr/bin/java -jar /opt/rd-bot/RD-Bot.jar
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```sh
sudo systemctl enable --now rd-bot
journalctl -u rd-bot -f
```

## 5. Panel access and firewall

- Default port **12022** (`panel.port`). Open it in the firewall
  (`ufw allow 12022/tcp`) or, better, bind to `127.0.0.1` and put it behind a
  reverse proxy with HTTPS — the panel sets `SameSite=Strict` cookies but
  plain HTTP still leaks the token on the wire.
- Optional `panel.ip-allowlist` restricts who may even attempt auth.
- Failed logins are throttled per IP (`max-auth-failures`, temporary ban).
- Mutating requests need the `X-CSRF-Token` header; output is escaped;
  uploads are capped (12 MB) and limited to PDF/TXT/MD.

## 6. `prompt.md` reference

One Markdown file, sent as plain text at the base of every system prompt
(hot-reloaded on each request — editing the file or saving in the panel
applies immediately). Sections: Server, Connection (IP + join steps),
Commands, How-to guides, Staff (escalation role + staff-only topics), Rules,
Never do, Personality. Placeholders (`{{server_ip}}`, `{{staff_role}}`, …)
are filled from the panel, per server.

Final system prompt order: `prompt.md` → personality settings →
retrieved knowledge chunks (top-K, ~700 chars) → strict JSON output
instructions. Model contract: `answer, confidence, needs_staff, category,
reason, summary, suggested_topic, sources`. Unparseable output always
escalates — the bot never sends a blank or invented reply from a broken
model response.

## 7. Slash commands

- `/kb add|list|remove|reload` — staff only, honours panel staff roles.
- `/rd-bot pause|resume` — instant guild pause without touching ticket data.
- `/rd-bot status` — key count, cooling keys, gateway ping.
- `/panel link` — panel admins only: DMs you a panel login link. Needs
  `panel.public-base-url` set to a reachable address (e.g.
  `http://185.207.166.52:12022`) **and the panel port open in the firewall**,
  otherwise the link won't load.
- `/panel-token regenerate` — owner only, ephemeral, shows the new link.

## 8. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Bot online but never answers | MESSAGE CONTENT INTENT off in the portal; channel not matching ticket detection; working-hours schedule closed; guild paused. |
| `401/429` storm in the panel key stats | Keys rate-limited: add keys, lower `top-k`/`history-messages`, raise `cache-seconds`. 429s cool the key and fail over automatically. |
| Empty replies / everything escalates | Groq key invalid (check key `last4` + errors in API Keys page); `prompt.md` missing knowledge; threshold preset too strict. |
| Panel shows "Auth failed" | Use the full `/?token=…` link once; cookies blocked; IP banned after 8 bad tries (wait 5 min). |
| No keys configured | Add `ai.keys` or `RDBOT_GROQ_KEY_1`. The bot refuses AI calls and escalates instead of failing silently. |
| Discord sources won't sync | Bot must see the channel (View Channels) and the channel must be a guild text channel. |

## 9. Security notes

- Tokens: generated with `SecureRandom` (a-zA-Z0-9, 48 chars), SHA-256 hashed
  on disk, constant-time compare, cookie `HttpOnly` + `SameSite=Strict`.
- AI output is untrusted: `@everyone`/`@here`/role/user mentions are
  neutralized, and user text can never change the bot's rules
  (prompt-injection resistant system prompt + JSON validation).
- Escalation pings use Discord allowed-mentions scoped to the single role.
- Never print full keys (panel shows last 4 only); keys come from env/config,
  never typed into the panel.

## Assumptions

- Tickets v2 channels are detectable by category/prefix/topic — no migration
  and no dependency on its internals.
- SQLite + WAL handles a support bot's load on one machine (no clustering).
- Keyword/BM25 retrieval is used instead of a vector DB (spec explicitly
  allows it); wording drift is covered by confidence thresholds + gaps.
- PDFBox is used for PDF text extraction; scanned/image PDFs yield no text.
- `jsoup` does not execute JavaScript — JS-heavy docs sites index poorly.
- Per-guild panel tokens (one token per server) are modeled as future work:
  the data model is per-guild everywhere, but the token store is currently
  global (rotation replaces all tokens).


