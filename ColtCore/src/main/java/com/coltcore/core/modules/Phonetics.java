package com.coltcore.core.modules;

import java.util.Locale;

/**
 * Matches a word by how it <em>sounds</em>, so a slur respelled to survive the
 * literal filter is still caught.
 *
 * <h2>The evasion this defeats</h2>
 * The regex layer in {@code ChatGuardModule.compileTerm} already folds leetspeak,
 * repeated letters, inserted padding and separators, so it handles
 * {@code n1gg3r}, {@code niiigger} and {@code nig ger} without help. What it
 * cannot do is respelling that changes which letters are there at all:
 * {@code phaggot}, {@code fgt}, {@code reetard}. Those are the same sounds
 * written differently, and sound is what this file compares.
 *
 * <h2>What was wrong with the first version</h2>
 * It flagged {@code gg wp}, {@code brb food}, {@code wsp} and {@code yo whats
 * up}. Three independent defects, all of which made it fire on ordinary chat:
 *
 * <ol>
 *   <li><b>Vowels were deleted outright.</b> A pure consonant skeleton is far
 *       too lossy: {@code fact} and {@code faggot} both reduce to {@code FKT},
 *       so any sentence with those three sounds in order was a slur. Vowels are
 *       now kept as a single {@code @} marker — identity discarded, because
 *       evaders swap vowels freely, but <em>position</em> retained, because they
 *       cannot remove a syllable without changing the word. {@code fact} is
 *       {@code F@KT} and {@code faggot} is {@code F@K@T}, and those are no
 *       longer the same string.</li>
 *   <li><b>Matching was substring containment.</b> {@code brb food} codes to
 *       {@code BRBFT}, which <em>contains</em> {@code FT}, so it matched four
 *       different terms at once. A homophone evasion is the whole span by
 *       construction — that is what makes it a homophone — so the span must now
 *       <em>equal</em> the term, give or take the insertion budget. A term
 *       buried inside a longer word is the literal matcher's job, not this
 *       one's.</li>
 *   <li><b>The minimum length was only enforced on the fuzzy path.</b> The
 *       containment path had no floor at all, so a one-character code sailed
 *       through it. That is the specific reason {@code gg wp} was reported as a
 *       slur: it has a K sound, and a foreign term in the old lexicon encoded to
 *       exactly {@code K}. The floor is now checked once, up front, and applies
 *       to every path.</li>
 * </ol>
 *
 * <p>The fourth cause was the vocabulary rather than the algorithm, and is fixed
 * in {@link Lexicon}: 24 languages of terms were being run through an
 * <em>English</em> encoder, which does not produce a pronunciation for them, it
 * produces debris.
 *
 * <h2>What this deliberately does not do</h2>
 * It does not span words. The textbook homophone evasion — {@code knee gear} —
 * needs multi-word spans, and multi-word spans were built, measured and removed,
 * because on a Minecraft server {@code no gear}, {@code neth gear} and
 * {@code need gear} encode identically to that slur and are said constantly,
 * while {@code knee gear} is said never. The same held for every other term
 * checked: {@code tp for good} scored as one slur, {@code baby zombie} as
 * another, {@code PAY 1M GETS} as a third. Adjacent-pair spans multiply the
 * chance of an accidental collision by the length of the message, in exchange
 * for an evasion nobody uses.
 *
 * <p>Insertions are allowed but substitutions never are, because one
 * substitution turns almost any short code into almost any other — that is what
 * let {@code organ} match {@code zorgan}. The insertion budget is also confined
 * to codes of at least {@value #FUZZY_MIN_CODE} characters, since below that an
 * inserted sound is most of the word: at budget 1, {@code raghead} matched
 * {@code cracked} and {@code nigger} matched {@code nuclear}.
 *
 * <h2>Disemvowelled text is a separate case</h2>
 * {@code fgt} is not a homophone, it is an abbreviation, and it has no vowels to
 * compare. A token containing no vowel letters is therefore matched against the
 * term's consonants alone. That is a deliberately weaker test, so it is confined
 * to tokens that actually look disemvowelled — {@code fact} has vowels and never
 * reaches it.
 *
 * <h2>English spelling is not a function</h2>
 * Metaphone's rule that {@code G} before {@code E} is soft is simply wrong for
 * {@code gear}, {@code get}, {@code girl}, {@code give}. Rather than special
 * case a word list, both readings are produced and either may match.
 */
public final class Phonetics {

    private Phonetics() { }

    private static final String VOWELS = "AEIOU";

    /** Stands in for any vowel run. Position is kept, identity is not. */
    public static final char VOWEL_MARK = '@';

    /**
     * Fraction of single-character tokens above which a message is treated as
     * letter-spaced and re-tested with the spaces removed.
     */
    private static final double SPACED_RATIO = 0.5;

    /**
     * Code length at or above which insertions are tolerated.
     *
     * <p>Below this an insertion is most of the word. {@code F@K} plus one
     * inserted sound reaches {@code F@RK} and {@code FL@K}, so a short term
     * would match ordinary vocabulary; it is matched exactly instead.
     */
    private static final int FUZZY_MIN_CODE = 5;

    /* ------------------------------------------------------------------ */
    /*  Encoding                                                          */
    /* ------------------------------------------------------------------ */

    /** Both readings: soft-G first, hard-G second. */
    public static String[] codes(String input) {
        return new String[]{ code(input, false), code(input, true) };
    }

    /**
     * Metaphone-style skeleton with vowel positions retained.
     *
     * <p>Input is folded first: non-letters removed, which is what collapses a
     * multi-word evasion into one token before any rule runs.
     */
    public static String code(String input) {
        return code(input, false);
    }

    /**
     * @param hardG treat G before E/I/Y as hard. English does this constantly
     *              ("gear", "get", "girl") and Metaphone's rule does not.
     */
    public static String code(String input, boolean hardG) {
        if (input == null || input.isEmpty()) return "";
        StringBuilder clean = new StringBuilder(input.length());
        String upper = input.toUpperCase(Locale.ROOT);
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (c >= 'A' && c <= 'Z') clean.append(c);
        }
        String s = clean.toString();
        if (s.isEmpty()) return "";

        // Silent initial clusters. This is the rule that makes "knee" sound
        // like "nee" and therefore lets "knee gear" collide with its target.
        if (s.length() >= 2) {
            String start = s.substring(0, 2);
            if (start.equals("KN") || start.equals("GN") || start.equals("PN")
                    || start.equals("AE") || start.equals("WR")) {
                s = s.substring(1);
            } else if (start.equals("WH")) {
                s = "W" + s.substring(2);
            } else if (s.charAt(0) == 'X') {
                s = "S" + s.substring(1);
            }
        }

        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            char prev = i > 0 ? s.charAt(i - 1) : 0;
            char next = i + 1 < n ? s.charAt(i + 1) : 0;
            char after = i + 2 < n ? s.charAt(i + 2) : 0;

            // Collapse doubles, except CC which can be two sounds.
            if (c == prev && c != 'C') continue;

            switch (c) {
                // One mark per vowel RUN, so "nigger" and "neegar" agree on
                // syllable count while disagreeing on every vowel in it.
                case 'A', 'E', 'I', 'O', 'U' -> {
                    if (out.length() == 0 || out.charAt(out.length() - 1) != VOWEL_MARK) {
                        out.append(VOWEL_MARK);
                    }
                }
                case 'B' -> { if (!(i == n - 1 && prev == 'M')) out.append('B'); }
                case 'C' -> {
                    if (next == 'I' && after == 'A') out.append('X');
                    else if (next == 'H') { out.append('X'); i++; }
                    else if (next == 'I' || next == 'E' || next == 'Y') out.append('S');
                    else out.append('K');
                }
                case 'D' -> {
                    if (next == 'G' && (after == 'E' || after == 'Y' || after == 'I')) {
                        out.append('J');
                        i++;
                    } else {
                        out.append('T');
                    }
                }
                case 'G' -> {
                    if (next == 'H') {
                        // Silent unless a vowel follows the H, so "dough" loses
                        // it and "ghost" keeps it.
                        if (after != 0 && VOWELS.indexOf(after) >= 0) out.append('K');
                        i++;
                    } else if (next == 'N') {
                        // Silent in "gnome", "sign".
                        i++;
                    } else if (!hardG && (next == 'I' || next == 'E' || next == 'Y')) {
                        out.append('J');
                    } else {
                        out.append('K');
                    }
                }
                case 'H' -> {
                    boolean afterVowel = prev != 0 && VOWELS.indexOf(prev) >= 0;
                    boolean beforeVowel = next != 0 && VOWELS.indexOf(next) >= 0;
                    if (!afterVowel || beforeVowel) out.append('H');
                }
                case 'K' -> { if (prev != 'C') out.append('K'); }
                case 'P' -> { if (next == 'H') { out.append('F'); i++; } else out.append('P'); }
                case 'Q' -> out.append('K');
                case 'S' -> {
                    if (next == 'H') { out.append('X'); i++; }
                    else if (next == 'I' && (after == 'O' || after == 'A')) out.append('X');
                    else out.append('S');
                }
                case 'T' -> {
                    if (next == 'I' && (after == 'O' || after == 'A')) out.append('X');
                    else if (next == 'H') { out.append('0'); i++; }
                    else out.append('T');
                }
                case 'V' -> out.append('F');
                case 'W', 'Y' -> { if (next != 0 && VOWELS.indexOf(next) >= 0) out.append(c); }
                case 'X' -> out.append("KS");
                case 'Z' -> out.append('S');
                default -> out.append(c);          // F J L M N R
            }
        }
        return out.toString();
    }

    /** The code with its vowel marks removed. */
    public static String consonants(String code) {
        if (code == null || code.isEmpty()) return "";
        StringBuilder b = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c != VOWEL_MARK) b.append(c);
        }
        return b.toString();
    }

    /**
     * Whether a term is safe to match phonetically at all.
     *
     * <p>A term with fewer than {@code minLength} consonant sounds cannot be
     * matched without hitting ordinary chat, because at that size the code is no
     * longer a fingerprint — {@code K} and {@code SP} appear in most sentences.
     * Such terms are dropped from the phonetic layer entirely and left to the
     * literal matcher, which has the spelling to work with and does not need to
     * guess.
     */
    public static boolean usable(String termCode, int minLength) {
        return termCode != null && consonants(termCode).length() >= Math.max(3, minLength);
    }

    /* ------------------------------------------------------------------ */
    /*  Matching                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Whether {@code haystack} contains a span that <em>sounds like</em>
     * {@code termCode}.
     *
     * @param termCode    the pre-encoded term, from {@link #code}
     * @param haystack    the message, unencoded and space separated
     * @param maxDistance sounds the span may carry that the term does not.
     *                    Applied only to codes of at least
     *                    {@value #FUZZY_MIN_CODE} characters
     * @param minLength   terms with fewer consonant sounds than this are not
     *                    matched at all. See {@link #usable}
     */
    public static boolean sounds(String termCode, String haystack,
                                 int maxDistance, int minLength) {
        if (termCode == null || termCode.isEmpty() || haystack == null) return false;
        if (!usable(termCode, minLength)) return false;

        String[] words = haystack.trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) return false;

        int budget = budget(termCode, maxDistance);
        String termCons = consonants(termCode);

        // ONE WORD AT A TIME.
        //
        // Multi-word spans were tried and measured, and they cost far more than
        // they earn. The textbook evasion "knee gear" needs them - but "no gear",
        // "neth gear" and "need gear" produce the identical code, and on a
        // Minecraft server those are said constantly while "knee gear" is said
        // never. The same held everywhere it was checked: "tp for good" scored as
        // a slur, "baby zombie" as another, "PAY 1M GETS" as a third. Two-word
        // spans multiply the chances of an accidental collision by the number of
        // adjacent pairs in the message, against an evasion nobody uses.
        //
        // Spacing evasion is not lost with them: "nig ger" and "ni gg er" are
        // caught by the literal matcher, because Scripts.normalise strips the
        // separators before the term patterns run.
        for (String word : words) {
            if (matches(termCode, termCons, word, budget)) return true;
        }

        // Letter-spaced evasion ("n i g g e r") is the one case that genuinely
        // needs rejoining, so it is detected rather than assumed: the message has
        // to actually look letter-spaced. Without that gate the rejoined form of
        // "no gear" is "nogear", and this check would undo the one above.
        if (looksLetterSpaced(words)) {
            StringBuilder all = new StringBuilder();
            for (String w : words) all.append(w);
            return matches(termCode, termCons, all.toString(), budget);
        }
        return false;
    }

    private static int budget(String termCode, int maxDistance) {
        return termCode.length() >= FUZZY_MIN_CODE ? Math.max(0, maxDistance) : 0;
    }

    private static boolean looksLetterSpaced(String[] words) {
        if (words.length < 4) return false;
        int singles = 0;
        for (String w : words) if (w.length() == 1) singles++;
        return singles >= words.length * SPACED_RATIO;
    }

    /**
     * Whether a term's pronunciation is indistinguishable from an ordinary word.
     *
     * <p>This is the check that removes {@code kraut}, {@code chinks},
     * {@code fags}, {@code coons} and {@code spic} from the phonetic layer
     * without anyone having to notice them by hand: they encode exactly as
     * {@code great}, {@code chunks}, {@code fix}, {@code goons} and
     * {@code speak}. Matching those by sound does not catch evasion, it catches
     * conversation — 171 times in 8,270 real messages before this existed.
     *
     * <p>It runs at load, against the owner's own terms as well as the shipped
     * ones, so a term added next year is vetted on the same evidence.
     *
     * @return the colliding word, or {@code null} if the term is safe
     */
    public static String collidesWith(String termCode, Iterable<String> commonWords,
                                      int maxDistance) {
        if (termCode == null || termCode.isEmpty() || commonWords == null) return null;
        int budget = budget(termCode, maxDistance);
        String termCons = consonants(termCode);
        for (String word : commonWords) {
            if (word == null || word.isBlank()) continue;
            String w = word.trim();
            // English inflects, and a word list does not. "take" is in every
            // word list and "takes" is in none of them, but "takes" is what
            // people type - and it is "takes" that collides with a slur, not
            // "take". Vetting the base form only would have let that through.
            if (matches(termCode, termCons, w, budget)) return w;
            String plural = w + (sibilant(w) ? "es" : "s");
            if (matches(termCode, termCons, plural, budget)) return plural;
        }
        return null;
    }

    /**
     * The plural of a word, so a word list covers its own plurals.
     *
     * <p>One plural, deliberately. A wider set was tried — {@code -ed},
     * {@code -ing}, {@code -er} — and it vetted out the terms that matter most,
     * because it invents words nobody types: {@code nick} plus {@code -er} is
     * {@code nicker}, which is not English but does encode exactly as a racial
     * slur, so the slur was discarded to protect a word that does not exist.
     * Plurals are safe because they are always real: if {@code take} is a word,
     * so is {@code takes}.
     */
    private static boolean sibilant(String w) {
        return w.endsWith("s") || w.endsWith("x") || w.endsWith("z")
                || w.endsWith("ch") || w.endsWith("sh");
    }

    /**
     * Whether a term should be encoded phonetically at all.
     *
     * <p>Leet spellings belong to the literal matcher, which has explicit
     * character classes for them. Encoding them as sound produces nonsense,
     * because the digits are simply deleted: {@code f4ggot} came out as
     * {@code FK@T}, which is not how anyone pronounces it, and which collided
     * with the typo {@code fgod}.
     */
    public static boolean encodable(String term) {
        if (term == null || term.isBlank()) return false;
        for (int i = 0; i < term.length(); i++) {
            if (Character.isDigit(term.charAt(i))) return false;
        }
        return true;
    }

    /** One span, both G readings, and the disemvowelled fallback. */
    private static boolean matches(String termCode, String termCons,
                                   String span, int budget) {
        boolean vowelless = hasNoVowels(span);
        for (String spanCode : codes(span)) {
            if (spanCode.isEmpty()) continue;
            if (within(termCode, spanCode, budget)) return true;
            // "fgt" carries no vowels to compare, so the marks are dropped from
            // both sides. Confined to spans that are actually disemvowelled,
            // because without that guard it is the old broken behaviour again.
            if (vowelless && within(termCons, consonants(spanCode), budget)) return true;
        }
        return false;
    }

    /**
     * Y counts as a vowel here even though the encoder does not treat it as one.
     *
     * <p>This is the difference between {@code fgt}, which is genuinely
     * disemvowelled, and {@code skwlly}, which is a name with a vowel spelled
     * {@code y}. Without it {@code skwlly} took the weaker consonant-only path
     * and came out as a match.
     */
    private static boolean hasNoVowels(String span) {
        for (int i = 0; i < span.length(); i++) {
            char c = Character.toUpperCase(span.charAt(i));
            if (VOWELS.indexOf(c) >= 0 || c == 'Y') return false;
        }
        return true;
    }

    /**
     * True when {@code span} is {@code term} with at most {@code budget} extra
     * sounds inserted — no substitutions, no deletions.
     *
     * <p>That restriction is the whole point. Levenshtein at one edit lets
     * {@code organ} match {@code zorgan} by swapping a letter, which is a
     * coincidence rather than an evasion. Requiring the term to survive intact
     * as a subsequence keeps {@code nick gear} and drops the coincidences.
     */
    private static boolean within(String term, String span, int budget) {
        if (term.isEmpty() || span.isEmpty()) return false;
        int extra = span.length() - term.length();
        if (extra < 0 || extra > budget) return false;
        if (extra == 0) return term.equals(span);
        int i = 0;
        int skipped = 0;
        for (int j = 0; j < span.length() && i < term.length(); j++) {
            if (term.charAt(i) == span.charAt(j)) i++;
            else if (++skipped > budget) return false;
        }
        return i == term.length();
    }

    /**
     * Whether two strings sound alike outright. Used by the self-test and by
     * {@code /textguard sounds}.
     */
    public static boolean alike(String a, String b, int maxDistance) {
        for (String ca : codes(a)) {
            if (ca.isEmpty()) continue;
            int budget = ca.length() >= FUZZY_MIN_CODE ? Math.max(0, maxDistance) : 0;
            for (String cb : codes(b)) {
                if (cb.isEmpty()) continue;
                if (ca.equals(cb)) return true;
                if (within(ca, cb, budget) || within(cb, ca, budget)) return true;
            }
        }
        return false;
    }
}
