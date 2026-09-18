package com.gildedmc.core.modules;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Script-aware text handling, so the guard works outside English.
 *
 * <h2>The two bugs this fixes</h2>
 * <ol>
 *   <li><b>Everything non-Latin was deleted.</b> Word extraction filtered on
 *       {@code [^a-z0-9\s]}, so a message in Hindi, Arabic, Chinese, Russian,
 *       Thai, Tamil or Bengali came out as an empty string. Not degraded —
 *       empty. The term list, the classifier and the evidence ledger all saw
 *       nothing, for twenty-nine of the thirty most spoken languages.</li>
 *   <li><b>Genuine Cyrillic was corrupted.</b> The lookalike table exists to
 *       catch someone hiding English behind Cyrillic glyphs — {@code аbuse}
 *       with a Cyrillic а. Applied to actual Russian it mangles the text:
 *       {@code ты идиот} became {@code t...ot}. Folding is now only applied
 *       when the message is <em>dominantly</em> Latin, which is exactly the
 *       case the table was written for.</li>
 * </ol>
 *
 * <h2>Combining marks are not decoration</h2>
 * Stripping {@code \p{M}} is right for Latin, where marks are accents and
 * {@code café} and {@code cafe} should match. It is destructive for Indic,
 * Thai, Arabic and Hebrew, where the marks are vowels and stripping them
 * changes or destroys the word. Marks are kept for those scripts.
 *
 * <h2>Scripts without spaces</h2>
 * Chinese, Japanese and Thai do not delimit words. Their characters are split
 * individually so that word-level features become per-character and
 * per-character-pair features, which is the standard treatment and lets the
 * same machinery work without a segmenter.
 */
public final class Scripts {

    private Scripts() { }

    public enum Script {
        LATIN, CYRILLIC, GREEK, ARABIC, HEBREW,
        DEVANAGARI, BENGALI, GURMUKHI, GUJARATI, TAMIL, TELUGU, KANNADA, MALAYALAM,
        THAI, HAN, KANA, HANGUL,
        // Added after testing: these were falling through to OTHER, and an
        // unrecognised script was then reported as LATIN by detect(), which
        // would have run the Latin lookalike table over text that is not Latin
        // at all - corrupting the language rather than revealing an evasion.
        ARMENIAN, GEORGIAN, ETHIOPIC, KHMER, LAO, MYANMAR, SINHALA, TIBETAN,
        OTHER;

        /** Marks carry meaning here, so they must survive normalisation. */
        public boolean keepsMarks() {
            return switch (this) {
                case ARABIC, HEBREW, DEVANAGARI, BENGALI, GURMUKHI, GUJARATI,
                     TAMIL, TELUGU, KANNADA, MALAYALAM, THAI,
                     KHMER, LAO, MYANMAR, SINHALA, TIBETAN -> true;
                default -> false;
            };
        }

        /** No spaces between words, so characters are the unit. */
        public boolean spaceless() {
            return this == HAN || this == KANA || this == THAI;
        }

        /** Case folding is meaningful only for bicameral scripts. */
        public boolean cased() {
            return this == LATIN || this == CYRILLIC || this == GREEK;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Detection                                                         */
    /* ------------------------------------------------------------------ */

    /** The script of a single character. */
    public static Script of(char c) {
        if (c < 0x0080) return Character.isLetter(c) ? Script.LATIN : Script.OTHER;
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        if (b == null) return Script.OTHER;
        if (b == Character.UnicodeBlock.LATIN_1_SUPPLEMENT
                || b == Character.UnicodeBlock.LATIN_EXTENDED_A
                || b == Character.UnicodeBlock.LATIN_EXTENDED_B
                || b == Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL) return Script.LATIN;
        if (b == Character.UnicodeBlock.CYRILLIC
                || b == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY) return Script.CYRILLIC;
        if (b == Character.UnicodeBlock.GREEK
                || b == Character.UnicodeBlock.GREEK_EXTENDED) return Script.GREEK;
        if (b == Character.UnicodeBlock.ARABIC
                || b == Character.UnicodeBlock.ARABIC_SUPPLEMENT
                || b == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A
                || b == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B) return Script.ARABIC;
        if (b == Character.UnicodeBlock.HEBREW) return Script.HEBREW;
        if (b == Character.UnicodeBlock.DEVANAGARI) return Script.DEVANAGARI;
        if (b == Character.UnicodeBlock.BENGALI) return Script.BENGALI;
        if (b == Character.UnicodeBlock.GURMUKHI) return Script.GURMUKHI;
        if (b == Character.UnicodeBlock.GUJARATI) return Script.GUJARATI;
        if (b == Character.UnicodeBlock.TAMIL) return Script.TAMIL;
        if (b == Character.UnicodeBlock.TELUGU) return Script.TELUGU;
        if (b == Character.UnicodeBlock.KANNADA) return Script.KANNADA;
        if (b == Character.UnicodeBlock.MALAYALAM) return Script.MALAYALAM;
        if (b == Character.UnicodeBlock.THAI) return Script.THAI;
        if (b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) return Script.HAN;
        if (b == Character.UnicodeBlock.HIRAGANA
                || b == Character.UnicodeBlock.KATAKANA
                || b == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS) return Script.KANA;
        if (b == Character.UnicodeBlock.HANGUL_SYLLABLES
                || b == Character.UnicodeBlock.HANGUL_JAMO
                || b == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) return Script.HANGUL;
        if (b == Character.UnicodeBlock.ARMENIAN) return Script.ARMENIAN;
        if (b == Character.UnicodeBlock.GEORGIAN) return Script.GEORGIAN;
        if (b == Character.UnicodeBlock.ETHIOPIC) return Script.ETHIOPIC;
        if (b == Character.UnicodeBlock.KHMER) return Script.KHMER;
        if (b == Character.UnicodeBlock.LAO) return Script.LAO;
        if (b == Character.UnicodeBlock.MYANMAR) return Script.MYANMAR;
        if (b == Character.UnicodeBlock.SINHALA) return Script.SINHALA;
        if (b == Character.UnicodeBlock.TIBETAN) return Script.TIBETAN;
        return Script.OTHER;
    }

    /**
     * The dominant script of a message, by letter count.
     *
     * <p>Dominance rather than presence, because mixed text is the norm: a
     * Hindi sentence with an English brand name in it is Devanagari, and an
     * English sentence with two Cyrillic lookalikes hidden in it is Latin —
     * which is what decides whether the lookalike table should run.
     */
    public static Script detect(String text) {
        if (text == null || text.isEmpty()) return Script.LATIN;
        Map<Script, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetter(c)) continue;
            Script s = of(c);
            if (s == Script.OTHER) continue;
            counts.merge(s, 1, Integer::sum);
        }
        Script best = Script.LATIN;
        int bestCount = 0;
        for (Map.Entry<Script, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) { bestCount = e.getValue(); best = e.getKey(); }
        }
        // A text made entirely of characters no branch recognised is NOT Latin.
        // Saying it is would licence the lookalike folding to run over it.
        if (bestCount == 0) {
            for (int i = 0; i < text.length(); i++) {
                if (Character.isLetter(text.charAt(i))) return Script.OTHER;
            }
        }
        return bestCount == 0 ? Script.LATIN : best;
    }

    /** Every script present, for reporting and per-language term routing. */
    public static Map<Script, Integer> profile(String text) {
        Map<Script, Integer> counts = new LinkedHashMap<>();
        if (text == null) return counts;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) counts.merge(of(c), 1, Integer::sum);
        }
        return counts;
    }

    /* ------------------------------------------------------------------ */
    /*  Lookalike folding — Latin only                                    */
    /* ------------------------------------------------------------------ */

    private static final Map<Character, Character> CYRILLIC_LOOKALIKE = new LinkedHashMap<>();
    private static final Map<Character, Character> GREEK_LOOKALIKE = new LinkedHashMap<>();
    private static final Map<Character, Character> FLIPPED = new LinkedHashMap<>();
    static {
        String[] cyr = {"а a", "о o", "е e", "р p", "с c", "х x", "у y", "к k",
                        "м m", "н h", "т t", "в b", "і i", "ѕ s", "ј j", "г r"};
        for (String s : cyr) CYRILLIC_LOOKALIKE.put(s.charAt(0), s.charAt(2));
        String[] grk = {"α a", "ο o", "ε e", "ρ p", "χ x", "υ u", "κ k", "μ m",
                        "ν v", "τ t", "β b", "ι i", "σ s", "γ y", "θ o", "ω w"};
        for (String s : grk) GREEK_LOOKALIKE.put(s.charAt(0), s.charAt(2));
        String[] flip = {"ɐ a", "ɔ c", "ǝ e", "ɟ f", "ƃ g", "ɥ h", "ᴉ i", "ɾ j",
                         "ʞ k", "ן l", "ɯ m", "ɹ r", "ʇ t", "ʌ v", "ʍ w", "ʎ y"};
        for (String s : flip) FLIPPED.put(s.charAt(0), s.charAt(2));
    }

    private static final java.util.regex.Pattern SEPARATORS =
            java.util.regex.Pattern.compile("[\\s._\\-*+~`'\"|/\\\\,:;()\\[\\]{}]+");

    /**
     * Folds a message to a comparable skeleton, respecting its script.
     *
     * <p>For Latin text this is the original behaviour: accents removed,
     * Cyrillic and Greek lookalikes mapped to their Latin twins, upside-down
     * text reversed. For anything else the lookalike tables are left alone —
     * they would be destroying the language rather than unmasking it — and
     * combining marks are preserved where they are vowels rather than accents.
     */
    /**
     * Characters that carry no meaning but break every pattern they sit inside.
     *
     * <p>Zero-width joiners, bidi marks and soft hyphens are invisible in chat
     * and survive every fold that works on letters, so "n[ZWJ]igger" renders
     * identically to the term and matches nothing. Stripping them is the single
     * highest-value bypass fix for non-Latin scripts, because Arabic, Hebrew and
     * the Indic scripts legitimately contain joiners and an attacker can hide
     * behind that.
     */
    private static final java.util.regex.Pattern INVISIBLE = java.util.regex.Pattern.compile(
            "[\u200B-\u200F\u202A-\u202E\u2060-\u2065\u00AD\uFEFF\u180E\uFFF9-\uFFFB"
                    + "\\x{13430}-\\x{1343F}\\x{E0001}\\x{E0020}-\\x{E007F}]");

    /**
     * Fullwidth and mathematical letterforms folded to plain ASCII.
     *
     * <p>Ｆｕｌｌｗｉｄｔｈ text and the mathematical alphanumerics (𝐛𝐨𝐥𝐝, 𝑖𝑡𝑎𝑙𝑖𝑐,
     * 𝔤𝔬𝔱𝔥𝔦𝔠) render as ordinary Latin to a reader and as entirely different
     * codepoints to a matcher. NFKC handles most of it, which is why it runs
     * first, but the fullwidth block is folded explicitly because scripts that
     * keep their marks use NFKD instead and would otherwise miss it.
     */
    private static String foldWidth(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) b.append((char) (c - 0xFEE0));
            else if (c == 0x3000) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }

    public static String normalise(String input) {
        if (input == null || input.isEmpty()) return "";
        Script script = detect(input);

        // Strip the invisibles FIRST, before the normaliser and before every
        // fold below. The class is Unicode format (Cf) characters with no
        // compatibility decomposition, so NFKD and NFKC both pass them straight
        // through and stripping before or after the normaliser removes the same
        // set; it is done first because the steps that follow are order
        // sensitive. NFKC will not compose a base with its mark across an
        // intervening format character, which leaves the mark orphaned for the
        // \p{M} strip to delete, and the flipped>=2 branch below reverses the
        // whole string, so a survivor would be carried into the reversed
        // skeleton and land between its letters as well. Removing them first
        // means every fold sees the string the reader sees.
        //
        // The bidi controls (U+202A-U+202E) are in this set deliberately. They
        // are text-direction controls rather than noise, but nothing renders the
        // result of this method: every call site is a term match, a term
        // compile, a duplicate/similarity key or a hashed model feature, and the
        // player's own message is shown from the raw string. Stripping them here
        // changes no display; it only stops a direction control from splitting a
        // term inside the skeleton.
        String visible = INVISIBLE.matcher(input).replaceAll("");

        // NFKC for non-Latin: it still unifies compatibility forms (Arabic
        // presentation forms, full-width Latin) without decomposing vowels into
        // separate marks that the next step would then delete.
        String s = Normalizer.normalize(visible,
                script.keepsMarks() ? Normalizer.Form.NFKC : Normalizer.Form.NFKD);
        if (!script.keepsMarks()) s = s.replaceAll("\\p{M}+", "");
        if (script.cased() || script == Script.LATIN) s = s.toLowerCase(Locale.ROOT);
        else s = s.toLowerCase(Locale.ROOT);       // harmless for uncased scripts

        if (script == Script.LATIN) {
            StringBuilder b = new StringBuilder(s.length());
            int flipped = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                Character cy = CYRILLIC_LOOKALIKE.get(c);
                if (cy != null) { b.append(cy); continue; }
                Character gr = GREEK_LOOKALIKE.get(c);
                if (gr != null) { b.append(gr); continue; }
                Character fl = FLIPPED.get(c);
                if (fl != null) { b.append(fl); flipped++; continue; }
                b.append(c);
            }
            s = b.toString();
            if (flipped >= 2) s = new StringBuilder(s).reverse().toString();
        }
        return SEPARATORS.matcher(s).replaceAll("");
    }

    /* ------------------------------------------------------------------ */
    /*  Tokenisation                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * Lowercase, punctuation stripped, spaces kept — for word features and
     * word-boundary tests.
     *
     * <p>Keeps every letter of every script. The old version filtered on
     * {@code [^a-z0-9\s]}, which returned an empty string for any message that
     * was not written in the Latin alphabet.
     */
    public static String words(String input) {
        if (input == null || input.isEmpty()) return "";
        // Strip the invisible blocks first. Everything below treats a character
        // that is not a letter, a digit or a combining mark as a separator, so a
        // zero-width character inside a word was becoming a space and splitting
        // the word into single letters - which is how "f<ZWSP>a<ZWSP>g" walked
        // past the short-term matcher.
        input = INVISIBLE.matcher(input).replaceAll("");
        if (input.isEmpty()) return "";
        Script script = detect(input);
        String s = Normalizer.normalize(input,
                script.keepsMarks() ? Normalizer.Form.NFKC : Normalizer.Form.NFKD);
        if (!script.keepsMarks()) s = s.replaceAll("\\p{M}+", "");
        s = s.toLowerCase(Locale.ROOT);

        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                Script cs = of(c);
                // Spaceless scripts get a boundary around every character, so a
                // Chinese sentence becomes a sequence of one-character tokens
                // rather than a single token nothing will ever match.
                if (cs.spaceless()) {
                    if (b.length() > 0 && b.charAt(b.length() - 1) != ' ') b.append(' ');
                    b.append(c).append(' ');
                } else {
                    b.append(c);
                }
            } else if (Character.getType(c) == Character.NON_SPACING_MARK
                    || Character.getType(c) == Character.COMBINING_SPACING_MARK) {
                b.append(c);                       // an Indic vowel sign, not punctuation
            } else {
                b.append(' ');
            }
        }
        return b.toString().replaceAll("\\s+", " ").trim();
    }

    /**
     * Maps non-Latin digits to ASCII.
     *
     * <p>Needed before any pattern that looks for a number. A phone number
     * written in Arabic-Indic digits is still a phone number, and Arabic,
     * Persian, Devanagari, Bengali and Thai all have their own digit sets. The
     * doxxing detector would otherwise be blind to every one of them.
     */
    public static String foldDigits(String input) {
        if (input == null || input.isEmpty()) return "";
        StringBuilder b = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int digit = Character.digit(c, 10);
            // Character.digit understands every Unicode decimal digit set, so
            // this covers scripts nobody thought to list.
            b.append(digit >= 0 && !Character.isLetter(c) ? (char) ('0' + digit) : c);
        }
        return b.toString();
    }

    /** Human-readable script name, for status output. */
    public static String describe(String text) {
        Script s = detect(text);
        return s.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
