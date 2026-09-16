package com.gildedmc.core.modules;

import java.util.Locale;

/**
 * Turns a chat line into a sparse numeric vector, with no dictionary to load.
 *
 * <h2>Feature hashing</h2>
 * Every feature — a word, a pair of words, a run of characters — is hashed
 * straight to a column index. There is no vocabulary file to build, ship,
 * version or keep in sync with the model, and a word nobody has ever typed
 * before still lands somewhere sensible. Collisions happen and are harmless at
 * this width: two rare features sharing a column costs a little accuracy, it
 * does not break anything.
 *
 * <h2>Why character n-grams matter here</h2>
 * Word features alone are useless against a playerbase that spells things
 * {@code n1663r} or {@code f  u  c  k}. Character 3- and 4-grams taken from the
 * <em>normalised</em> form — accents folded, lookalikes mapped, separators
 * stripped by {@link ChatGuardModule#normalise} — survive that, because the
 * obfuscated spelling still shares most of its character runs with the plain
 * one. That is the whole reason a linear model can generalise past the hand
 * written patterns that trained it.
 *
 * <h2>Cost</h2>
 * One pass over the string, no allocation beyond the index and value arrays.
 * This is the part that has to be free, because it runs on every chat message.
 */
public final class TextFeatures {

    private TextFeatures() { }

    /** Column count. A power of two so the modulo is a mask. */
    /**
     * Column count. A power of two so the modulo is a mask.
     *
     * <p>2^19 rather than something smaller because there is no reason to be
     * stingy: at seven labels this is 14 MB of weights, which is nothing beside
     * a server's heap, and the extra width means fewer hash collisions between
     * the character n-grams that carry most of the signal.
     */
    public static final int DIMENSION = 1 << 19;         // 524288
    private static final int MASK = DIMENSION - 1;

    /** A hashed, L2-normalised sparse vector. */
    public record Vector(int[] indices, float[] values) {

        public int size() { return this.indices.length; }
    }

    /**
     * Extracts features from one message.
     *
     * <p>Both forms of the text are used on purpose: the space-preserving form
     * for word features, and the fully normalised skeleton for character runs.
     */
    public static Vector extract(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Vector(new int[0], new float[0]);
        }
        String words = ChatGuardModule.words(raw);
        String skeleton = ChatGuardModule.normalise(raw);

        // Generous upper bound; trimmed before returning. Must cover the leet
        // folded n-grams too, or features are silently dropped off the end.
        int capacity = 48 + words.length() * 2 + skeleton.length() * 8;
        int[] idx = new int[capacity];
        float[] val = new float[capacity];
        int n = 0;

        // Word unigrams and bigrams.
        String[] tokens = words.isEmpty() ? new String[0] : unleet(words).split(" ");
        String previous = null;
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            if (n < capacity) { idx[n] = hash("w:" + token); val[n] = 1.0F; n++; }
            if (previous != null && n < capacity) {
                idx[n] = hash("b:" + previous + "_" + token);
                val[n] = 1.0F;
                n++;
            }
            previous = token;
        }

        // Character 3- and 4-grams, taken from the leet-folded skeleton.
        //
        // Folding happens BEFORE hashing and lands in the same feature space as
        // ordinary text, which is the whole trick: "idiot" and "1d10t" collapse
        // to identical n-grams, so an example of one teaches the model about
        // the other. Emitting the folded runs under their own prefix instead
        // looks reasonable and is useless — plain training text never produces
        // those features, so they would be trained on nothing and every
        // obfuscated message would score against zero weights.
        String folded = unleet(skeleton);
        for (int size = 3; size <= 4; size++) {
            for (int i = 0; i + size <= folded.length() && n < capacity; i++) {
                idx[n] = hash("c" + size + ":" + folded.substring(i, i + size));
                val[n] = 1.0F;
                n++;
            }
        }
        if (!folded.equals(skeleton)) {
            // The UNFOLDED runs are emitted as well, into the same space.
            //
            // Leet folding and Arabizi collide head on: in leetspeak 3 is an e
            // and 7 is a t, while in Arabizi 3 is ع and 7 is ح, so folding
            // "3aby ya 7mar" mangles perfectly ordinary Egyptian Arabic written
            // in Latin letters. Emitting both forms means neither has to win —
            // leetspeak matches on the folded runs, Arabizi on the raw ones,
            // and the model learns which features actually predict what.
            for (int size = 3; size <= 4; size++) {
                for (int i = 0; i + size <= skeleton.length() && n < capacity; i++) {
                    idx[n] = hash("c" + size + ":" + skeleton.substring(i, i + size));
                    val[n] = 1.0F;
                    n++;
                }
            }
            if (n < capacity) { idx[n] = hash("obfuscated"); val[n] = 1.0F; n++; }
        }
        // Which script this was written in. Lets the model learn that a given
        // offence looks different in Devanagari than it does in Latin, instead
        // of pooling every language into one undifferentiated blob.
        if (n < capacity) {
            idx[n] = hash("script:" + Scripts.detect(raw).name());
            val[n] = 1.0F;
            n++;
        }

        // A few shape features. Bucketed, because the exact number is noise.
        if (n < capacity) { idx[n] = hash("len:" + bucket(raw.length())); val[n] = 1.0F; n++; }
        if (n < capacity) { idx[n] = hash("caps:" + capsBucket(raw)); val[n] = 1.0F; n++; }
        if (n < capacity) { idx[n] = hash("digit:" + digitBucket(raw)); val[n] = 1.0F; n++; }
        if (n < capacity) { idx[n] = hash("bias"); val[n] = 1.0F; n++; }

        // L2 normalise, so a long message does not simply outvote a short one.
        double sum = 0.0D;
        for (int i = 0; i < n; i++) sum += val[i] * val[i];
        float scale = sum > 0 ? (float) (1.0D / Math.sqrt(sum)) : 1.0F;

        int[] outIdx = new int[n];
        float[] outVal = new float[n];
        System.arraycopy(idx, 0, outIdx, 0, n);
        for (int i = 0; i < n; i++) outVal[i] = val[i] * scale;
        return new Vector(outIdx, outVal);
    }

    /**
     * Maps the usual digit and symbol substitutions back to letters.
     *
     * <p>Deliberately one-way and lossy: {@code 1} could be {@code i} or
     * {@code l}, and picking one consistently is what matters, not picking the
     * right one. Both spellings land on the same feature either way, which is
     * the entire point.
     */
    static String unleet(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(switch (c) {
                case '0' -> 'o';
                case '1', '!', '|' -> 'i';
                case '3' -> 'e';
                case '4', '@' -> 'a';
                case '5', '$' -> 's';
                case '7' -> 't';
                case '8' -> 'b';
                case '9', '6' -> 'g';
                case '+' -> 't';
                case '(', '<' -> 'c';
                default -> c;
            });
        }
        return b.toString();
    }

    /** FNV-1a, folded into the column space. Cheap and well spread. */
    static int hash(String s) {
        int h = 0x811C9DC5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }
        return (h ^ (h >>> 16)) & MASK;
    }

    private static String bucket(int length) {
        if (length < 8) return "xs";
        if (length < 24) return "s";
        if (length < 64) return "m";
        if (length < 128) return "l";
        return "xl";
    }

    private static String capsBucket(String raw) {
        int letters = 0, caps = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) caps++;
            }
        }
        if (letters == 0) return "none";
        int pct = caps * 100 / letters;
        return pct >= 70 ? "high" : pct >= 30 ? "mid" : "low";
    }

    private static String digitBucket(String raw) {
        int digits = 0;
        for (int i = 0; i < raw.length(); i++) if (Character.isDigit(raw.charAt(i))) digits++;
        if (digits == 0) return "none";
        int pct = digits * 100 / Math.max(1, raw.length());
        return pct >= 30 ? "high" : pct >= 10 ? "mid" : "low";
    }

    /** Lowercase, for label handling. */
    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
    }
}
