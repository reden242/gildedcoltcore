package com.gildedmc.core.modules;

import com.gildedmc.core.SchedulerCompat;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The anti-advertising pipeline: layer 2 (the message classifier) and layer 3
 * (the same classifier reading the conversation) behind one front door used by
 * {@link ChatGuardModule#screenAdvertising}.
 *
 * <h2>One model, two views</h2>
 * Both layers run {@link MillenniumNet} - the text redesign of the Millennium 5
 * engine - and both run it in this process on the plugin's own thread. There is
 * no HTTP call, no separate service to keep alive, and nothing to time out. The
 * layers differ only in what they are shown:
 *
 * <ul>
 *   <li><b>L2</b> sees the message alone, truncated to its last
 *       {@value #MAX_TOKENS} tokens. It answers "is this text an advert?"</li>
 *   <li><b>L3</b> sees the message plus the player's last
 *       {@code l3.context-messages} lines, each scored separately. It answers
 *       the question L2 structurally cannot: "is this message part of an
 *       advertising pitch being spread across several lines?"</li>
 * </ul>
 *
 * <h2>Why L3 does not simply concatenate</h2>
 * The obvious design - feed {@code context + message} as one sequence - was
 * built, measured and discarded. With five context lines (about twenty tokens)
 * against a three-token reply, the context wins the pooled representation, and
 * it fails in both directions: an innocent "ok" following five advertising
 * lines scored 0.804 (a false accusation), while a real pitch, "wanna join my
 * smp? ip in bio", fell from 0.741 to 0.183 when it followed ordinary chat (a
 * miss). So L3 is message-anchored instead, and the context has to earn its
 * influence:
 *
 * <ol>
 *   <li>The message alone is confident (&ge; {@value #BLOCK}) - flag. Note that
 *       this is <em>not</em> a restatement of L2's answer: the band caller scores
 *       the normalised text and the scan-all caller does not score at all, so a
 *       confident score here is a second, independent reading.</li>
 *   <li>The message alone is borderline (&ge; {@value #CLEAR}, &lt;
 *       {@value #BLOCK}) <em>and</em> at least half the context lines are
 *       themselves confident adverts - flag. An oddity in isolation is not
 *       worth acting on; the same oddity in a run of adverts is a pitch.</li>
 *   <li>Otherwise, flag only if appending the message <em>raises</em> the
 *       advertising score of what precedes it. Requiring the score to rise,
 *       rather than to be high, is what protects the innocent reply: the
 *       advertising context already scores high and the reply does not lift it.
 *       A pitch split across lines lifts it sharply, and that is the case no
 *       single-message classifier can see.</li>
 * </ol>
 *
 * <p>Every threshold here was set from the held-out split, not by taste; the
 * measurements are reproduced by {@code TrainMillennium eval}.
 *
 * <p>Fail-open by construction: a missing or unreadable model disables the
 * layer and the message passes. Nothing in this class can punish on a failure.
 */
public final class AntiAdPipeline {

    /** A layer-3 answer, or a "no opinion" that the caller must treat as pass. */
    record ModelVerdict(boolean flag, double confidence, String reasoning) {
        static ModelVerdict noOpinion(String why) {
            return new ModelVerdict(false, -1.0D, why);
        }

        /** An opinion that the message should pass, with the model's own score. */
        static ModelVerdict clear(double confidence, String why) {
            return new ModelVerdict(false, confidence, why);
        }
    }

    /** p(advertising) at or above this blocks without asking L3. */
    private static final double BLOCK = 0.85D;
    /** p(advertising) at or below this clears without asking L3. */
    private static final double CLEAR = 0.40D;
    /** Fraction of context lines that must be confident adverts for rule 2. */
    private static final double CONTEXT_AD_SHARE = 0.5D;
    /** Score the message must add to its context for rule 3 to fire. */
    private static final float CONTEXT_LIFT = 0.35F;
    /**
     * Floor on the combined score for rule 3, set to the same bar the message
     * alone has to clear. Requiring the two to agree means a borderline window
     * is never enough on its own, so an ordinary reply that happens to lift an
     * ordinary conversation cannot be flagged by arithmetic alone.
     */
    private static final float CONTEXT_LIFT_FLOOR = 0.85F;
    /** Tokens per scored sequence - the message, and each context line. */
    static final int MAX_TOKENS = 24;

    private final JavaPlugin plugin;

    private MillenniumNet model;                    // null = unavailable
    private boolean l2Enabled = true;
    private boolean l3Enabled = true;
    private boolean scanAlways = true;
    private int contextMessages = 5;

    private final Map<UUID, Deque<String>> context = new ConcurrentHashMap<>();

    /**
     * The neural word-reputation cache: every confirmed outcome teaches it
     * what individual words mean, and Layer 3 consults it before trusting a
     * pooled message score. Survives reload - reputations are earned from
     * live traffic, not from the model file.
     */
    private final NeuralWordCache wordCache =
            new NeuralWordCache(0, NeuralWordCache.DEFAULT_DICT_FRACTION);

    private Connection database;                    // antiad.db, may stay null

    public AntiAdPipeline(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** (Re)reads config, reloads the model, reopens the database. */
    public void reload() {
        ConfigurationSection section = this.plugin.getConfig().getConfigurationSection("anti-ad");
        if (section == null) section = this.plugin.getConfig().createSection("anti-ad");

        this.l2Enabled = section.getBoolean("l2.enabled", true);
        String resource = section.getString("l2.model-resource", "/antiad.m5.bin");
        this.model = this.l2Enabled || section.getBoolean("l3.enabled", true)
                ? MillenniumNet.loadResource(resource) : null;
        if (this.model == null) {
            this.plugin.getLogger().warning("[AntiAd] classifier " + resource
                    + " missing or unreadable - L2 and L3 disabled, fail-open.");
        } else {
            this.plugin.getLogger().info("[AntiAd] classifier loaded: "
                    + this.model.parameters() + " parameters, "
                    + String.format(Locale.ROOT, "%.2f MB",
                    this.model.serialisedBytes() / 1048576.0D)
                    + ", " + this.model.vocabWords().size() + " words.");
        }

        this.l3Enabled = section.getBoolean("l3.enabled", true);
        this.scanAlways = "always".equalsIgnoreCase(
                section.getString("l3.scan-mode", "always"));
        this.contextMessages = Math.max(1, section.getInt("l3.context-messages", 5));
        this.wordCache.resize(this.model == null ? 0 : this.model.vocabWords().size(),
                section.getDouble("l3.word-cache-fraction",
                        NeuralWordCache.DEFAULT_DICT_FRACTION));

        openDatabase(section.getInt("log.keep-days", 30));
    }

    /* ------------------------------------------------------------------ */
    /*  Layer 2                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * p(advertising) on the ALREADY NORMALIZED text, or -1 when the model is
     * unavailable (the caller falls back to the heuristic aggregator).
     */
    public double l2Probability(String normalizedText) {
        if (this.model == null || !this.l2Enabled) return -1.0D;
        try {
            return this.model.probability(normalizedText, MAX_TOKENS);
        } catch (Throwable t) {
            return -1.0D;
        }
    }

    public boolean l2Ready() {
        return this.model != null && this.l2Enabled;
    }

    /* ------------------------------------------------------------------ */
    /*  Word-reputation cache                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Teaches the word cache from a blocked message. Call once per block, on
     * already-normalised text - this is the "after they advertised" half of
     * Layer 3.
     */
    public void confirmAdvert(String normalizedText) {
        this.wordCache.confirmAdvert(normalizedText);
    }

    /**
     * Teaches the word cache from a cleared message. Clears are the more
     * valuable signal: every false positive the pipeline talks itself out of
     * becomes words the cache will defend next time.
     */
    public void confirmClean(String normalizedText) {
        this.wordCache.confirmClean(normalizedText);
    }

    /** The cache's opinion on already-normalised text, for other layers. */
    public NeuralWordCache.Prior wordPrior(String normalizedText) {
        return this.wordCache.prior(normalizedText);
    }

    /* ------------------------------------------------------------------ */
    /*  Layer 3                                                           */
    /* ------------------------------------------------------------------ */

    /** Feeds the context window. Called for every message, all players. */
    public void record(UUID playerId, String text) {
        if (text == null || text.isBlank()) return;
        Deque<String> entries = this.context.computeIfAbsent(playerId,
                ignored -> new ArrayDeque<>());
        synchronized (entries) {
            entries.addLast(text.length() > 300 ? text.substring(0, 300) : text);
            while (entries.size() > this.contextMessages) entries.removeFirst();
        }
    }

    /** Drops a player's context. */
    public void clear(UUID playerId) {
        this.context.remove(playerId);
    }

    /**
     * L3 as the tiebreaker for a message L2 put in the ambiguous band, or as the
     * fallback when L2 is unavailable.
     *
     * <p>Cheap and non-blocking: three to seven forward passes over at most
     * {@value #MAX_TOKENS} tokens each, on an in-process model. Safe to call
     * from the main thread, which is why signs, books and anvil renames are
     * screened like any other surface now.
     */
    public ModelVerdict l3Check(UUID playerId, String raw) {
        return verdict(playerId, raw);
    }

    /**
     * L3 as a scanner over traffic the regex and L2 cleared. The contextual
     * rules are the whole point here: a message that looks innocent alone but
     * continues a pitch is invisible to L2 and is exactly what this catches.
     */
    public ModelVerdict l3ScanEveryMessage(UUID playerId, String raw) {
        if (!this.scanAlways) return ModelVerdict.noOpinion("scan-always off");
        return verdict(playerId, raw);
    }

    /** Whether L3 reads every message, not just the ambiguous band. */
    public boolean scanModeAlways() {
        return this.scanAlways && l3Ready();
    }

    public boolean l3Ready() {
        return this.model != null && this.l3Enabled;
    }

    private ModelVerdict verdict(UUID playerId, String raw) {
        if (!l3Ready()) return ModelVerdict.noOpinion("l3 disabled");
        if (raw == null || raw.isBlank()) return ModelVerdict.noOpinion("empty");
        try {
            return contextualVerdict(playerId, raw);
        } catch (Throwable t) {
            // A classifier that throws must never be the reason someone is
            // punished, so the failure is reported as "no opinion".
            this.plugin.getLogger().warning("[AntiAd] L3 failed, fail-open: " + t.getMessage());
            return ModelVerdict.noOpinion("error");
        }
    }

    private ModelVerdict contextualVerdict(UUID playerId, String raw) {
        float message = this.model.probability(raw, MAX_TOKENS);
        // The cache pulls the pooled score toward the reputation of the
        // words actually in the message, in proportion to how many are
        // known. A confident score built mostly from cached-benign words is
        // how the TPA-auto false positive happened, and this is where it
        // stops: the adjusted score, not the raw one, faces the rules below.
        // Unknown vocabulary adjusts nothing, so novel pitches score as before.
        NeuralWordCache.Prior prior = this.wordCache.prior(raw);
        float scored = (float) NeuralWordCache.adjust(message, prior);
        if (scored >= BLOCK) {
            // Flag, not clear. The scan-all caller has not scored this message
            // at all - it arrives here precisely because no cheaper layer found
            // an address - so returning "clear" would mean the one layer that
            // reads every message could never block anything, which is the
            // opposite of its job. The band caller did score it, but on the
            // normalised text and below the block threshold, so this is a
            // genuine second reading rather than a duplicate of the first.
            return new ModelVerdict(true, scored, "confident advertising"
                    + cacheNote(message, scored, prior));
        }
        if (scored < CLEAR) {
            // Rule 3 only. Everything below is the "does this message continue
            // a pitch" test, and it needs a context to test against.
            return liftVerdict(playerId, raw, scored);
        }

        List<String> recent = recentMessages(playerId, raw);
        if (recent.isEmpty()) {
            return ModelVerdict.clear(scored, "borderline, no context");
        }
        int adverts = 0;
        float contextTotal = 0F;
        for (String line : recent) {
            float score = this.model.probability(line, MAX_TOKENS);
            contextTotal += score;
            if (score >= BLOCK) adverts++;
        }
        double share = (double) adverts / recent.size();
        if (share >= CONTEXT_AD_SHARE) {
            // The verdict is backed by the context as well as the message, so
            // the confidence reported is the stronger of the two. Reporting the
            // message's own borderline score alone would hand the caller a
            // number too low to act on and waste the evidence that decided it.
            double confidence = Math.max(scored, contextTotal / recent.size());
            return new ModelVerdict(true, confidence,
                    "borderline message in a run of adverts (" + adverts + "/"
                            + recent.size() + ")");
        }
        return ModelVerdict.clear(scored, "borderline in ordinary chat");
    }

    /**
     * Names the cache's role when it moved the score across a rule boundary,
     * so the log shows why a confident model read did or did not flag.
     */
    private static String cacheNote(float raw, float scored, NeuralWordCache.Prior prior) {
        if (prior.known() == 0) return "";
        boolean crossed = (raw >= BLOCK) != (scored >= BLOCK)
                || (raw < CLEAR) != (scored < CLEAR);
        if (!crossed) return "";
        return String.format(Locale.ROOT,
                " [word-cache: raw %.2f -> %.2f, %d/%d known, lean %.2f]",
                raw, scored, prior.known(), prior.total(), prior.advertLean());
    }

    /**
     * Rule 3: does the message raise the advertising score of what precedes it?
     *
     * <p>Both scores are of the same window, so anything the context contributes
     * is present in each and cancels. What is left is the message's own
     * contribution - which is the only thing worth acting on when the message by
     * itself reads as innocent.
     */
    private ModelVerdict liftVerdict(UUID playerId, String raw, float messageScore) {
        List<String> recent = recentMessages(playerId, raw);
        if (recent.isEmpty()) return ModelVerdict.clear(messageScore, "no context");
        StringBuilder joined = new StringBuilder();
        for (String line : recent) {
            if (joined.length() > 0) joined.append(' ');
            joined.append(line);
        }
        float contextOnly = this.model.probability(joined.toString(), MAX_TOKENS * 3);
        float withMessage = this.model.probability(joined + " " + raw, MAX_TOKENS * 3);
        float lift = withMessage - contextOnly;
        if (lift >= CONTEXT_LIFT && withMessage >= CONTEXT_LIFT_FLOOR) {
            return new ModelVerdict(true, withMessage,
                    String.format(Locale.ROOT,
                            "continues a pitch (score %+.2f to %.2f)", lift, withMessage));
        }
        return ModelVerdict.clear(messageScore, "no lift from context");
    }

    private List<String> recentMessages(UUID playerId, String raw) {
        List<String> recent = new ArrayList<>();
        Deque<String> entries = this.context.get(playerId);
        if (entries == null) return recent;
        synchronized (entries) {
            for (String line : entries) {
                if (!line.equals(raw)) recent.add(line);
            }
        }
        return recent;
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
            prune(keepDays);
        } catch (Throwable t) {
            this.plugin.getLogger().warning("[AntiAd] log database unavailable: " + t.getMessage());
            this.database = null;
        }
    }

    /**
     * Drops log rows past the retention window. The column is still called
     * {@code llm} because it is the same column existing databases already have;
     * it now holds the L3 reason rather than a language model's answer.
     */
    private void prune(int keepDays) {
        if (this.database == null || keepDays <= 0) return;
        long cutoff = System.currentTimeMillis() - (long) keepDays * 86_400_000L;
        try (PreparedStatement statement = this.database.prepareStatement(
                "DELETE FROM antiad_log WHERE ts < ?")) {
            statement.setLong(1, cutoff);
            statement.executeUpdate();
        } catch (Throwable ignored) {
            // Retention is housekeeping; never let it break startup.
        }
    }

    /** Async log write; never throws, never blocks the caller. */
    public void log(String playerName, String surface, String raw,
                    String l1, double l2, String l3, String action) {
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
                statement.setString(6, l3);
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
        this.context.clear();
    }
}
