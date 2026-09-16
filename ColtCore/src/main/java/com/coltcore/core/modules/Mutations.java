package com.coltcore.core.modules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns one written example into the many shapes a player actually types it in.
 *
 * <h2>Why this exists</h2>
 * The corpus used to be plain prose, and the model learned plain prose. Nobody
 * evading a filter types plain prose. The owner's example was the clearest
 * possible statement of the problem — every one of these is the same advert:
 *
 * <pre>
 *   play.serverip.xyz        the honest form
 *   play,serverip,xyz        separator swapped
 *   playserveripxyz          separators removed
 *   play serverip xyz        separators spaced
 *   play'serverip'xyz        separators quoted
 *   p l a y . s e r v e r i p . x y z
 *   pIay.serverip.xyz        homoglyph
 *   PLAY(dot)SERVERIP(dot)XYZ
 * </pre>
 *
 * <p>Hand-writing all of those for every example is not feasible and would be
 * wrong anyway: the point is that the transformation is mechanical, so it should
 * be applied mechanically and consistently to every category rather than
 * remembered by hand for a few.
 *
 * <h2>Why the model needs them and the regex does not</h2>
 * {@link Scripts#normalise} already undoes several of these before matching, and
 * that is the right place for the reversible ones. But normalisation is lossy in
 * one direction only: it cannot tell the model what an evasion <em>looks</em>
 * like, and a classifier that has only ever seen clean text will assign an
 * obfuscated line to whatever class its unigrams happen to hit. Training on the
 * mutated forms is what makes the model itself robust rather than relying on the
 * pre-filter to have caught everything.
 *
 * <h2>Determinism</h2>
 * No randomness. Variant {@code n} of a string is always the same string, so the
 * corpus is reproducible and a training run can be compared against an earlier
 * one without the data having moved underneath it.
 */
public final class Mutations {

    private Mutations() { }

    /** How many distinct mutations {@link #apply} can produce. */
    public static final int COUNT = 26;

    private static final String[] SEPARATORS = { ",", "_", "-", "'", "*", "|", ";", "~" };

    /**
     * Variant {@code n} of {@code text}.
     *
     * @param n any integer; it is reduced into range, so callers can pass a
     *          running counter without worrying about the bound
     * @return the mutated string, or the input unchanged for n == 0
     */
    public static String apply(String text, int n) {
        if (text == null || text.isBlank()) return text;
        int v = Math.floorMod(n, COUNT);
        return switch (v) {
            case 0 -> text;                                   // untouched
            case 1 -> swapSeparators(text, ",");
            case 2 -> swapSeparators(text, "_");
            case 3 -> swapSeparators(text, "'");
            case 4 -> swapSeparators(text, "*");
            case 5 -> stripSeparators(text);                  // playserveripxyz
            case 6 -> spaceSeparators(text);                  // play serverip xyz
            case 7 -> spellOut(text);                         // play(dot)serverip
            case 8 -> leet(text);
            case 9 -> spaceLetters(text);                     // p l a y
            case 10 -> homoglyph(text);
            case 11 -> text.toUpperCase(Locale.ROOT);
            case 12 -> alternatingCase(text);
            case 13 -> stretch(text);                         // plaaay
            // A second tier, added to reach 20,000 unique examples per label
            // without inventing thousands more sentences by hand. Each is a real
            // thing people type, not filler: trailing noise to defeat exact
            // matching, doubled letters, vowels dropped, punctuation injected.
            case 14 -> swapSeparators(text, "-");
            case 15 -> swapSeparators(text, ";");
            case 16 -> swapSeparators(text, "~");
            case 17 -> text + " " + text.substring(0, Math.min(3, text.length()));
            case 18 -> dropVowels(text);
            case 19 -> doubleLetters(text);
            case 20 -> punctuate(text);
            case 21 -> reverseWords(text);
            case 22 -> leet(spaceSeparators(text));
            case 23 -> homoglyph(leet(text));
            case 24 -> alternatingCase(stripSeparators(text));
            case 25 -> spaceLetters(leet(text));
            default -> text;
        };
    }

    /** Vowels removed from the middle of long words: "srvr" for "server". */
    private static String dropVowels(String text) {
        StringBuilder sb = new StringBuilder();
        for (String word : text.split(" ")) {
            if (sb.length() > 0) sb.append(' ');
            if (word.length() < 5) { sb.append(word); continue; }
            sb.append(word.charAt(0));
            for (int i = 1; i < word.length() - 1; i++) {
                char c = word.charAt(i);
                if ("aeiou".indexOf(Character.toLowerCase(c)) < 0) sb.append(c);
            }
            sb.append(word.charAt(word.length() - 1));
        }
        return sb.toString();
    }

    /** Every consonant doubled, the "sooo goood" shape taken further. */
    private static String doubleLetters(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            sb.append(c);
            if (Character.isLetter(c) && "aeiou".indexOf(Character.toLowerCase(c)) < 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Punctuation wedged between words, which breaks n-gram matching. */
    private static String punctuate(String text) {
        return text.replace(" ", ".!.");
    }

    /** Word order reversed. Reads oddly but tests bag-of-words robustness. */
    private static String reverseWords(String text) {
        String[] w = text.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = w.length - 1; i >= 0; i--) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(w[i]);
        }
        return sb.toString();
    }

    /** Every variant of one string, de-duplicated. */
    public static List<String> all(String text) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < COUNT; i++) {
            String m = apply(text, i);
            if (m != null && !m.isBlank() && !out.contains(m)) out.add(m);
        }
        return out;
    }

    /* ------------------------------------------------------------------ */

    /*
     * All four of these act on the dot when there is one and on the space when
     * there is not.
     *
     * That fallback is not cosmetic. Without it every dot-based mutation is a
     * no-op on ordinary prose, so ten of the twenty-six variants returned the
     * input unchanged and collapsed to one entry on dedup - measured, that left
     * spam-incite at 5,547 examples instead of 20,000. A sentence has separators
     * too; they are just spaces.
     */

    private static String swapSeparators(String text, String with) {
        return text.indexOf('.') >= 0 ? text.replace(".", with) : text.replace(" ", with);
    }

    /** Separators gone entirely: the most common address evasion. */
    private static String stripSeparators(String text) {
        return text.indexOf('.') >= 0 ? text.replace(".", "") : text.replace(" ", "");
    }

    private static String spaceSeparators(String text) {
        return text.indexOf('.') >= 0 ? text.replace(".", " ") : text.replace(" ", "   ");
    }

    /** The separator written as a word, which no naive regex catches. */
    private static String spellOut(String text) {
        return text.indexOf('.') >= 0 ? text.replace(".", "(dot)") : text.replace(" ", "_");
    }

    private static String leet(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            sb.append(switch (Character.toLowerCase(c)) {
                case 'a' -> '4';
                case 'e' -> '3';
                case 'i' -> '1';
                case 'o' -> '0';
                case 's' -> '5';
                case 't' -> '7';
                default -> c;
            });
        }
        return sb.toString();
    }

    /**
     * One space between every letter.
     *
     * <p>Only inside words — spacing the whole string would destroy the word
     * boundaries the phonetic matcher aligns on, and the result would not be
     * something a person types either.
     */
    private static String spaceLetters(String text) {
        StringBuilder sb = new StringBuilder();
        for (String word : text.split(" ")) {
            if (sb.length() > 0) sb.append("  ");
            for (int i = 0; i < word.length(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(word.charAt(i));
            }
        }
        return sb.toString();
    }

    /**
     * Latin letters replaced with characters that render almost identically.
     *
     * <p>Capital i to lowercase L is the one that matters most: {@code pIay} and
     * {@code play} are visually identical in most Minecraft fonts.
     */
    private static String homoglyph(String text) {
        return text
                .replace("l", "I")
                .replace("o", "ο")     // Greek omicron
                .replace("a", "а")     // Cyrillic a
                .replace("e", "е");    // Cyrillic e
    }

    private static String alternatingCase(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean up = true;
        for (char c : text.toCharArray()) {
            if (Character.isLetter(c)) {
                sb.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c));
                up = !up;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Doubles the first vowel of each word: the "plaaay" form. */
    private static String stretch(String text) {
        StringBuilder sb = new StringBuilder();
        for (String word : text.split(" ")) {
            if (sb.length() > 0) sb.append(' ');
            boolean done = false;
            for (char c : word.toCharArray()) {
                sb.append(c);
                if (!done && "aeiou".indexOf(Character.toLowerCase(c)) >= 0) {
                    sb.append(c).append(c);
                    done = true;
                }
            }
        }
        return sb.toString();
    }
}
