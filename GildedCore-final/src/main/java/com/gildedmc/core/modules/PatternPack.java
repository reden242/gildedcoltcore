package com.gildedmc.core.modules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic detectors for the categories where a pattern beats a model.
 *
 * <h2>Why not learn all of it</h2>
 * A classifier is the right tool for open-ended vocabulary — insults and
 * harassment, where the words change constantly and the phrasing is endless. It
 * is the wrong tool for things with fixed <em>structure</em>. A phone number, an
 * email address and a server domain are shapes, not vocabulary: a regex catches
 * every one that has ever been written, including ones nobody has seen before,
 * while a model only catches the ones resembling its training data. Learning
 * those would swap perfect recall for approximately 25%.
 *
 * <p>So the two are layered. Structure is matched here, first and exactly.
 * Advertising intent goes to {@link LocalAiModule}.
 *
 * <h2>Bypass tolerance</h2>
 * Phrase detectors compile through {@link ChatGuardModule#compileTerm}, so
 * {@code kys}, {@code k y s} and {@code ky5} are one entry, and they are tested
 * against the same normalised skeletons the term list uses.
 *
 * <h2>Minecraft is not the real world</h2>
 * The one thing that would ruin the doxxing detector is coordinates: "my base is
 * at 250 64 -1300" is ordinary chat and looks numeric. Real-world identifiers
 * are matched by their own shapes — an email is an email, a phone number has a
 * country or area code — and never by "there were digits".
 */
public final class PatternPack {

    private PatternPack() { }

    /* ------------------------------------------------------------------ */
    /*  Categories                                                        */
    /* ------------------------------------------------------------------ */

    public static final String CAT_DEATH_THREAT = "death-threat";
    public static final String CAT_DOXXING = "doxxing";
    public static final String CAT_SPAM_INCITE = "spam-incite";

    /** A match: which category, and the text that triggered it. */
    public record Hit(String category, String evidence) { }

    /* ------------------------------------------------------------------ */
    /*  Death threats and self-harm baiting                               */
    /* ------------------------------------------------------------------ */

    /**
     * Phrases that are a threat or self-harm bait in essentially every context.
     *
     * <p>Kept deliberately narrow. "I'll kill you" inside a PvP server is
     * genuinely ambiguous, so plain {@code kill you} is NOT here — only
     * constructions that carry outside the game, plus the self-harm ones, which
     * are never acceptable regardless of context.
     */
    private static final String[] DEATH_PHRASES = {
        "kys", "killyourself", "killurself", "killyoursel",
        "gokillyourself", "godie", "hopeyoudie", "hopeudie",
        "youshouldkillyourself", "endyourlife", "endurlife",
        "iwillkillyouinreallife", "illkillyouirl", "killyouirl",
        "iknowwhereyoulive", "iwillfindyou", "illfindyouinreallife",
        "youdeservetodie", "hangyourself", "neckyourself", "unaliveyourself"
    };

    /* ------------------------------------------------------------------ */
    /*  Telling others to spam                                            */
    /* ------------------------------------------------------------------ */

    private static final String[] SPAM_INCITE_PHRASES = {
        "everyonespam", "allspam", "spamthisinchat", "spamchat", "spamitinchat",
        "letsspam", "letsallspam", "floodthechat", "floodchat", "spamthis",
        "everyonetype", "everyonesay", "spamuntilstaff", "spamtheserver",
        "crashthechat", "spamtocrash"
    };

    private static volatile List<Pattern> deathPatterns;
    private static volatile List<Pattern> spamIncitePatterns;

    /** Extra phrases from config, in any language. */
    private static final List<String> extraDeath = new ArrayList<>();
    private static final List<String> extraSpamIncite = new ArrayList<>();

    /**
     * Adds phrases for other languages.
     *
     * <p>The built-in lists are English, because that is what can be shipped
     * responsibly and verified. Every one of the thirty languages needs its own
     * phrasing for "kill yourself" and "everyone spam", and only the server
     * owner knows which ones their community actually uses. Entries go through
     * the same bypass-tolerant compiler and the same script-aware normalisation
     * as everything else, so a Hindi or Arabic phrase works exactly as well as
     * an English one.
     */
    public static void configure(List<String> death, List<String> spamIncite) {
        extraDeath.clear();
        extraSpamIncite.clear();
        if (death != null) for (String d : death) if (d != null && !d.isBlank()) extraDeath.add(d.trim());
        if (spamIncite != null) {
            for (String d : spamIncite) if (d != null && !d.isBlank()) extraSpamIncite.add(d.trim());
        }
        deathPatterns = null;
        spamIncitePatterns = null;
    }

    private static List<Pattern> compile(String[] phrases, List<String> extra) {
        List<Pattern> out = new ArrayList<>(phrases.length + extra.size());
        for (String p : phrases) {
            try { out.add(ChatGuardModule.compileTerm(p)); }
            catch (Exception ignored) { }
        }
        for (String p : extra) {
            try { out.add(ChatGuardModule.compileTerm(p)); }
            catch (Exception ignored) { }
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /*  Real-world identifiers                                            */
    /* ------------------------------------------------------------------ */

    /** name@host.tld — an email is unambiguous and never a game coordinate. */
    private static final Pattern EMAIL = Pattern.compile(
            "\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Phone numbers, requiring a country code or a bracketed area code.
     *
     * A bare run of nine digits is not matched: that is as likely to be a
     * seed, a coordinate pair or a purchase amount.
     */
    private static final Pattern PHONE = Pattern.compile(
            "(\\+\\d{1,3}[\\s.-]?\\d{2,4}[\\s.-]?\\d{3,4}[\\s.-]?\\d{3,4}"
            + "|\\(\\d{3}\\)[\\s.-]?\\d{3}[\\s.-]?\\d{4})");

    /** "12 Oak Street", "45b Church Road" — a number followed by a street word. */
    private static final Pattern STREET = Pattern.compile(
            "\\b\\d{1,5}[a-z]?\\s+[a-z][a-z'-]{2,}\\s+"
            + "(street|st|road|rd|avenue|ave|lane|ln|drive|dr|close|court|ct|"
            + "boulevard|blvd|way|place|pl|terrace|crescent)\\b",
            Pattern.CASE_INSENSITIVE);

    /** UK-style and US-style postcodes. */
    private static final Pattern POSTCODE = Pattern.compile(
            "\\b([a-z]{1,2}\\d[a-z\\d]?\\s*\\d[a-z]{2}|\\d{5}(-\\d{4})?)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Phrases that turn otherwise innocuous data into doxxing.
     *
     * A postcode on its own is weak evidence; "his postcode is" plus a postcode
     * is not. These are what make the difference.
     */
    private static final Pattern DOX_CONTEXT = Pattern.compile(
            "\\b(his|her|their|your|ur)?\\s*(real\\s*name|full\\s*name|home\\s*address|"
            + "address|postcode|zip\\s*code|phone\\s*number|mobile\\s*number|"
            + "school|workplace|lives?\\s+(at|in|on)|works?\\s+at|"
            + "social\\s*security|passport|credit\\s*card)\\b",
            Pattern.CASE_INSENSITIVE);

    /** "I found his X", "leaking his X", "doxxing" — the act, stated. */
    private static final Pattern DOX_INTENT = Pattern.compile(
            "\\b(dox+ed|dox+ing|dox+|leak(ing|ed)?\\s+(his|her|their|your)|"
            + "here\\s+is\\s+(his|her|their)\\s+(address|number|name|email)|"
            + "i\\s+found\\s+(his|her|their)\\s+(address|number|name|email|school))\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * The data belongs to someone else — the thing that makes it a leak.
     *
     * <p>Also covers the threat form ("if you dont stop i will post"), because
     * the possessive alone is not always present in it.
     */
    private static final Pattern THIRD_PARTY = Pattern.compile(
            "\\b(his|her|hers|their|theirs|your|yours|ur|"
            + "this\\s+(guy|kid|person|player)|that\\s+(guy|kid|person|player))\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * The speaker is talking about themselves, which is never doxxing.
     *
     * <p>Checked <em>after</em> the third-party test and allowed to veto it, so
     * "my email is x, dont post his" stays flagged while "bob@gmail.com is my
     * email" does not.
     */
    private static final Pattern FIRST_PERSON = Pattern.compile(
            "\\b(my|mine|im|i\\s*am|me|our|us|contact\\s+me|reach\\s+me|"
            + "email\\s+me|dm\\s+me|message\\s+me)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Minecraft coordinates, so they are never mistaken for personal data. */
    private static final Pattern COORDINATES = Pattern.compile(
            "-?\\d{1,7}\\s*[, ]\\s*-?\\d{1,3}\\s*[, ]\\s*-?\\d{1,7}");

    /* ------------------------------------------------------------------ */
    /*  Matching                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * The first category this text trips, or null.
     *
     * <p>Ordered by how serious and how certain each detector is: a death
     * threat outranks doxxing, which outranks incitement to spam.
     */
    public static Hit match(String raw) {
        if (raw == null || raw.isBlank()) return null;

        if (deathPatterns == null) deathPatterns = compile(DEATH_PHRASES, extraDeath);
        if (spamIncitePatterns == null) {
            spamIncitePatterns = compile(SPAM_INCITE_PHRASES, extraSpamIncite);
        }

        List<String> variants = ChatGuardModule.variants(raw);
        // Also test the leet-folded skeleton, so "ky5" and "k1ll" are covered by
        // the same entries rather than needing their own.
        List<String> forms = new ArrayList<>(variants);
        for (String v : variants) {
            String folded = TextFeatures.unleet(v);
            if (!folded.equals(v)) forms.add(folded);
        }

        for (Pattern p : deathPatterns) {
            for (String form : forms) {
                Matcher m = p.matcher(form);
                if (m.find()) return new Hit(CAT_DEATH_THREAT, m.group());
            }
        }

        Hit dox = doxxing(raw);
        if (dox != null) return dox;

        for (Pattern p : spamIncitePatterns) {
            for (String form : forms) {
                Matcher m = p.matcher(form);
                if (m.find()) return new Hit(CAT_SPAM_INCITE, m.group());
            }
        }
        return null;
    }

    /**
     * Personal information about a real person.
     *
     * <p>Email and phone stand alone — there is no innocent reason to post
     * either in Minecraft chat. Weaker shapes (a street, a postcode) need a
     * context phrase alongside them, because "45 Church Road" could be a build
     * name and a five digit number could be anything.
     */
    public static Hit doxxing(String rawInput) {
        // Arabic-Indic, Persian, Devanagari and Thai digits are folded to ASCII
        // first: a phone number written in them is still a phone number.
        String raw = Scripts.foldDigits(rawInput);

        boolean context = DOX_CONTEXT.matcher(raw).find();
        if (DOX_INTENT.matcher(raw).find()) {
            return new Hit(CAT_DOXXING, "stated intent to share personal information");
        }

        // An identifier only becomes a doxx when it belongs to SOMEONE ELSE.
        // Measured: gating on the identifier alone flagged "bob@gmail.com is my
        // email", "email me at someone@outlook.com about the build" and
        // "contact the server owner at admin@example.org" - a player sharing
        // their own contact details, punished on the doxxing ladder, which is
        // an IP ban. Street and postcode were already gated this way; email and
        // phone were not, and that inconsistency was the whole bug.
        boolean thirdParty = THIRD_PARTY.matcher(raw).find();
        boolean firstPerson = FIRST_PERSON.matcher(raw).find();

        Matcher email = EMAIL.matcher(raw);
        if (email.find() && (context || thirdParty) && !firstPerson) {
            return new Hit(CAT_DOXXING, email.group());
        }

        Matcher phone = PHONE.matcher(raw);
        if (phone.find() && !isCoordinates(raw, phone.group())
                && (context || thirdParty) && !firstPerson) {
            return new Hit(CAT_DOXXING, phone.group());
        }

        if (!context) return null;

        Matcher street = STREET.matcher(raw);
        if (street.find()) return new Hit(CAT_DOXXING, street.group());

        Matcher postcode = POSTCODE.matcher(raw);
        if (postcode.find() && !isCoordinates(raw, postcode.group())) {
            return new Hit(CAT_DOXXING, postcode.group());
        }
        // A context phrase with no identifier attached is not yet doxxing;
        // "what school do you go to" is nosy, not a leak.
        return null;
    }

    /** True when the match is part of a coordinate triple. */
    private static boolean isCoordinates(String raw, String candidate) {
        Matcher coords = COORDINATES.matcher(raw);
        while (coords.find()) {
            if (coords.group().contains(candidate)) return true;
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /*  Reporting                                                         */
    /* ------------------------------------------------------------------ */

    /** Categories this pack can produce, for config validation. */
    public static List<String> categories() {
        return List.of(CAT_DEATH_THREAT, CAT_DOXXING, CAT_SPAM_INCITE);
    }

    /** How many phrases back each category, for the status report. */
    public static Map<String, Integer> sizes() {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put(CAT_DEATH_THREAT, DEATH_PHRASES.length);
        out.put(CAT_SPAM_INCITE, SPAM_INCITE_PHRASES.length);
        out.put(CAT_DOXXING, 6);          // email, phone, street, postcode, context, intent
        return out;
    }

    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
