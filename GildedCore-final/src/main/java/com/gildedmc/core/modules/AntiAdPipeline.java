package com.gildedmc.core.modules;

import com.gildedmc.core.SchedulerCompat;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The anti-advertising pipeline: layer 2 (the fastText-style classifier) and
 * layer 3 (the local LLM) behind one front door used by
 * {@link ChatGuardModule#screenAdvertising}.
 *
 * <h2>Layer 2 - a real classifier, trained on real vectors</h2>
 * {@code antiad.ft.bin} is a fastText-architecture model: char 3-6-gram subword
 * embeddings initialised from Common Crawl 300d vectors (PCA-reduced to 100d),
 * mean-pooled into a softmax over advertising/clean. Trained offline by
 * {@code antiad/train_antiad.py} against 32k samples of seed ads, mutated
 * obfuscations and real server chat; shipped quantized at ~3.6 MB. Java-side
 * loading and the hash contract live in {@link FastTextModel}.
 *
 * <h2>Layer 3 - the local LLM</h2>
 * The message and its recent context go to a locally-hosted OpenAI-compatible
 * endpoint (default {@code http://127.0.0.1:8080/v1/chat/completions}, model
 * {@code millenium-5}). The LLM answers strict JSON; anything else - timeout,
 * refusal, unparseable, rate limit (one call per player per 10 s) - is treated
 * as "no opinion" and the message passes.
 *
 * <p>Two entry points share one implementation. {@link #llmCheck} is the
 * tiebreaker used when L2 lands in the ambiguous band (0.40-0.85).
 * {@link #scanEveryMessage} is the scanner: with {@code l3.scan-mode: always}
 * it reads every message the regex and L2 <em>cleared</em>, which is the only
 * way a pitch with no findable address - an obscure TLD, a bare server name -
 * gets judged on meaning. It never blocks the server tick: the main-thread
 * guard returns "no opinion" for signs, books and anvils, and repeated lines
 * hit the verdict cache instead of the model.
 */
public final class AntiAdPipeline {

    /** LLM answer, or a "no opinion" that the caller must treat as pass. */
    record LlmVerdict(boolean flag, double confidence, String reasoning) {
        static LlmVerdict noOpinion(String why) {
            return new LlmVerdict(false, -1.0D, why);
        }
    }

    private final JavaPlugin plugin;
    private final String ownDomain;

    private FastTextModel model;                    // layer 2, null = unavailable
    private boolean l2Enabled = true;

    // layer 3
    private boolean l3Enabled = true;
    private String llmEndpoint = "http://127.0.0.1:8080/v1/chat/completions";
    private String llmModel = "millenium-5";
    private String llmKey = "local";
    private int llmTimeoutSeconds = 8;
    private long l3MinCallGapMs = 10_000L;

    private final Map<UUID, Long> lastLlmCall = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<String>> context = new ConcurrentHashMap<>();
    private static final int CONTEXT_MESSAGES = 5;

    /**
     * Verdict cache keyed by text hash. The same line ("gg", a copypasta, a
     * repeated spam post) is not sent to the model twice, which is what makes
     * scanning every message affordable.
     */
    private final Map<Integer, LlmVerdict> verdictCache = new ConcurrentHashMap<>();
    private static final int CACHE_MAX = 4096;

    // layer 3 scan policy
    private boolean scanAlways = true;
    private int scanTimeoutSeconds = 3;
    private int maxConcurrentScans = 4;

    /**
     * Scan-all calls in flight. Each one parks an async chat thread on HTTP for
     * up to {@code scan-timeout-seconds}, so when the model is slow the cap is
     * what stops a busy server from parking every chat thread it has. Over the
     * cap the message simply passes unscanned, like any other fail-open path.
     */
    private final java.util.concurrent.atomic.AtomicInteger scansInFlight =
            new java.util.concurrent.atomic.AtomicInteger();

    private Connection database;                    // antiad.db, may stay null

    public AntiAdPipeline(JavaPlugin plugin) {
        this.plugin = plugin;
        this.ownDomain = plugin.getConfig().getString("anti-ad.own-domain", "gildedmc.net");
        reload();
    }

    /** (Re)reads config, reloads the model, reopens the database. */
    public void reload() {
        ConfigurationSection section = this.plugin.getConfig().getConfigurationSection("anti-ad");
        if (section == null) section = this.plugin.getConfig().createSection("anti-ad");

        this.l2Enabled = section.getBoolean("l2.enabled", true);
        String resource = section.getString("l2.model-resource", "/antiad.ft.bin");
        this.model = this.l2Enabled ? FastTextModel.loadResource(resource) : null;
        if (this.l2Enabled && this.model == null) {
            this.plugin.getLogger().warning("[AntiAd] layer 2 model " + resource
                    + " missing or unreadable - L2 disabled, fail-open.");
        } else if (this.model != null) {
            this.plugin.getLogger().info("[AntiAd] layer 2 model loaded ("
                    + String.join("/", this.model.labels()) + ").");
        }

        this.l3Enabled = section.getBoolean("l3.enabled", true);
        this.scanAlways = "always".equalsIgnoreCase(
                section.getString("l3.scan-mode", "always"));
        ConfigurationSection llm = section.getConfigurationSection("llm");
        if (llm != null) {
            this.llmEndpoint = llm.getString("endpoint", this.llmEndpoint);
            this.llmModel = llm.getString("model", this.llmModel);
            List<String> keys = llm.getStringList("api-keys");
            if (!keys.isEmpty()) this.llmKey = keys.get(0);
            this.llmTimeoutSeconds = llm.getInt("timeout-seconds", 8);
            this.scanTimeoutSeconds = llm.getInt("scan-timeout-seconds", 3);
            this.maxConcurrentScans = Math.max(1, llm.getInt("max-concurrent-scans", 4));
        }
        this.l3MinCallGapMs = section.getLong("l3.min-call-gap-ms", 10_000L);

        openDatabase(section.getInt("log.keep-days", 30));
    }

    /* ------------------------------------------------------------------ */
    /*  Layer 2                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * p(advertising) from the fastText model on the ALREADY NORMALIZED text,
     * or -1 when the model is unavailable (caller falls back to the old
     * LinearModel or passes).
     */
    public double l2Probability(String normalizedText) {
        if (this.model == null) return -1.0D;
        try {
            return this.model.advertisingProbability(normalizedText);
        } catch (Throwable t) {
            return -1.0D;
        }
    }

    public boolean l2Ready() {
        return this.model != null;
    }

    /* ------------------------------------------------------------------ */
    /*  Layer 3                                                           */
    /* ------------------------------------------------------------------ */

    /** Feeds the L3 context window. Called for every message, all players. */
    public void record(UUID playerId, String text) {
        if (text == null || text.isBlank()) return;
        Deque<String> entries = this.context.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
        synchronized (entries) {
            entries.addLast(text.length() > 300 ? text.substring(0, 300) : text);
            while (entries.size() > CONTEXT_MESSAGES) entries.removeFirst();
        }
    }

    /** Drops a player's context and rate-limit stamp. */
    public void clear(UUID playerId) {
        this.context.remove(playerId);
        this.lastLlmCall.remove(playerId);
    }

    /**
     * Asks the local LLM for a second opinion. Rate-limited per player and
     * fail-open by construction: every failure mode returns "no opinion".
     * MUST be called off the main thread (it blocks on HTTP).
     */
    public LlmVerdict llmCheck(UUID playerId, String playerName, String raw) {
        return llmVerdict(playerId, playerName, raw, this.llmTimeoutSeconds);
    }

    /**
     * The LLM as a first-class scanner rather than a tiebreaker.
     *
     * <p>Called for messages the regex and the classifier did <em>not</em>
     * flag, so the layer that can read meaning gets to look at traffic the
     * cheaper layers cleared. Two things keep that affordable: the per-player
     * rate limit (one call per {@code l3.min-call-gap-ms}), and a verdict
     * cache, so a repeated line costs nothing after the first look.
     *
     * <p>Only callable off the main thread. On the main thread the caller must
     * not block, so this returns "no opinion" immediately - the message is
     * recorded for context instead and the next scanned line benefits.
     */
    public LlmVerdict scanEveryMessage(UUID playerId, String playerName, String raw) {
        if (!this.scanAlways) return LlmVerdict.noOpinion("scan-always off");
        if (Bukkit.isPrimaryThread()) return LlmVerdict.noOpinion("main thread");
        String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (this.verdictCache.containsKey(key.hashCode())) {
            return llmVerdict(playerId, playerName, raw, this.scanTimeoutSeconds);
        }
        if (this.scansInFlight.get() >= this.maxConcurrentScans) {
            return LlmVerdict.noOpinion("scan backlog");
        }
        this.scansInFlight.incrementAndGet();
        try {
            return llmVerdict(playerId, playerName, raw, this.scanTimeoutSeconds);
        } finally {
            this.scansInFlight.decrementAndGet();
        }
    }

    /** Whether the LLM scans every message, not just the ambiguous band. */
    public boolean scanModeAlways() {
        return this.scanAlways && this.l3Enabled;
    }

    private LlmVerdict llmVerdict(UUID playerId, String playerName, String raw,
                                  int timeoutSeconds) {
        if (!this.l3Enabled) return LlmVerdict.noOpinion("l3 disabled");

        String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        int hash = key.hashCode();
        LlmVerdict cached = this.verdictCache.get(hash);
        if (cached != null) return cached;

        Long last = this.lastLlmCall.get(playerId);
        long now = System.currentTimeMillis();
        if (last != null && now - last < this.l3MinCallGapMs) {
            return LlmVerdict.noOpinion("rate-limited");
        }
        this.lastLlmCall.put(playerId, now);

        List<String> recent = new ArrayList<>();
        Deque<String> entries = this.context.get(playerId);
        if (entries != null) {
            synchronized (entries) { recent.addAll(entries); }
        }
        String body = buildRequest(playerName, raw, recent);
        try {
            String response = post(body, timeoutSeconds);
            LlmVerdict verdict = parseVerdict(response);
            if (verdict.confidence() >= 0.0D) {
                if (this.verdictCache.size() > CACHE_MAX) this.verdictCache.clear();
                this.verdictCache.put(hash, verdict);
            }
            return verdict;
        } catch (Throwable t) {
            this.plugin.getLogger().warning("[AntiAd] LLM unavailable, fail-open: " + t.getMessage());
            return LlmVerdict.noOpinion("error");
        }
    }

    private String buildRequest(String playerName, String raw, List<String> recent) {
        StringBuilder history = new StringBuilder();
        for (String line : recent) {
            if (line.equals(raw)) continue;
            if (history.length() > 0) history.append("\\n");
            history.append(escape(line));
        }
        String system = "You are a Minecraft server chat moderator. Decide whether the message"
                + " advertises or promotes another server, IP address, or Discord invite."
                + " Benign mentions of well-known websites (reddit.com, youtube.com, github.com)"
                + " are NOT advertising. Answer with strict JSON only:"
                + " {\\\"flag\\\": true|false, \\\"confidence\\\": 0.0-1.0, \\\"reasoning\\\": \\\"short reason\\\"}";
        String user = "Player: " + playerName + "\\nRecent messages from the same player:\\n"
                + history + "\\nMessage to judge: \\\"" + escape(raw) + "\\\"";
        return "{\"model\": \"" + this.llmModel + "\", \"temperature\": 0.0,"
                + " \"messages\": ["
                + "{\"role\": \"system\", \"content\": \"" + system + "\"},"
                + "{\"role\": \"user\", \"content\": \"" + user + "\"}"
                + "]}";
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String post(String json, int timeoutSeconds) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(this.llmEndpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + this.llmKey);
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(Math.max(1, timeoutSeconds) * 1_000);
        connection.setDoOutput(true);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while (stream != null && (read = stream.read(chunk)) >= 0) buffer.write(chunk, 0, read);
        if (status >= 400) throw new IllegalStateException("HTTP " + status);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private LlmVerdict parseVerdict(String response) {
        String content = extractContent(response);
        if (content == null) return LlmVerdict.noOpinion("no content");
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) return LlmVerdict.noOpinion("no json");
        String json = content.substring(start, end + 1);
        boolean flag = json.matches("(?s).*\"flag\"\\s*:\\s*true.*");
        String confRaw = regexGroup(json, "\"confidence\"\\s*:\\s*([0-9.]+)");
        double confidence = 0.5D;
        try {
            if (confRaw != null) confidence = Double.parseDouble(confRaw);
        } catch (NumberFormatException ignored) { }
        String reasoning = regexGroup(json, "\"reasoning\"\\s*:\\s*\"([^\"]*)\"");
        return new LlmVerdict(flag, Math.max(0.0D, Math.min(1.0D, confidence)),
                reasoning == null ? "llm" : reasoning);
    }

    /** content field of an OpenAI-style chat completion response. */
    private static String extractContent(String response) {
        String content = regexGroup(response, "(?s)\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        if (content == null) return null;
        return content.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
    }

    private static String regexGroup(String input, String pattern) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(input);
        return m.find() ? m.group(1) : null;
    }

    /* ------------------------------------------------------------------ */
    /*  Logging                                                           */
    /* ------------------------------------------------------------------ */

    private void openDatabase(int keepDays) {
        try {
            if (this.database != null) {
                try { this.database.close(); } catch (Throwable ignored) { }
                this.database = null;
            }
            java.nio.file.Path folder = this.plugin.getDataFolder().toPath();
            java.nio.file.Files.createDirectories(folder);
            this.database = DriverManager.getConnection(
                    "jdbc:sqlite:" + folder.resolve("antiad.db"));
            try (Statement statement = this.database.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS antiad_log ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "player_name TEXT NOT NULL,"
                        + "surface TEXT NOT NULL,"
                        + "raw_msg TEXT NOT NULL,"
                        + "l1 TEXT, l2 REAL, llm TEXT, action TEXT NOT NULL,"
                        + "ts INTEGER NOT NULL)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_antiad_ts ON antiad_log(ts)");
            }
        } catch (Throwable t) {
            this.plugin.getLogger().warning("[AntiAd] log database unavailable: " + t.getMessage());
            this.database = null;
        }
    }

    /** Async log write; never throws, never blocks the caller. */
    public void log(String playerName, String surface, String raw,
                    String l1, double l2, String llm, String action) {
        if (this.database == null) return;
        final Connection db = this.database;
        SchedulerCompat.runAsync(this.plugin, () -> {
            try (PreparedStatement statement = db.prepareStatement(
                    "INSERT INTO antiad_log(player_name,surface,raw_msg,l1,l2,llm,action,ts)"
                            + " VALUES (?,?,?,?,?,?,?,?)")) {
                statement.setString(1, playerName);
                statement.setString(2, surface);
                statement.setString(3, raw);
                statement.setString(4, l1);
                statement.setDouble(5, l2);
                statement.setString(6, llm);
                statement.setString(7, action);
                statement.setLong(8, System.currentTimeMillis());
                statement.executeUpdate();
            } catch (Throwable ignored) {
                // A full log table must never break chat.
            }
        });
    }

    public void disable() {
        if (this.database != null) {
            try { this.database.close(); } catch (Throwable ignored) { }
            this.database = null;
        }
    }
}
