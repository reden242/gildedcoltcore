package com.gildedmc.core.modules;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The neural word-reputation cache behind Layer 3.
 *
 * <h2>What it is</h2>
 * Every confirmed outcome teaches this cache what individual words mean:
 * {@link #confirmAdvert} pushes the message's words toward advertising,
 * {@link #confirmClean} pushes them toward benign. Each word holds an
 * exponentially-weighted lean, so a word seen clean fifty times and in one
 * advert stays benign, while a word that only ever arrives inside pitches
 * goes advert quickly. The update weight is constant, so memory is bounded
 * per word and old evidence decays rather than accumulating forever.
 *
 * <h2>Why Layer 3 needs it</h2>
 * Layer 3 is message-anchored: the message's own score decides, and the
 * context only modulates. But the model's score for a message is a pooled
 * representation over every token in it, and one unfamiliar token can drag
 * an innocent sentence over the block line - {@code @here my tpa auto is on
 * ...} scored 0.9554 with nothing advertising in it. When most of a
 * message's words are cached-benign from earlier confirmed traffic, the
 * cache pulls the score back toward the words' reputation in proportion to
 * how many of them are known. Coverage is the safety mechanism: unknown
 * words contribute nothing, so a novel pitch with fresh vocabulary is
 * scored exactly as before and the cache can never talk a new advert down.
 *
 * <h2>Size</h2>
 * The cache covers a fraction of the model dictionary, not an unbounded
 * vocabulary: capacity is {@code vocabSize * dictFraction}, clamped to
 * [{@value #MIN_CAP}, {@value #HARD_CAP}]. The default fraction is 1.0, so
 * a busy server ends up caching most of the dictionary it actually sees.
 * Past capacity the least diagnostic entries go first - low observation
 * weight near 0.5 carries no information and is evicted before a word with
 * a strong lean either way. Entries also expire after
 * {@value #TTL_MS} ms without reinforcement.
 *
 * <p>Thread-safe, allocation-light on reads, and fail-silent: every method
 * guards its own exceptions because a reputation cache must never be the
 * reason a message is blocked or the reason screening throws.
 */
public final class NeuralWordCache {

    /** How much of the model dictionary the cache may cover (0-1). */
    static final double DEFAULT_DICT_FRACTION = 1.0D;
    /** Smallest capacity, so tiny vocabs still cache usefully. */
    static final int MIN_CAP = 1024;
    /** Largest capacity, so a huge vocab cannot eat the heap. */
    static final int HARD_CAP = 32768;
    /** Weight of each new observation in the per-word lean. */
    static final double EMA = 0.35D;
    /** Sightings before a word's lean counts at full strength. */
    static final double FULL_WEIGHT = 3.0D;
    /** Entries unreinforced this long are forgotten. */
    static final long TTL_MS = 86_400_000L;

    /** Coverage at or above this lets a benign reputation downgrade a block. */
    static final double BENIGN_COVERAGE = 0.60D;
    /** Lean at or below this counts as a benign reputation. */
    static final double BENIGN_LEAN = 0.25D;
    /** Coverage at or above this lets an advert reputation support a flag. */
    static final double ADVERT_COVERAGE = 0.40D;
    /** Lean at or above this counts as an advert reputation. */
    static final double ADVERT_LEAN = 0.75D;
    /** Strongest pull the cache may exert on a model score. */
    static final double MAX_PULL = 0.50D;

    /**
     * What the cache knows about one message: how many of its words have a
     * reputation, and what that reputation says.
     */
    public record Prior(int known, int total, double advertLean) {
        /** Fraction of the message's words the cache recognises. */
        public double coverage() {
            return this.total == 0 ? 0.0D : (double) this.known / this.total;
        }
        /**
         * True when enough of the message is known-benign that a block
         * should be downgraded. Never true on an empty message.
         */
        public boolean benignKnown() {
            return this.total > 0 && coverage() >= BENIGN_COVERAGE
                    && this.advertLean <= BENIGN_LEAN;
        }
        /** True when enough of the message is known-advert to back a flag. */
        public boolean advertKnown() {
            return this.total > 0 && coverage() >= ADVERT_COVERAGE
                    && this.advertLean >= ADVERT_LEAN;
        }
    }

    /** No word recognised: neutral lean, zero coverage, adjusts nothing. */
    public static final Prior UNKNOWN = new Prior(0, 0, 0.5D);

    private record Stat(double lean, double weight, long updated) { }

    private final ConcurrentHashMap<String, Stat> map = new ConcurrentHashMap<>();
    private volatile int capacity;

    public NeuralWordCache(int vocabSize, double dictFraction) {
        resize(vocabSize, dictFraction);
    }

    /**
     * Resizes the cache to a fraction of the dictionary. Called on reload
     * when the model (and therefore the vocab size) is known.
     */
    public void resize(int vocabSize, double dictFraction) {
        double fraction = dictFraction < 0.0D ? 0.0D
                : Math.min(1.0D, dictFraction);
        this.capacity = Math.min(HARD_CAP,
                Math.max(MIN_CAP, (int) (vocabSize * fraction)));
        if (this.map.size() > this.capacity) evict();
    }

    /** Feeds a blocked message back: its words lean advertising. */
    public void confirmAdvert(String normalizedText) {
        confirm(normalizedText, 1.0D);
    }

    /** Feeds a cleared message back: its words lean benign. */
    public void confirmClean(String normalizedText) {
        confirm(normalizedText, 0.0D);
    }

    private void confirm(String normalizedText, double outcome) {
        if (normalizedText == null || normalizedText.isBlank()) return;
        try {
            List<String> tokens = MillenniumNet.tokenize(normalizedText);
            if (tokens.isEmpty()) return;
            long now = System.currentTimeMillis();
            for (String token : tokens) {
                if (token.length() > 32) continue;
                this.map.compute(token, (key, old) -> {
                    if (old == null) return new Stat(outcome, 1.0D, now);
                    double lean = old.lean + EMA * (outcome - old.lean);
                    return new Stat(lean, Math.min(64.0D, old.weight + 1.0D), now);
                });
            }
            if (this.map.size() > this.capacity) evict();
        } catch (Throwable ignored) {
            // Reputation learning must never break screening.
        }
    }

    /**
     * The cache's opinion on a message: mean lean of the words it
     * recognises, plus how many it recognised. Expired entries are skipped,
     * so a stale reputation neither protects nor accuses.
     */
    public Prior prior(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) return UNKNOWN;
        try {
            List<String> tokens = MillenniumNet.tokenize(normalizedText);
            if (tokens.isEmpty()) return UNKNOWN;
            long now = System.currentTimeMillis();
            double total = 0.0D;
            int known = 0;
            for (String token : tokens) {
                Stat stat = this.map.get(token);
                if (stat == null || now - stat.updated > TTL_MS) continue;
                total += effectiveLean(stat);
                known++;
            }
            if (known == 0) return new Prior(0, tokens.size(), 0.5D);
            return new Prior(known, tokens.size(), total / known);
        } catch (Throwable ignored) {
            return UNKNOWN;
        }
    }

    /**
     * Pulls a model score toward the word prior, proportional to coverage.
     * Full coverage moves the score at most halfway to the reputation;
     * zero coverage returns the score untouched.
     */
    public static double adjust(double modelScore, Prior prior) {
        if (prior == null || prior.total() == 0 || prior.known() == 0) return modelScore;
        double pull = Math.min(MAX_PULL, prior.coverage() * 0.6D);
        return modelScore * (1.0D - pull) + prior.advertLean() * pull;
    }

    /**
     * A word's usable lean: pulled toward neutral until it has been seen
     * {@value #FULL_WEIGHT} times. One sighting of a word inside a blocked
     * message makes it suspicious, not convicted - the third sighting is
     * what commits the reputation either way.
     */
    static double effectiveLean(Stat stat) {
        double strength = Math.min(1.0D, stat.weight / FULL_WEIGHT);
        return 0.5D + (stat.lean - 0.5D) * strength;
    }

    /** Drops expired entries, then the least diagnostic ones past capacity. */
    private void evict() {
        try {
            long now = System.currentTimeMillis();
            this.map.entrySet().removeIf(e -> now - e.getValue().updated > TTL_MS);
            int over = this.map.size() - this.capacity;
            if (over <= 0) return;
            // Least diagnostic first: weakly-held leans near 0.5 know nothing.
            // A bounded number of passes keeps this O(n) and rare.
            for (int pass = 0; pass < 6 && over > 0; pass++) {
                double threshold = 0.05D * (pass + 1);
                var it = this.map.entrySet().iterator();
                while (it.hasNext() && over > 0) {
                    Stat s = it.next().getValue();
                    double diagnostic = s.weight * Math.abs(effectiveLean(s) - 0.5D);
                    if (diagnostic < threshold) {
                        it.remove();
                        over--;
                    }
                }
            }
            if (over > 0) {
                // Guarantee the bound: drop the stalest, least-held entries.
                var entries = new java.util.ArrayList<>(this.map.entrySet());
                entries.sort((x, y) -> {
                    int byWeight = Double.compare(x.getValue().weight, y.getValue().weight);
                    return byWeight != 0 ? byWeight
                            : Long.compare(x.getValue().updated, y.getValue().updated);
                });
                for (int i = 0; i < over && i < entries.size(); i++) {
                    this.map.remove(entries.get(i).getKey());
                }
            }
        } catch (Throwable ignored) {
            // Eviction is housekeeping; never let it break screening.
        }
    }

    /** Words currently cached, for diagnostics. */
    public int size() {
        return this.map.size();
    }

    public void clear() {
        this.map.clear();
    }
}
