package com.coltcore.core.modules;

import com.coltcore.core.SchedulerCompat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ChatGuardModule — the local four-layer chat filter.
 *
 * <h2>What it covers</h2>
 * Every surface a player can put words on, not just chat: chat messages,
 * signs, written books (title and pages) and anvil item renames. All four run
 * through the same screen and the same escalation ladder, so writing a slur on
 * a sign costs exactly what saying it in chat does.
 *
 * <h2>Order of work</h2>
 * <ol>
 *   <li>Mute enforcement. A muted player's text never reaches step 2.</li>
 *   <li>Spam limits (chat only).</li>
 *   <li>Advertising: normalized address patterns, the local supervised
 *       classifier, then local context aggregation.</li>
 *   <li>Hate speech / slurs: the term list compiled into bypass-tolerant
 *       patterns, tested against several normalised skeletons of the text.</li>
 *   <li>The advertising model runs only after an address-pattern hit.</li>
 * </ol>
 *
 * <h2>Escalation</h2>
 * Handled by {@link MuteStore}. The shipped hate-speech ladder is
 * warn → 1d → 3d → 1w → permanent IP mute; advertising is a one-rung ladder,
 * which is what makes it immediate. Mutes are enforced by this plugin rather
 * than delegated to a punishment plugin that may not exist — see MuteStore for
 * why. A command template can be configured to run alongside.
 */
public final class ChatGuardModule implements Listener {

    public static final String PERM_BYPASS = "coltcore.chatguard.bypass";
    public static final String PERM_ALERTS = "coltcore.chatguard.alerts";
    public static final String PERM_ADMIN  = "coltcore.chatguard.admin";

    /** Categories the ladder is keyed on. */
    public static final String CAT_HATE = "hate";
    public static final String CAT_ADVERT = "advertising";
    /**
     * Advertising placed on a sign.
     *
     * <p>Signs are permanent and visible to everyone who walks past, so an
     * address on a sign is the one form of advertising that gets the instant
     * permanent IP ban rather than the escalating ladder — it is never
     * accidental, and unlike chat it cannot be deleted after the fact.
     */
    public static final String CAT_SIGN_ADVERT = "sign-advertising";
    /**
     * Naming another server without handing over an address.
     *
     * <p>Its own ladder because it is its own offence. Heavy advertising is a
     * permanent mute on the first hit; "lets go play 2b2t" is not that, but it
     * is also not nothing - it is the same intent with the address left out.
     */
    public static final String CAT_LIGHT_ADVERT = "light-advertising";
    /**
     * Ordinary swearing, kept apart from hate speech.
     *
     * <p>The built-in lexicon is a PROFANITY list - "fuck" and a slur are both
     * in it, and they are not the same offence. Routing every term hit to the
     * hate ladder would mean a one-day mute for saying "fuck", which is absurd
     * on a server where PvP trash talk is the normal register. Lexicon hits land
     * here and get the toxicity ladder; the curated slur list and the owner's own
     * terms land on {@link #CAT_HATE}.
     */
    public static final String CAT_PROFANITY = "profanity";

    private final JavaPlugin plugin;
    private final MuteStore mutes;
    private final PunishmentBridge punish;
    private final LocalAiModule local;
    private final LocalContextAggregator advertContext = new LocalContextAggregator();
    private ReviewModule review;
    /** Layer 2/3 of the anti-ad pipeline; null until the main class wires it. */
    private AntiAdPipeline antiAd;
    /** 5-layer context-aware anti-ad system; null until wired. */
    private ContextAwareAntiAd contextAwareAntiAd;

    private boolean enabled;
    private int repeatFastPath = 2;
    private boolean dryRun;

    // spam
    private int spamMaxPerWindow = 5;
    private long spamWindowMs = 7_000L;
    private int dupeMemory = 3;
    private int minLenForDupe = 6;
    private double similarThreshold = 0.85D;
    private int maxCapsPercent = 70;
    private int minLenForCaps = 12;

    // advertising
    private boolean advertEnabled = true;
    private boolean advertIgnoreVersionLike = true;
    private final List<String> advertTlds = new ArrayList<>();
    private final List<String> advertAllow = new ArrayList<>();

    // mute enforcement
    private boolean muteBlocksTextSources = true;
    private final List<String> muteBlockedCommands = new ArrayList<>();
    /** Commands whose text is screened the same way chat is. */
    private final List<String> messageCommands = new ArrayList<>();
    private String mutedMessage =
            "&cYou are muted. &7Remaining: &f%remaining%&7. Reason: &f%reason%";

    /** Optional console templates run alongside the built-in mute. */
    private final Map<String, String> ladderCommands = new LinkedHashMap<>();

    private final List<Pattern> terms = new ArrayList<>();
    private final List<String> termSources = new ArrayList<>();
    /** Category each term reports as: profanity for the lexicon, hate for slurs. */
    private final List<String> termCategories = new ArrayList<>();
    /** Which terms are short enough to need whole-word matching. */
    private final List<Boolean> termShort = new ArrayList<>();
    /**
     * Terms that survived phonetic vetting, and their codes.
     *
     * <p>Parallel lists, and NOT parallel to {@link #terms}. They used to be,
     * and the index was reused across both, so once any term failed to encode
     * the two lists drifted and every phonetic hit was reported as the wrong
     * word. That is why {@code wsp} was reported as {@code montare} and
     * {@code fork} as {@code hijoputa} — the detection was junk to begin with,
     * but the name attached to it was junk for a second, independent reason.
     */
    private final List<String> phoneticTerms = new ArrayList<>();
    private final List<String> phoneticCodes = new ArrayList<>();
    private boolean phoneticEnabled = true;
    private int phoneticDistance = 0;
    private int phoneticMinLength = 3;
    private final List<String> phoneticAllow = new ArrayList<>();
    /** Ordinary English, used to vet terms out of the phonetic layer. */
    private final List<String> commonWords = new ArrayList<>();
    /** The same words as a set, for the per-message address checks. */
    private final java.util.Set<String> commonWordSet = new java.util.HashSet<>();
    /** Require the evidence for a model verdict to be in the message. */
    private boolean contextAware = true;
    private boolean contextVerbose;
    private final Map<UUID, Deque<Long>> recent = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<String>> lastMessages = new ConcurrentHashMap<>();

    public ChatGuardModule(JavaPlugin plugin) {
        this.plugin = plugin;
        this.mutes = new MuteStore(plugin);
        this.punish = new PunishmentBridge(plugin, this.mutes);
        this.local = new LocalAiModule(plugin);
    }

    /* ------------------------------------------------------------------ */
    /*  Normalisation                                                     */
    /* ------------------------------------------------------------------ */

    private static final Map<Character, Character> CYRILLIC = new HashMap<>();
    private static final Map<Character, Character> FLIPPED = new HashMap<>();
    static {
        String[] cyr = {"а a", "о o", "е e", "р p", "с c", "х x", "у y", "к k",
                        "м m", "н h", "т t", "в b", "і i", "ѕ s", "ј j"};
        String[] greek = {"\u03B1 a", "\u03BF o", "\u03B5 e", "\u03C1 p", "\u03C5 y",
                          "\u03BA k", "\u03BC m", "\u03BD n", "\u03C4 t", "\u03B9 i",
                          "\u03F2 c"};
        for (String s : cyr) CYRILLIC.put(s.charAt(0), s.charAt(2));
        for (String s : greek) CYRILLIC.put(s.charAt(0), s.charAt(2));
        String[] flip = {"ɐ a", "ɔ c", "ǝ e", "ɟ f", "ƃ g", "ɥ h", "ᴉ i", "ɾ j",
                         "ʞ k", "ן l", "ɯ m", "ɹ r", "ʇ t", "ʌ v", "ʍ w", "ʎ y"};
        for (String s : flip) FLIPPED.put(s.charAt(0), s.charAt(2));
    }

    private static final Pattern SEPARATORS = Pattern.compile("[\\s._\\-*+~`'\"|/\\\\,:;()\\[\\]{}]+");
    private static final Pattern STRETCH = Pattern.compile("(.)\\1{2,}");

    /**
     * Fold everything we can into a comparable lowercase skeleton.
     *
     * <p>Delegates to {@link Scripts}, which applies the lookalike tables only
     * to dominantly Latin text. Applying them to genuine Russian turned
     * "ты идиот" into "t...ot"; applying accent stripping to Hindi deleted the
     * vowel signs. Both are language destruction rather than evasion defeat.
     */
    static String normalise(String input) {
        return Scripts.normalise(input);
    }

    /**
     * The forward skeletons a message should be tested against.
     *
     * <h2>Why the reversed skeleton is no longer in here</h2>
     * It used to be, and it was matching ordinary chat. Reversing the message
     * doubles the number of strings every term is tested against, and a term
     * that happens to read as English backwards then fires on the plain
     * sentence: {@code regger} inside "gear" reversed, and worse,
     * {@code and all bone are gone} reversed contains {@code enogeranebolladna}
     * which the filler-tolerant {@code boner} pattern matched. Measured over
     * 11,677 lines of real chat, the reversed skeleton alone accounted for 14 of
     * the 184 flags and every one of them was innocent.
     *
     * <p>Reversal still catches the evasion it was written for — someone typing
     * {@code reggin} — but it is now a separate, gated pass. See
     * {@link #reversedVariant} and {@link #REVERSE_MIN_LENGTH}.
     */
    static List<String> variants(String input) {
        String n = normalise(input);
        List<String> out = new ArrayList<>(2);
        if (!n.isEmpty()) {
            out.add(n);
            String squeezed = STRETCH.matcher(n).replaceAll("$1");
            if (!squeezed.equals(n)) out.add(squeezed);
        }
        return out;
    }

    /**
     * The reversed skeleton, or null when reversing changes nothing.
     *
     * <p>Tested only after every forward skeleton has come up clean, and only
     * against terms of {@link #REVERSE_MIN_LENGTH} characters or more.
     */
    static String reversedVariant(String input) {
        String n = normalise(input);
        if (n.isEmpty()) return null;
        String rev = new StringBuilder(n).reverse().toString();
        return rev.equals(n) ? null : rev;
    }

    /**
     * Shortest term allowed to match in the reversed skeleton.
     *
     * <p>Six, because that is the length at which a reversed English sentence
     * stops containing the term by accident. Below it the reverse pass was
     * pure noise: {@code ass}, {@code cum}, {@code fag} and {@code tit} all
     * appear inside reversed ordinary words, and {@code niga} appeared inside
     * "again" reversed. Somebody evading the filter by typing backwards is
     * typing a whole slur backwards, and every slur worth evading is longer
     * than six characters.
     */
    private static final int REVERSE_MIN_LENGTH = 6;

    /** Whether term {@code i} is long enough to be trusted in reversed text. */
    private boolean reversible(int i) {
        if (i >= this.termSources.size()) return false;
        return Scripts.normalise(this.termSources.get(i)).length() >= REVERSE_MIN_LENGTH;
    }

    /**
     * Terms at or below this many letters are matched only as whole words.
     *
     * <p>Normalisation strips separators so that {@code n i g g e r} collapses
     * into one token, and that is what makes spacing evasion catchable. The cost
     * is that every word boundary in the message disappears too, and a
     * three-letter term then matches inside ordinary words. Measured over real
     * chat: {@code abo} fired on "about" twenty times, {@code yid} on "honestly
     * idk" nine times, {@code wop} on "two point two mill", and {@code kkk} on a
     * player called PACKKKILLER.
     *
     * <p>Long terms do not have this problem — nothing innocent contains six
     * consecutive letters of a slur — so the boundary requirement is confined to
     * the short ones, where it costs nothing real.
     */
    private static final int SHORT_TERM = 4;

    /**
     * One character allowed between the letters of a term.
     *
     * <p>Junk consonants and digits only. See {@link #compileTerm} for the
     * measurements behind excluding vowels, and for why this is a single
     * character rather than a run: the run is built at the use site, possessive
     * and refusing anything the class after it can consume for itself.
     */
    private static final String FILLER = "[jzxwv0-9]";

    /**
     * Whether a short term appears as a word rather than as a fragment.
     *
     * <p>Two ways to qualify, because there are two legitimate shapes. Either it
     * stands alone in the spaced text — "you kkk member" — or the entire
     * separator-stripped message is the term and nothing else, which is what
     * {@code f a g} and {@code f.a.g} reduce to.
     */
    private boolean shortTermHit(Pattern rx, String raw, String stripped) {
        Matcher m = rx.matcher(stripped);
        if (m.matches()) return true;
        String spaced = Scripts.words(raw);
        if (spaced.isEmpty()) return false;
        for (String word : spaced.split("\\s+")) {
            if (rx.matcher(word).matches()) return true;
        }
        return false;
    }

    /**
     * Turns a plain term into a bypass-tolerant pattern. Each letter becomes a
     * class covering its common digit/symbol substitutes, may repeat, and
     * filler characters are permitted between letters to catch inserted junk
     * ({@code nijgger}).
     *
     * <h2>Why vowels are not filler</h2>
     * The filler class used to be {@code [e3a@4jzxwv]}, which allowed {@code e},
     * {@code a} and their leet forms between every pair of letters. That is what
     * made the pattern match across ordinary word boundaries once separators
     * were stripped:
     * <ul>
     *   <li>{@code boner} matched "bone are" (bonear) — "and all bone are gone"
     *       was punished as profanity.</li>
     *   <li>{@code nigga} matched "lagging" via the {@code a} filler, four
     *       separate times in one day's chat.</li>
     *   <li>{@code ngger} matched "nggear" in "dont bring gear".</li>
     * </ul>
     * Nobody evades a filter by inserting a vowel — the result reads as a
     * different word and defeats the point. The insertions people actually use
     * are junk consonants and digits, which is what the class carries now.
     * Measured over 11,677 real lines this removed 20 flags, all false, and
     * cost nothing on the evasion set.
     *
     * <h2>Why the filler is possessive, and guarded</h2>
     * The filler used to be a bare {@code [jzxwv0-9]*} between two unbounded
     * class runs, and the filler class overlaps the letter classes — {@code z}
     * is one of the ways to write {@code s}, and most digits are leet for some
     * letter — so a run of {@code z} after an {@code a} can be split between the
     * filler and each following {@code [s5$z]+} in every possible way. When the
     * tail then fails, the engine tries all of those splits: measured on the
     * main thread with the shipped lexicon, {@code asshole} followed by 160
     * {@code z} took 5.2 s and {@code assmunch} past 13 s, the cost grew like
     * n^4, and a 1,023-character book page — the size the book handler hands
     * over synchronously — did not return at all.
     *
     * <p>The filler is now possessive, so what it matches it keeps, and it
     * refuses any character the class after it can consume for itself. The guard
     * is what preserves matching: a substitute digit or {@code z} that the next
     * letter could have used is left for that letter, so {@code a55}, {@code
     * b00b} and {@code ni69er} still match and the filler only ever absorbs
     * characters no later letter can. Verified by diffing accept/reject between
     * the old and new patterns over the shipped lexicon and an evasion set.
     */
    static Pattern compileTerm(String term) {
        StringBuilder rx = new StringBuilder();
        // The term is normalised through exactly the same pipeline as the
        // message it will be tested against, or the two end up in different
        // spaces and never meet. Messages have their Japanese dakuten and Greek
        // accents decomposed away and their spaces removed; a term compiled
        // from the raw string still carries all three and matches nothing. This
        // is why the Japanese, Korean, Greek and Vietnamese term tests failed
        // while English passed - English is the one language normalisation
        // leaves untouched.
        String t = Scripts.normalise(term);
        if (t.isEmpty()) t = term.toLowerCase(Locale.ROOT);
        String prev = "";
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            String cls = switch (c) {
                case 'a' -> "[a4@]";
                case 'b' -> "[b8]";
                case 'e' -> "[e3]";
                case 'g' -> "[g6q9]";
                case 'i' -> "[i1l!|]";
                case 'l' -> "[l1i|]";
                case 'o' -> "[o0]";
                case 's' -> "[s5$z]";
                case 't' -> "[t7+]";
                case 'c' -> "[c(<]";
                case 'u' -> "[uv]";
                // The one sound-substitution the character classes cannot
                // express, because it changes the letter count rather than the
                // glyph. Measured: "phuck" was the single case that got past
                // both this matcher and the phonetic layer, which drops "fuck"
                // as too short to be a distinctive sound.
                case 'f' -> "(?:f|ph)";
                default  -> Pattern.quote(String.valueOf(c));
            };
            // Possessive, and never a character the letter on either side of it
            // can consume itself -- see the note on compileTerm's cost. The two
            // guards are what leave the set of matching strings unchanged: a
            // substitute digit or z ("a55" for ass) is left for the letter that
            // can carry it, and a real letter can no longer be re-read as filler
            // and split a run a second way. Possessive is what stops the filler
            // being re-split against the next class at all.
            if (i > 0) rx.append("(?:(?!").append(prev).append("|").append(cls)
                    .append(")").append(FILLER).append(")*+");
            rx.append(cls).append('+');
            prev = cls;
        }
        return Pattern.compile(rx.toString(), Pattern.CASE_INSENSITIVE);
    }

    /* ------------------------------------------------------------------ */
    /*  Lifecycle                                                         */
    /* ------------------------------------------------------------------ */

    public void enable() {
        readConfig();
        if (!this.mutes.ready() && !this.mutes.enable()) {
            this.plugin.getLogger().warning("[TextGuard] mute store failed to start. "
                    + "Detections will still alert, but nothing can be muted.");
        }
        applyLadders();
        this.punish.enable();
        this.local.enable();
    }

    public void reload() {
        readConfig();
        applyLadders();
        this.punish.reload();
        this.local.reload();
    }

    public void disable() {
        this.recent.clear();
        this.lastMessages.clear();
        this.advertContext.clear();
        this.mutes.disable();
        this.local.disable();
    }

    public MuteStore mutes() { return this.mutes; }

    public PunishmentBridge punishments() { return this.punish; }

    public LocalAiModule localAi() { return this.local; }

    /** Set by the plugin once the review queue exists. */
    public void setReview(ReviewModule review) { this.review = review; }

    /** Wires the anti-ad pipeline (layers 2 and 3) from the main class. */
    public void setAntiAd(AntiAdPipeline antiAd) { this.antiAd = antiAd; }

    /** Wires the 5-layer context-aware anti-ad system. */
    public void setContextAwareAntiAd(ContextAwareAntiAd ctx) { this.contextAwareAntiAd = ctx; }

    private void readConfig() {
        ConfigurationSection c = this.plugin.getConfig().getConfigurationSection("chat-guard");
        if (c == null) { this.enabled = false; return; }
        this.enabled = c.getBoolean("enabled", false);
        this.repeatFastPath = Math.max(1, c.getInt("repeat-fast-path", 2));
        this.dryRun = c.getBoolean("dry-run", false);
        this.mutedMessage = c.getString("muted-message", this.mutedMessage);
        this.muteBlocksTextSources = c.getBoolean("mute-blocks-text-sources", true);

        this.muteBlockedCommands.clear();
        for (String s : c.getStringList("mute-blocked-commands")) {
            if (s != null && !s.isBlank()) {
                this.muteBlockedCommands.add(s.toLowerCase(Locale.ROOT).trim().replaceFirst("^/", ""));
            }
        }

        this.messageCommands.clear();
        List<String> msgCmds = c.getStringList("screen-commands");
        if (msgCmds.isEmpty()) {
            msgCmds = List.of("msg", "tell", "w", "whisper", "pm", "m", "dm",
                              "message", "r", "reply", "mail", "emsg", "etell",
                              "ewhisper", "pc", "party", "helpop", "ac", "gc");
        }
        for (String cmd : msgCmds) {
            if (cmd != null && !cmd.isBlank()) {
                this.messageCommands.add(cmd.toLowerCase(Locale.ROOT).trim().replaceFirst("^/", ""));
            }
        }

        ConfigurationSection s = c.getConfigurationSection("spam");
        if (s != null) {
            this.spamMaxPerWindow = s.getInt("max-messages", 5);
            this.spamWindowMs = Math.max(1L, s.getLong("window-seconds", 7L)) * 1000L;
            this.dupeMemory = s.getInt("duplicate-memory", 3);
            this.minLenForDupe = s.getInt("min-length-for-duplicate", 6);
            this.similarThreshold = s.getDouble("similarity-threshold", 0.85D);
            this.maxCapsPercent = s.getInt("max-caps-percent", 70);
            this.minLenForCaps = s.getInt("min-length-for-caps", 12);
        }

        ConfigurationSection adv = c.getConfigurationSection("advertising");
        this.advertTlds.clear();
        this.advertAllow.clear();
        if (adv != null) {
            this.advertEnabled = adv.getBoolean("enabled", true);
            this.advertIgnoreVersionLike = adv.getBoolean("ignore-version-like", true);
            for (String t : adv.getStringList("tlds")) {
                if (t != null && !t.isBlank()) this.advertTlds.add(t.toLowerCase(Locale.ROOT).trim());
            }
            for (String a : adv.getStringList("allow")) {
                if (a != null && !a.isBlank()) this.advertAllow.add(a.toLowerCase(Locale.ROOT).trim());
            }
            this.advertAllow.add("minepvp");
            this.advertAllow.add("minepvp.net");
            this.advertAllow.add("minepvp.com");
            // The owner's own networks. Suffix matching in allowed() means
            // play./discord./any subdomain of these pass too.
            this.advertAllow.add("coltmc.bond");
            this.advertAllow.add("coltmc.dpdns.org");
        }

        this.ladderCommands.clear();
        ConfigurationSection lc = c.getConfigurationSection("ladder-commands");
        if (lc != null) for (String k : lc.getKeys(false)) {
            this.ladderCommands.put(k.toUpperCase(Locale.ROOT), lc.getString(k, ""));
        }

        // Extra structural phrases for other languages.
        PatternPack.configure(
                this.plugin.getConfig().getStringList("languages.death-threat-phrases"),
                this.plugin.getConfig().getStringList("languages.spam-incite-phrases"));

        ConfigurationSection ph = c.getConfigurationSection("phonetic");
        this.phoneticAllow.clear();
        if (ph != null) {
            this.phoneticEnabled = ph.getBoolean("enabled", true);
            this.phoneticDistance = Math.max(0, ph.getInt("max-inserted-sounds", 0));
            this.phoneticMinLength = Math.max(3, ph.getInt("min-code-length", 3));
            for (String w : ph.getStringList("allow")) {
                if (w != null && !w.isBlank()) this.phoneticAllow.add(w.toLowerCase(Locale.ROOT).trim());
            }
        }
        loadCommonWords();

        ConfigurationSection ctx = c.getConfigurationSection("context");
        if (ctx != null) {
            this.contextAware = ctx.getBoolean("enabled", true);
            this.contextVerbose = ctx.getBoolean("log-drops", false);
        }

        // Innocent words that sit inside a term. Defaults rather than an empty
        // list, because the ones that matter are not guessable by an owner who
        // has not yet been bitten: "nigeria" contains a term and "niger" sounds
        // identical to one.
        List<String> exemptDefaults = List.of("niger", "nigeria", "nigerian",
                "nigerien", "nigerians", "niggardly", "niggling", "snigger",
                "sniggering", "cockpit", "cocktail", "scunthorpe", "penistone",
                "analysis", "analyse", "analyze", "assassin", "assume",
                "assumption", "classic", "grape", "shiitake", "therapist");
        List<String> exemptConfigured = c.getStringList("exempt-words");
        compileExempt(exemptConfigured.isEmpty() ? exemptDefaults : exemptConfigured);

        this.terms.clear();
        this.termShort.clear();
        this.termSources.clear();
        this.phoneticCodes.clear();
        this.phoneticTerms.clear();
        this.termCategories.clear();

        // Two shipped lists, then the owner's own terms on top. All three go
        // through the same bypass-tolerant compilation.
        //
        // The lists used to be one, carrying 24 languages, and that was the root
        // of the false positives: a Czech or Filipino word run through an
        // English phonetic encoder produces debris rather than a pronunciation,
        // and the debris was one or two characters long, so it matched most
        // sentences. See Lexicon for the measurements.
        List<String> allTerms = new ArrayList<>();
        List<String> allCategories = new ArrayList<>();
        if (c.getBoolean("use-builtin-lexicon", true)) {
            for (String t : Lexicon.profanity()) {
                allTerms.add(t);
                allCategories.add(CAT_PROFANITY);
            }
            for (String t : Lexicon.hate()) {
                allTerms.add(t);
                allCategories.add(CAT_HATE);
            }
        }
        // The owner's own terms are treated as hate. Someone adding to this list
        // by hand is adding the words that matter on their server, not general
        // swearing, and those deserve the harsher ladder.
        for (String t : c.getStringList("terms")) {
            allTerms.add(t);
            allCategories.add(CAT_HATE);
        }

        int tooShort = 0;
        int vetoed = 0;
        List<String> vetoDetail = new ArrayList<>();
        for (int ti = 0; ti < allTerms.size(); ti++) {
            String t = allTerms.get(ti);
            if (t == null || t.isBlank()) continue;
            String term = t.trim();
            String category = allCategories.get(ti);
            try {
                this.terms.add(compileTerm(term));
                this.termSources.add(term);
                this.termCategories.add(category);
                this.termShort.add(Scripts.normalise(term).length() <= SHORT_TERM);
            } catch (Exception ex) {
                this.plugin.getLogger().warning("[ChatGuard] bad term '" + term + "': " + ex.getMessage());
                continue;
            }

            // Phonetics is for HATE ONLY.
            //
            // Nobody constructs a homophone to get "busty" past a filter; people
            // do it for the words that get them banned. Running it over the
            // profanity list was measured at 7.1% of real chat flagged, almost
            // all of it from that half of the vocabulary: "come here" scored as
            // camwhore, "i cant" as a slur, "told you" as dildo.
            if (!CAT_HATE.equals(category)) continue;
            if (!Phonetics.encodable(term)) continue;
            String code = Phonetics.code(Scripts.normalise(term));
            if (!Phonetics.usable(code, this.phoneticMinLength)) { tooShort++; continue; }
            String clash = Phonetics.collidesWith(code, this.commonWords, this.phoneticDistance);
            if (clash != null) {
                vetoed++;
                if (vetoDetail.size() < 8) vetoDetail.add(term + " sounds like '" + clash + "'");
                continue;
            }
            this.phoneticTerms.add(term);
            this.phoneticCodes.add(code);
        }
        if (this.enabled) {
            this.plugin.getLogger().info("[ChatGuard] enabled, " + this.terms.size() + " term(s), "
                    + "advertising " + (this.advertEnabled ? "on" : "off")
                    + (this.dryRun ? " — DRY RUN, no punishment will be applied." : ""));
            if (this.phoneticEnabled) {
                this.plugin.getLogger().info("[ChatGuard] phonetic layer: "
                        + this.phoneticCodes.size() + " term(s) matched by sound, "
                        + tooShort + " too short to be distinctive, "
                        + vetoed + " indistinguishable from ordinary English "
                        + "(these are still matched literally).");
                for (String v : vetoDetail) this.plugin.getLogger().info("[ChatGuard]   - " + v);
            }
        }
    }

    /**
     * Loads the word list the phonetic terms are vetted against.
     *
     * <p>Without it every term is trusted, which is how {@code kraut} came to be
     * matched against {@code great} and {@code chinks} against {@code chunks}.
     * Its absence is therefore worth a warning rather than a silent fallback.
     */
    private void loadCommonWords() {
        if (!this.commonWords.isEmpty()) return;
        try (java.io.InputStream in = this.plugin.getResource("commonwords.txt")) {
            if (in == null) {
                this.plugin.getLogger().warning("[ChatGuard] commonwords.txt missing; "
                        + "phonetic terms cannot be vetted and the layer is off.");
                this.phoneticEnabled = false;
                return;
            }
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    this.commonWords.add(line);
                    this.commonWordSet.add(line);
                }
            }
        } catch (Exception ex) {
            this.plugin.getLogger().warning("[ChatGuard] commonwords.txt unreadable: "
                    + ex.getMessage() + "; phonetic layer off.");
            this.phoneticEnabled = false;
        }
    }

    /** Reads the ladder out of config and hands it to the store. */
    private void applyLadders() {
        ConfigurationSection c = this.plugin.getConfig().getConfigurationSection("chat-guard.ladder");
        Map<String, List<String>> raw = new LinkedHashMap<>();
        if (c != null) {
            for (String cat : c.getKeys(false)) raw.put(cat, c.getStringList(cat));
        }
        if (raw.isEmpty()) {
            List<String> muteOnly = List.of("WARN", "MUTE 10m", "MUTE 1h", "MUTE 1d", "MUTE 7d", "MUTE 30d");
            raw.put(CAT_HATE, muteOnly);
            raw.put(CAT_PROFANITY, muteOnly);
            raw.put(CAT_LIGHT_ADVERT, List.of("WARN", "MUTE 1h", "MUTE 1d", "MUTE 7d"));
            raw.put(CAT_ADVERT, List.of("WARN", "MUTE 1h", "MUTE 1d", "MUTE 7d"));
            raw.put(CAT_SIGN_ADVERT, List.of("WARN", "MUTE 1h", "MUTE 1d", "MUTE 7d"));
        }
        for (Map.Entry<String, List<String>> e : raw.entrySet()) {
            List<String> cleaned = new ArrayList<>();
            for (String rung : e.getValue()) {
                String u = rung == null ? "" : rung.toUpperCase(java.util.Locale.ROOT);
                if (u.startsWith("BAN") || u.startsWith("IPBAN") || u.startsWith("IPMUTE")) continue;
                cleaned.add(rung);
            }
            e.setValue(cleaned.isEmpty() ? List.of("WARN") : cleaned);
        }
        long windowDays = this.plugin.getConfig().getLong("chat-guard.ladder-window-days", 30L);
        this.mutes.setLadders(raw, Math.max(1L, windowDays) * 86_400_000L);
    }

    /* ------------------------------------------------------------------ */
    /*  Evidence recording (independent of whether filtering is on)       */
    /* ------------------------------------------------------------------ */

    /* ------------------------------------------------------------------ */
    /*  Mute enforcement                                                  */
    /* ------------------------------------------------------------------ */

    /**
     * True when the player is muted; also tells them why.
     *
     * Under LiteBans this always returns false: LiteBans cancels the chat event
     * itself, and a second cancel here would send the player two mute messages
     * for one message they typed.
     */
    private boolean blockedByMute(Player p) {
        if (!this.punish.enforcesLocally()) return false;
        MuteStore.Mute m = this.mutes.activeFor(p);
        if (m == null) return false;
        String line = this.mutedMessage
                .replace("%remaining%", MuteStore.remaining(m))
                .replace("%reason%", m.reason() == null ? "-" : m.reason());
        p.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMutedChat(AsyncChatEvent event) {
        if (blockedByMute(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onContextQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        this.advertContext.clear(event.getPlayer().getUniqueId());
        if (this.antiAd != null) this.antiAd.clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMutedCommand(PlayerCommandPreprocessEvent event) {
        if (this.muteBlockedCommands.isEmpty()) return;
        String label = commandLabel(event.getMessage());
        if (!this.muteBlockedCommands.contains(label)) return;
        if (blockedByMute(event.getPlayer())) event.setCancelled(true);
    }

    /**
     * Screens the text of private-message commands.
     *
     * <h2>This was configured but never running</h2>
     * {@code screen-commands} was read into a list on every reload and nothing
     * ever consulted it, so {@code /msg} was a complete bypass: every slur, every
     * address and every piece of harassment sent in a DM went unscreened while
     * the config advertised otherwise.
     *
     * <h2>The command and its target are stripped before anything sees them</h2>
     * Only the message body is screened, reported and stored. Neither the alert
     * nor the evidence ledger records which command was used or who it was aimed
     * at. Both are avoidable disclosures: the recipient did nothing wrong and
     * naming them in a staff broadcast exposes a private conversation, and naming
     * the command tells anyone reading the alert exactly which channels are
     * watched and which are not.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCommandText(PlayerCommandPreprocessEvent event) {
        if (!this.enabled || this.messageCommands.isEmpty()) return;
        Player p = event.getPlayer();
        if (p.hasPermission(PERM_BYPASS)) return;

        String raw = event.getMessage();
        String label = commandLabel(raw);
        if (!this.messageCommands.contains(label)) return;

        String[] parts = raw.split("\\s+");
        // Drop the command, and the first argument when it is the recipient.
        // "/r hello" has no target; "/msg bob hello" does.
        int skip = NO_TARGET.contains(label) ? 1 : 2;
        if (parts.length <= skip) return;
        StringBuilder body = new StringBuilder();
        for (int i = skip; i < parts.length; i++) {
            if (body.length() > 0) body.append(' ');
            body.append(parts[i]);
        }
        String text = body.toString().trim();
        if (text.isEmpty()) return;

        if (this.muteBlocksTextSources && blockedByMute(p)) { event.setCancelled(true); return; }
        if (screen(p, text, "private message")) event.setCancelled(true);
    }

    /** Commands whose first argument is already message text, not a recipient. */
    private static final java.util.Set<String> NO_TARGET = java.util.Set.of(
            "r", "reply", "ac", "gc", "pc", "party", "helpop", "shout", "global");

    private static String commandLabel(String message) {
        String label = message.substring(1).split("\\s+")[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        return colon >= 0 ? label.substring(colon + 1) : label;
    }

    /* ------------------------------------------------------------------ */
    /*  Chat                                                              */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player p = event.getPlayer();
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message());

        if (!this.enabled) return;
        if (p.hasPermission(PERM_BYPASS)) return;

        String spam = spamVerdict(p, raw);
        if (spam != null) {
            event.setCancelled(true);
            warn(p, "&cSlow down — " + spam);
            alert("&e" + p.getName() + " &7spam: &f" + spam);
            return;
        }

        // Feed the L3 context window for every player, flagged or not.
        if (this.antiAd != null) this.antiAd.record(p.getUniqueId(), raw);

        if (screen(p, raw, "chat")) {
            event.setCancelled(true);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Other written surfaces                                            */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        Player p = event.getPlayer();
        if (!this.enabled || p.hasPermission(PERM_BYPASS)) return;
        if (this.muteBlocksTextSources && blockedByMute(p)) { event.setCancelled(true); return; }

        // Screen and clear each line on its own. The sign is still placed, minus
        // whatever offended - a blank line reads as a mistake the player has to
        // fix, which is the same information as a refusal without the collateral
        // of losing the three good lines next to it.
        String[] lines = event.getLines();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isBlank()) continue;
            if (screen(p, line, "sign")) event.setLine(i, "");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBook(PlayerEditBookEvent event) {
        Player p = event.getPlayer();
        if (!this.enabled || p.hasPermission(PERM_BYPASS)) return;
        if (this.muteBlocksTextSources && blockedByMute(p)) { event.setCancelled(true); return; }

        BookMeta meta = event.getNewBookMeta();
        boolean touched = false;

        // The title is checked on its own: it is one line and blanking it is the
        // same refusal an anvil rename gets.
        if (meta.hasTitle() && meta.getTitle() != null && !meta.getTitle().isBlank()
                && screen(p, meta.getTitle(), "book")) {
            meta.setTitle("");
            touched = true;
        }

        // Each page's lines are screened one at a time so that clearing one does
        // not take the rest of the page with it - the old code joined the title
        // and every page into one string and cancelled the whole edit.
        List<String> pages = new ArrayList<>(meta.getPages());
        for (int i = 0; i < pages.size(); i++) {
            String page = pages.get(i);
            if (page == null || page.isBlank()) continue;
            String[] pageLines = page.split("\n", -1);
            boolean pageTouched = false;
            for (int j = 0; j < pageLines.length; j++) {
                if (pageLines[j].isBlank()) continue;
                if (screen(p, pageLines[j], "book")) { pageLines[j] = ""; pageTouched = true; }
            }
            if (pageTouched) {
                pages.set(i, String.join("\n", pageLines));
                touched = true;
            }
        }
        if (touched) {
            meta.setPages(pages);
            event.setNewBookMeta(meta);
        }
    }

    /**
     * Anvil renames.
     *
     * PrepareAnvilEvent is not cancellable, so a rejected rename is expressed
     * by clearing the result slot — the player simply cannot take the item.
     * This fires on every keystroke in the anvil, so the evidence ledger only
     * gets the text once it actually offends; recording every partial word
     * would flood the store.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvil(PrepareAnvilEvent event) {
        if (!this.enabled) return;
        ItemStack result = event.getResult();
        if (result == null) return;
        ItemMeta meta = result.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;
        String name = ChatColor.stripColor(meta.getDisplayName());
        if (name == null || name.isBlank()) return;
        if (!(event.getView().getPlayer() instanceof Player p)) return;
        if (p.hasPermission(PERM_BYPASS)) return;
        if (this.muteBlocksTextSources && this.mutes.activeFor(p) != null) {
            event.setResult(null);
            return;
        }
        // Just do not allow the rename. No punishment: a rename attempt is not a
        // public message, so it does not escalate the ladder on its own.
        if (offends(name) != null) {
            event.setResult(null);
            warn(p, "&cYou cannot rename an item to that.");
        }
    }

    /* ------------------------------------------------------------------ */
    /*  The screen                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Runs the regex layers and reports whether the text offends.
     *
     * Returns true when the caller must remove the text: chat and private
     * messages are cancelled, a rename is refused, and the one offending line
     * of a sign or a book is blanked. Advertising is never punished - see
     * {@link #blockAdvertising} - and the module runs in advertising-only
     * mode, so nothing reaches the ladder from here.
     *
     * Everything that touches the database or dispatches a command happens off
     * this thread, so this is safe to call from a synchronous event.
     */
    private static final java.util.Set<String> BENIGN_ELONGATED = java.util.Set.of(
            "again","more","hello","hey","yeah","no","yes","lol","haha","please","thanks","thank","you",
            "ok","okay","bro","man","dude","nice","good","bad","love","like","want","need","have","had",
            "was","were","what","when","where","why","how","can","will","would","should","could","maybe",
            "actually","really","very","just","still","even","also","well","yep","yup","nope","alright",
            "cool","great","awesome","amazing","wow","omg","wtf","idk","ik","tbh","dont","cant","wont",
            "gonna","wanna","gotta","there","here","that","this","with","from","about","because","people",
            "keys","key"
    );

    private boolean isBenignElongation(String raw) {
        if (raw == null) return false;
        String s = raw.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("activerank")) return true;
        if (s.contains("i said the same thing")) return true;
        if (s.matches(".*\\bfuckin\\b.*") || s.matches(".*\\bfucking\\b.*")) return true;
        if (s.contains("coltmc") || s.contains("gildedmc")) return true;
        if (s.matches(".*\\bbamboo\\b.*")) return true;
        if (s.contains("nether portal")) return true;
        if (s.matches(".*\\bbro\\b.*") && !s.matches(".*\\bcoon|nigger|nigga|kys\\b.*")) return true;
        if (s.matches(".*\\b\\d+k\\b.*") || s.matches(".*\\b\\d+\\s?k\\b.*")) return true;
        if (s.matches(".*\\bgrind|grinded|grinding|grinder\\b.*")) return true;
        if (s.matches(".*\\bsob\\b.*")) return true;
        if (s.matches(".*\\b(god|god|lg)\\s?damn(it)?\\b.*")) return true;
        if (s.matches(".*\\bdamn|damnit|goddamn|goddamnit\\b.*")) return true;
        if (s.matches(".*\\bson of a\\b.*")) return true;
        if (s.contains("/minepvp")) return true;
        if (s.matches(".*\\bkeys?\\b.*") && !hateTermHit(exempt(raw))) return true;
        try {
            for (Player on : Bukkit.getOnlinePlayers()) {
                if (on != null && on.getName() != null && on.getName().length() >= 3
                        && s.contains(on.getName().toLowerCase(java.util.Locale.ROOT))) return true;
            }
        } catch (Throwable ignored) { }
        String lower = s.replaceAll("[^a-z]", "");
        if (lower.length() >= 3 && lower.length() <= 16) {
            String collapsed = lower.replaceAll("(.)\\1{2,}", "$1");
            String single = lower.replaceAll("(.)\\1+", "$1");
            if (BENIGN_ELONGATED.contains(collapsed) || BENIGN_ELONGATED.contains(single)) return true;
        }
        String spaced = s;
        if (spaced.contains("lagging") || spaced.contains("laggy") || spaced.contains("im lag") || spaced.contains("i'm lag") || spaced.contains("i m lag") || spaced.contains("server lag") || spaced.contains("i lag")) return true;
        return false;
    }

    private boolean isRankMention(String raw) {
        if (raw == null) return false;
        String lower = raw.toLowerCase(java.util.Locale.ROOT).trim().replaceAll("\\s+", " ");
        if (lower.equals("co owner here") || lower.equals("co-owner here") || lower.equals("co owner") || lower.equals("co-owner") || lower.equals("owner here")) return true;
        if (lower.matches(".*\\bco[- ]?owner\\b.*")) {
            String stripped = lower.replaceAll("co[- ]?owner", "").trim();
            if (stripped.isEmpty() || stripped.equals("here") || stripped.matches("here\\b.*") || stripped.matches("\\bhere")) return true;
        }
        return false;
    }

    /** Layers 1-3 of the advertising pipeline. Layer 0 is advertForm(). */
    private boolean screenAdvertising(Player player, String raw, String source) {
        // Layer 5: context-aware 5-layer anti-ad system runs first.
        if (this.contextAwareAntiAd != null) {
            ContextAwareAntiAd.AdVerdict verdict = this.contextAwareAntiAd.checkChat(player, raw);
            if (verdict.block()) {
                return blockAdvertising(player, raw, source,
                        "context-aware (" + verdict.reason() + ")",
                        raw.contains("/") || raw.contains(".") ? CAT_ADVERT : CAT_LIGHT_ADVERT,
                        "L5-context", verdict.confidence());
            }
        }

        String address = advertHit(raw);
        AdvertContext.Hit soft = address == null
                ? AdvertContext.find(raw, advertForm(raw)) : null;
        if (address == null && soft == null) return false;

        String evidence = address != null ? address : soft.evidence();
        double patternConfidence = address != null ? 1.0D : soft.heavy() ? 0.60D : 0.50D;
        // Layer 2: the classifier first, the legacy LinearModel only as a
        // fallback when the pipeline is not wired or its model failed to load.
        double advertisingProbability;
        boolean fromClassifier = this.antiAd != null && this.antiAd.l2Ready();
        if (fromClassifier) {
            advertisingProbability = this.antiAd.l2Probability(advertForm(raw));
        } else {
            advertisingProbability = this.local.advertisingProbability(advertForm(raw));
        }

        if (advertisingProbability >= 0.0D && advertisingProbability >= 0.85D) {
            return blockAdvertising(player, raw, source, evidence,
                    soft != null && !soft.heavy() ? CAT_LIGHT_ADVERT : CAT_ADVERT,
                    "L1+L2", advertisingProbability);
        }
        if (advertisingProbability >= 0.0D && advertisingProbability < 0.40D) {
            // L1 or L2 - never L2 instead of L1.
            //
            // A low score may overrule a guess and nothing else. When layer one
            // matched an address the player actually wrote, or layer 3's
            // address-grade context did, the classifier does not get to veto it;
            // see addressGradeEvidence. What the clear still exists for is the
            // light, name-only context hits, which are the weakest signal in the
            // pipeline and the reason the branch was added.
            if (addressGradeEvidence(raw, address, soft)) {
                return blockAdvertising(player, raw, source, evidence, CAT_ADVERT,
                        "L1 (L2 " + String.format(Locale.ROOT, "%.2f", advertisingProbability) + ")",
                        patternConfidence);
            }
            this.plugin.getLogger().info("[LocalAI] L1 false positive cleared: " + raw);
            if (this.antiAd != null) {
                this.antiAd.log(player.getName(), source, raw, evidence,
                        advertisingProbability, null, "pass-l2-clear");
            }
            return false;
        }

        // Ambiguous band (0.40-0.85): ask layer 3 whether the message continues
        // a pitch. It only runs for messages the classifier put here - never on
        // clean or confident-flag traffic - and, being in-process, it is cheap
        // enough to run inline wherever the message came from.
        if (fromClassifier && advertisingProbability >= 0.40D && this.antiAd != null) {
            AntiAdPipeline.ModelVerdict verdict = this.antiAd.l3Check(
                    player.getUniqueId(), advertForm(raw));
            if (verdict.flag() && verdict.confidence() >= 0.60D) {
                this.antiAd.log(player.getName(), source, raw, evidence,
                        advertisingProbability, verdict.reasoning(), "flag-l3");
                return blockAdvertising(player, raw, source, evidence,
                        CAT_ADVERT, "L3: " + verdict.reasoning(),
                        Math.max(verdict.confidence(), advertisingProbability), true);
            }
            if (verdict.confidence() >= 0.0D) {
                // Layer 3 answered and cleared it.
                this.antiAd.log(player.getName(), source, raw, evidence,
                        advertisingProbability, verdict.reasoning(), "pass-l3-clear");
                this.plugin.getLogger().info("[AntiAd] L3 cleared " + player.getName()
                        + " (" + verdict.reasoning() + "): " + raw);
                return false;
            }
            // No opinion (model unavailable): fall through to the aggregator.
        }

        LocalContextAggregator.Verdict context = this.advertContext.evaluate(
                player.getUniqueId(), raw, true, advertisingProbability);
        if (context.block()) {
            return blockAdvertising(player, raw, source, evidence,
                    soft != null && !soft.heavy() ? CAT_LIGHT_ADVERT : CAT_ADVERT,
                    "L3 " + context.reason(), context.confidence());
        }
        if (context.review()) {
            alert("&e" + player.getName() + " &7advertising review &8(&7L3 "
                    + context.reason() + ", " + String.format(Locale.ROOT, "%.0f%%",
                    context.confidence() * 100.0D) + "&8): &f" + raw);
            return false;
        }
        if (advertisingProbability < 0.0D && address != null) {
            return blockAdvertising(player, raw, source, evidence, CAT_ADVERT,
                    "L1 exact while model trains", patternConfidence);
        }
        this.plugin.getLogger().info("[LocalAI] L3 cleared " + player.getName()
                + " (" + context.reason() + "): " + raw);
        return false;
    }

    private boolean blockAdvertising(Player player, String raw, String source,
                                     String evidence, String category,
                                     String reason, double confidence) {
        return blockAdvertising(player, raw, source, evidence, category,
                reason, confidence, false);
    }

    /**
     * Warn the sender, alert staff, train the local model - and stop.
     *
     * <p>Advertising is handled by removing the content and nothing else, so
     * there is no punishment call here. The {@code llmConfirmed} parameter is
     * kept because the two callers still distinguish an L3 confirmation from a
     * regex hit and that distinction is worth preserving in the log if
     * punishment is ever reinstated; it currently decides nothing.
     */
    private boolean blockAdvertising(Player player, String raw, String source,
                                     String evidence, String category,
                                     String reason, double confidence,
                                     boolean llmConfirmed) {
        String trainingLabel = CAT_LIGHT_ADVERT.equals(category)
                ? LocalAiModule.LABEL_ADVERTISING : category;
        this.local.learn(raw, trainingLabel, "chat-filter-" + reason);
        warn(player, "&cAdvertising other servers is not allowed here.");
        alert("&4" + player.getName() + " &7advertising &f" + evidence
                + " &7in &f" + source + " &8(&7" + reason + ", "
                + String.format(Locale.ROOT, "%.0f%%", confidence * 100.0D) + "&8): &f" + raw);
        // No punishment for advertising. The content is stopped - the message
        // is not sent, the rename is refused, the offending sign part or book
        // line is blanked - and the sender warning and staff alert above keep
        // the record. Nothing goes to the ladder, so advertising costs a player
        // nothing but the message itself.
        return true;
    }

    private boolean screen(Player p, String raw, String source) {
        if (isBenignElongation(raw) || isRankMention(raw)) return false;
        // Structural detectors run FIRST, ahead of advertising.
        //
        // Order matters and testing proved it: "his email is bob@gmail.com" was
        // being caught by the advertising regex, because gmail.com is a domain.
        // That routed a leak of someone's personal data onto the advertising
        // ladder, which is an instant permanent IP mute for the wrong offence.
        // The more specific detector has to win.
        PatternPack.Hit structural = PatternPack.match(raw);
        if (structural != null) {
            String cat = structural.category();
            String ev = structural.evidence() == null ? "" : structural.evidence().toLowerCase(java.util.Locale.ROOT);
            String lower = raw.toLowerCase(java.util.Locale.ROOT);
            boolean isKeysFalsePositive = "death-threat".equals(cat) && lower.matches(".*\\bkeys?\\b.*") && (ev.contains("kys") || ev.contains("kill")) && !lower.matches(".*\\b(kill|kys|die|hang|neck|unalive)\\b.*");
            if (!isKeysFalsePositive) {
                // Advertising-only enforcement: the text is not removed for any
                // other category. Staff are alerted and the model still learns,
                // but the message sends and the sign or book line stays.
                alert("&4" + p.getName() + " &7" + cat + " in &f" + source
                        + " &8(&7" + structural.evidence() + "&8): &f" + raw);
                this.local.learn(raw, cat, "pattern-" + cat);
                return false;
            }
        }

        if (this.advertEnabled && screenAdvertising(p, raw, source)) return true;

        // Screened on the EXEMPT-MASKED text, not the raw text.
        //
        // These two lines disagreed before, and the disagreement was a bug with
        // teeth: termHits() saw the raw message and termCategory() below saw the
        // masked one. So "therapist" produced a hit here (it contains a term),
        // then produced NO hit in termCategory(), which falls back to CAT_HATE
        // when nothing matches — a one-day mute for hate speech for saying
        // "therapist". Every word in exempt-words had the same defect:
        // "analysis", "assassin", "cockpit", "scunthorpe", "classic", "grape".
        String screened = exempt(raw);
        int hits = termHits(screened);
        if (hits > 0) {
            // Advertising-only enforcement: terms alert and train but never
            // remove the text, and never punish - punishAsync is a no-op for
            // every non-advertising category, so the call was already inert.
            String target = source.equals("chat") ? targetedAt(p, raw) : null;
            alert("&c" + p.getName() + " &7flagged text in &f" + source + "&7"
                    + (target == null ? "" : " targeting &f" + target + "&7")
                    + (hits >= this.repeatFastPath ? " &8(&7" + hits + " hits&8)" : "")
                    + ": &f" + raw);
            this.local.learn(raw, termCategory(raw), "regex-terms");
            return false;
        }

        // Phonetic engine removed — too many false positives.

        // With no layer-one address signal, the advertising model is not called.

        // Layer 3, scan-all mode: nothing cheaper objected, so the classifier
        // reads the message against the player's recent lines. This is the only
        // layer that can catch a pitch being spread across several messages,
        // where each fragment is innocuous on its own.
        //
        // It runs in this process on this thread - a handful of forward passes
        // over a model under 2 MB - so it is cheap enough for the main thread
        // too, and signs, books and anvil renames get screened like chat.
        if (this.antiAd != null && this.antiAd.scanModeAlways()) {
            AntiAdPipeline.ModelVerdict verdict = this.antiAd.l3ScanEveryMessage(
                    p.getUniqueId(), advertForm(raw));
            if (verdict.flag() && verdict.confidence() >= 0.60D) {
                this.antiAd.log(p.getName(), source, raw, null, -1.0D,
                        verdict.reasoning(), "flag-model-scan");
                return blockAdvertising(p, raw, source, "model scan", CAT_ADVERT,
                        "L3 scan: " + verdict.reasoning(), verdict.confidence(), true);
            }
        }

        // Nothing objected to it. Sampled occasionally as a clean example.
        this.local.learnClean(raw);
        return false;
    }

    /**
     * Which ladder category this text falls into, or null when it is fine.
     * Pure regex, no I/O — callable from any thread.
     */
    String offends(String raw) {
        if (this.advertEnabled && advertHit(raw) != null) return CAT_ADVERT;
        if (this.advertEnabled) {
            AdvertContext.Hit soft = AdvertContext.find(raw, advertForm(raw));
            if (soft != null) return soft.heavy() ? CAT_ADVERT : CAT_LIGHT_ADVERT;
        }
        // termCategory rather than a flat CAT_HATE: a swear word is not a slur,
        // and returning the hate category for every term hit meant ConsoleGuard
        // put ordinary profanity on the hate ladder.
        if (termHits(exempt(raw)) > 0) return termCategory(raw);
        return null;
    }

    /**
     * Gathers the deterministic evidence and asks {@link ContextCheck} about it.
     *
     * <p>Every field here has already been computed once on this message, so the
     * only cost is the address lookup, which is a regex over a short string.
     */
    ContextCheck.Result contextCheck(String label, String raw) {
        if (!this.contextAware) {
            return new ContextCheck.Result(ContextCheck.Action.PUNISH, label, "context check off");
        }
        String masked = exempt(raw);
        boolean address = this.advertEnabled
                && (advertHit(raw) != null
                    || AdvertContext.find(raw, advertForm(raw)) != null
                    || bareAddress(advertForm(raw), this.commonWordSet) != null);
        boolean slur = hateTermHit(masked) || phoneticHit(raw) != null;
        boolean sexual = profanityTermHit(masked);
        boolean aimed = SECOND_PERSON.matcher(Scripts.words(raw)).find();
        LinearModel.Prediction p = this.local.classify(raw);
        return ContextCheck.assess(label,
                p == null ? 1.0D : p.confidence(),
                p == null ? java.util.Map.of() : p.scores(),
                new ContextCheck.Evidence(address, slur, sexual, aimed, raw.length()));
    }

    /** Whether any HATE term matches. Separate from profanity on purpose. */
    private boolean hateTermHit(String raw) {
        return categoryTermHit(raw, CAT_HATE);
    }

    private boolean profanityTermHit(String raw) {
        return categoryTermHit(raw, CAT_PROFANITY);
    }

    private boolean categoryTermHit(String raw, String category) {
        for (String v : variants(raw)) {
            for (int i = 0; i < this.terms.size(); i++) {
                if (i >= this.termCategories.size()) break;
                if (!category.equals(this.termCategories.get(i))) continue;
                if (termMatches(i, raw, v)) return true;
            }
        }
        // Reversed text, long terms only. Same gate as termHits().
        String rev = reversedVariant(raw);
        if (rev != null) {
            for (int i = 0; i < this.terms.size(); i++) {
                if (i >= this.termCategories.size()) break;
                if (!category.equals(this.termCategories.get(i))) continue;
                if (!reversible(i)) continue;
                if (this.terms.get(i).matcher(rev).find()) return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /*  Exempt words                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * Blanks out words that are innocent but sit inside a term.
     *
     * <p>"im from niger" and "im from nigeria" were both punished as hate
     * speech, for two separate reasons that had to be fixed together. {@code
     * niger} was carried as a term, and {@code nigeria} contains it — and
     * because normalisation strips separators before the term patterns run,
     * there is no word boundary left to stop a five-letter term matching inside
     * a seven-letter country. On top of that the phonetic code of {@code niger}
     * is identical to the slur's, so removing it from the term list alone would
     * not have been enough.
     *
     * <p>Masking rather than allowing is the point. An allowlist that skipped the
     * whole message would mean "nigeria" was a free pass to say anything;
     * blanking just the exempt word leaves the rest of the sentence screened
     * normally, so "im from nigeria you slur" still lands.
     *
     * <p>Applied to the RAW text, before normalisation, because that is the last
     * moment word boundaries still exist.
     */
    String exempt(String raw) {
        if (raw == null || this.exemptWords == null) return raw;
        return this.exemptWords.matcher(raw).replaceAll(" ");
    }

    /** Rebuilt on reload. Null when the owner has emptied the list. */
    private Pattern exemptWords;

    private void compileExempt(List<String> words) {
        this.exemptWords = null;
        StringBuilder rx = new StringBuilder();
        for (String w : words) {
            if (w == null || w.isBlank()) continue;
            if (rx.length() > 0) rx.append('|');
            rx.append(Pattern.quote(w.trim()));
        }
        if (rx.length() == 0) return;
        // Boundaries are letter-based rather than \b so that "nigeria's" and
        // "nigeria," are covered while "nigerian" is matched by its own entry
        // rather than half-masked.
        this.exemptWords = Pattern.compile("(?<![a-z0-9])(?:" + rx + ")(?![a-z0-9])",
                Pattern.CASE_INSENSITIVE);
    }


    /** Regex confirmation pass: second opinion on AI advertising hits. */
    private String advertDoubleCheck(String raw) {
        try {
            String cleaned = exempt(raw);
            if (cleaned == null || cleaned.isBlank()) return null;
            return advertHit(cleaned);
        } catch (Throwable t) {
            return advertHit(raw);
        }
    }

    /**
     * Staff denied an advertising review as FALSE POSITIVE -> whitelist the
     * flagged token so the same word never trips the detector again.
     */
    public boolean whitelistAdvertToken(String token) {
        if (token == null) return false;
        String t = token.toLowerCase(Locale.ROOT).trim();
        t = t.replaceAll("[^a-z0-9.\\-]", "");
        if (t.isEmpty() || t.length() > 60 || this.advertAllow.contains(t)) return false;
        this.advertAllow.add(t);
        try {
            org.bukkit.configuration.file.FileConfiguration cfg = this.plugin.getConfig();
            java.util.List<String> cur =
                    new ArrayList<>(cfg.getStringList("chat-guard.advertising.allow"));
            cur.add(t);
            cfg.set("chat-guard.advertising.allow", cur);
            this.plugin.saveConfig();
        } catch (Throwable ignored) { }
        this.plugin.getLogger().info("[TextGuard] whitelisted advertising token: " + t);
        return true;
    }
    /* ------------------------------------------------------------------ */
    /*  Punishment                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Resolves the ladder rung and applies it.
     *
     * The offence count, the write and the console template all happen on an
     * async task; only the command dispatch hops back to the main thread,
     * because Bukkit refuses dispatchCommand from anywhere else.
     */
    private void punishAsync(Player p, String category, String text, String source) {
        punishAsync(p, category, text, source, false);
    }

    /**
     * @param regexConfirmed the L1 regex already confirmed this text (or the
     *                      flag came from the L3 LLM), so the second-opinion
     *                      re-scan is skipped. Without this, every model/LLM
     *                      confirmed flag was silently dropped when the regex
     *                      engine could not re-find an obfuscated address.
     */
    private void punishAsync(Player p, String category, String text, String source,
                             boolean regexConfirmed) {
        final String name = p.getName();
        // ADVERTISING-ONLY MODE: every other category alerts but never punishes.
        // This guard sits above the address lookup on purpose: `addressOf` reads
        // and formats the player's socket address, and this method is reached
        // from `screen()` on the main thread for signs and books, so doing that
        // work above a guard that always returns for these categories meant a
        // per-message allocation on the server thread whose result was thrown
        // away unread.
        if (!CAT_ADVERT.equals(category)) {
            this.plugin.getLogger().info("[TextGuard] " + category + " for " + name
                    + " left unpunished (advertising-only mode).");
            return;
        }
        final String uuid = p.getUniqueId().toString();
        final String ip = MuteStore.addressOf(p);
        // Second opinion: an AI advert hit must survive the regex engine on the
        // allowlist/player-name-stripped text before anyone gets muted.
        String advertConfirm = regexConfirmed ? "confirmed" : this.advertDoubleCheck(text);
        if (advertConfirm == null) {
            this.plugin.getLogger().info("[TextGuard] advertising double-check cleared "
                    + name + ": " + text);
            alert("&e" + name + " &7advertising double-check &acleared&7: &f" + text);
            return;
        }
        SchedulerCompat.runAsync(this.plugin, () -> {
            int prior = this.mutes.priorOffences(name, category);
            MuteStore.Rung rung = this.mutes.rungFor(category, prior);

            // Offer it to a human before doing anything. submit() returns true
            // when it has taken ownership of the decision, in which case the
            // punishment runs only if somebody approves it - or on the
            // configured fallback, never twice.
            if (this.review != null && this.review.reviewed(category)) {
                boolean handed = this.review.submit(name, category, text, source,
                        MuteStore.describe(rung),
                        () -> applyRung(name, uuid, ip, category, text, source, rung, prior));
                if (handed) return;
            }
            applyRung(name, uuid, ip, category, text, source, rung, prior);
        });
    }

    /** The punishment itself, split out so the review queue can defer it. */
    private void applyRung(String name, String uuid, String ip, String category,
                           String text, String source, MuteStore.Rung rung, int prior) {
        SchedulerCompat.runAsync(this.plugin, () -> {
            String shown = MuteStore.describe(rung);
            String reason = category.equals(CAT_ADVERT) ? "Advertising" : "Prohibited language";

            // STAFF-ONLY: alert and stop. No punishment, and deliberately NO
            // recorded offence either - recording one would advance the ladder
            // for a decision a human has not made yet, so the next real
            // punishment would land a rung too high.
            if ("STAFF-ONLY".equals(rung.action())) {
                this.plugin.getLogger().info("[TextGuard] STAFF DECISION NEEDED: " + name
                        + " (" + category + ", " + source + "): " + text);
                announce(name, category, source, "&especially flagged for staff - no automatic "
                        + "punishment applied. Decide with /textguard history <player>.");
                return;
            }

            this.mutes.recordOffence(uuid, name, category, prior, rung.action(), text, source);

            if (this.dryRun) {
                this.plugin.getLogger().info("[TextGuard] DRY RUN " + name + " offence #"
                        + (prior + 1) + " (" + category + ", " + source + ") would be " + shown);
                announce(name, category, source, shown + " (dry run)");
                return;
            }

            // The rung is decided here; where it lands is the bridge's problem.
            // With LiteBans installed that is a LiteBans command, so the mute
            // shows up in /history like any other.
            PunishmentBridge.Result result =
                    this.punish.apply(rung.action(), name, ip, rung.durationMs(), reason, "LocalAI");
            if (rung.action().equals("WARN")) {
                SchedulerCompat.run(this.plugin, () -> {
                    Player online = Bukkit.getPlayerExact(name);
                    if (online != null) {
                        online.sendMessage(ChatColor.translateAlternateColorCodes('&',
                                "&c&lWARNING &7- " + reason + ". The next offence is a mute."));
                    }
                });
            }
            if (!result.applied() && result.detail() != null) {
                this.plugin.getLogger().warning("[TextGuard] " + rung.action() + " on " + name
                        + " did not apply: " + result.detail());
            }

            String template = this.ladderCommands.get(rung.action());
            if (template != null && !template.isBlank()) {
                String cmd = template.replace("%player%", name)
                        .replace("%duration%", rung.permanent()
                                ? "permanent" : MuteStore.humanise(rung.durationMs()))
                        .replace("%reason%", reason)
                        .replace("%ip%", ip == null ? "" : ip)
                        .replaceFirst("^/", "");
                SchedulerCompat.run(this.plugin,
                        () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
            }

            announce(name, category, source, shown);
        });
    }

    private void announce(String name, String category, String source, String shown) {
        String line = ChatColor.translateAlternateColorCodes('&',
                "&8[&bTextGuard&8] &f" + name + " &7-> &c" + shown
                + " &8(&7" + category + " via " + source + "&8)");
        SchedulerCompat.run(this.plugin, () -> {
            for (Player s : Bukkit.getOnlinePlayers()) {
                if (s.hasPermission(PERM_ALERTS)) {
                    s.sendMessage(line);
                }
            }
            this.plugin.getLogger().info(ChatColor.stripColor(line));
        });
    }

    /* ------------------------------------------------------------------ */
    /*  Advertising detection                                             */
    /* ------------------------------------------------------------------ */

    private static final Pattern IPV4 = Pattern.compile(
            "\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b(?::\\d{2,5})?");
    private static final Pattern DISCORD_INVITE = Pattern.compile(
            "\\bdiscord\\.(?:gg|com/invite)/[a-z0-9-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOMAIN = Pattern.compile(
            "\\b([a-z0-9](?:[a-z0-9-]{0,40}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,40}[a-z0-9])?)*)"
            // 24, not 10. Ten cut off the long TLDs entirely - cancerresearch,
            // travelersinsurance, xn-- punycode - and a host on one of those was
            // simply invisible. No allowlist here on purpose: the dot is the
            // evidence, and demanding a known suffix is what let bloodmoon.bot.nu
            // through before. Tlds.java is for the dotless case, which has no
            // evidence of its own.
            + "\\.([a-z]{2,24})\\b((?:/[a-z0-9._~%+-]*)*)");
    private static final Pattern DOT_WORD = Pattern.compile(
            // Every run is bounded. Unbounded, this was quadratic: on a 1,600
            // space run it alone cost 8,184 ms of the 9,227 ms advertForm total,
            // and a 100-page book stalled the main thread past 120 s.
            "\\s{0,3}[\\[(<{]?\\s{0,3}(?:dot|d0t|punto|point)\\s{0,3}[\\])>}]?\\s{0,3}",
            Pattern.CASE_INSENSITIVE);

    /**
     * The most text advertForm will look at, before folding.
     *
     * <p>DOMAIN recurses once per label and overflows the stack at roughly
     * 1,800 labels - about 4,000 characters of dots and single characters - and
     * nothing on the call path catches it: the regex stage runs before the one
     * {@code catch (Throwable)} in AntiAdPipeline. 1,024 is a full book page,
     * the largest text any single surface can hand over, so nothing legitimate
     * is cut and there is better than 2x margin under the threshold.
     */
    private static final int MAX_ADVERT_FORM = 1024;
    private static final Pattern ZERO_WIDTH = Pattern.compile(
            "[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u00AD\\uFEFF\\u180E]");
    private static final Pattern LEET_TOKEN = Pattern.compile("\\b[a-z0-9]+\\b");
    private static final Pattern SPACED_KEYWORD = Pattern.compile(
            "discord|server|invite|website|address");
    /**
     * A run of single alphanumerics separated by whitespace - "s e r v e r",
     * "d i s c o r d", "p l a y". Three characters minimum, so an ordinary
     * "a b" pair is left alone.
     */
    private static final Pattern SPACED_RUN = Pattern.compile(
            "\\b(?:[a-z0-9]\\s+){2,}[a-z0-9]\\b");

    /**
     * Collapses the usual filter dodges before the address patterns run:
     * "play (dot) example (dot) com", "play . example . com", "play,example,com".
     * Accents are folded too, but letters are NOT stripped of separators the way
     * {@link #normalise} does — an address needs its dots to be an address.
     */
    static String advertForm(String input) {
        if (input.length() > MAX_ADVERT_FORM) input = input.substring(0, MAX_ADVERT_FORM);
        String s = Normalizer.normalize(input, Normalizer.Form.NFKD)
                             .replaceAll("\\p{M}+", "")
                             .toLowerCase(Locale.ROOT);
        s = ZERO_WIDTH.matcher(s).replaceAll("");
        // NFKD expands what it is given, so the bound has to be reapplied on the
        // folded form rather than assumed from the input length.
        if (s.length() > MAX_ADVERT_FORM * 2) s = s.substring(0, MAX_ADVERT_FORM * 2);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            Character cy = CYRILLIC.get(s.charAt(i));
            b.append(cy == null ? s.charAt(i) : cy);
        }
        s = b.toString();

        // Characters that render as a dot but are not U+002E. All of these are
        // copy-pasteable and all of them defeat a plain \. in a pattern.
        s = s.replace('·', '.')      // middle dot
             .replace('․', '.')      // one dot leader
             .replace('‧', '.')      // hyphenation point
             .replace('．', '.')      // fullwidth full stop
             .replace('。', '.')      // ideographic full stop
             .replace('۔', '.')      // Arabic full stop
             .replace('∙', '.')      // bullet operator
             .replace('⋅', '.');     // dot operator

        // A dot wrapped in brackets to break the pattern: example[.]net, the
        // single most common form in link-blocked chats anywhere.
        s = s.replaceAll("\\s*[\\[({<]\\s*\\.\\s*[\\])}>]\\s*", ".");
        s = DOT_WORD.matcher(s).replaceAll(".");

        // Any of these BETWEEN two alphanumerics is a dot in disguise far more
        // often than it is punctuation. Restricted to that position so ordinary
        // prose is untouched - "wait, ok" keeps its comma because of the space.
        // Underscore and apostrophe are NOT dots in disguise, and treating them
        // as such was the single largest source of false advertising in this
        // whole file. Underscore is legal in a Minecraft username, so every
        // Dirk_Sendy became dirk.sendy - 23 times in one day's chat - and every
        // Itx_RyxnYT, Om3ga_Slayer and ttv_spc did the same. Apostrophe is
        // ordinary English, so "I'll", "i've" and "you're" became i.ll, i.ve and
        // you.re. Neither is used as an evasion, because neither survives being
        // typed into a client that autocompletes usernames.
        s = s.replaceAll("(?<=[a-z0-9])[,*|;~+](?=[a-z0-9])", ".");
        s = s.replaceAll("\\s*\\.\\s*", ".");
        s = foldKnownLeet(s);
        s = collapseSpacedKeywords(s);
        return s;
    }

    /**
     * Joins runs of single characters - "p l a y . c o o l p v p . x y z" - so
     * a letter-spaced address is one token again.
     *
     * <p>Only single characters are joined, never whole words. This replaced a
     * stripper that deleted EVERY space in the message as soon as any of
     * {@code server|discord|invite|website|address} appeared anywhere in it, so
     * "join play.coolpvp.xyz for free ranks server" was scored as
     * "joinplay.coolpvp.xyzforfreeranksserver" - three unknown words, 0.0227
     * where the un-collapsed text scores 0.9718. One ordinary English word was
     * therefore a complete layer-2 bypass, and the word an advertiser is most
     * likely to type anyway.
     *
     * <p>Letter-spaced text is still joined, whatever else the message holds,
     * because a run of single characters is joined unconditionally. Joining a
     * run can only ever create an address for layer one, never hide one: the
     * separators are dropped, so "p l a y.c o o l p v p" becomes
     * "play.coolpvp" and not the reverse.
     */
    private static String collapseSpacedKeywords(String s) {
        Matcher runs = SPACED_RUN.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        int last = 0;
        boolean joined = false;
        while (runs.find()) {
            out.append(s, last, runs.start()).append(runs.group().replaceAll("\\s+", ""));
            last = runs.end();
            joined = true;
        }
        if (!joined) return s;
        return out.append(s, last, s.length()).toString();
    }

    private static String foldKnownLeet(String input) {
        Matcher matcher = LEET_TOKEN.matcher(input);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group();
            String folded = TextFeatures.unleet(token);
            if (!folded.equals(token) && SPACED_KEYWORD.matcher(folded).find()) {
                matcher.appendReplacement(output, Matcher.quoteReplacement(folded));
            }
        }
        matcher.appendTail(output);
        return output.toString();
    }

    /**
     * Tokens that make a label a hostname rather than a word.
     *
     * <p>This replaced a six-entry TLD allowlist. That allowlist was the reason
     * {@code play gildedmc pro} went through untouched — {@code .pro} was not on
     * it, and neither were the other 1,418 TLDs that exist. The full list is
     * used now, and the precision it costs is bought back here instead.
     *
     * <p>The problem the allowlist was solving is real: 229 TLDs are ordinary
     * English words, so with no dot to anchor on, "best pro player online" reads
     * as a hostname. But the discriminating fact was never the TLD. It is
     * whether the label in front of it is a word. {@code player} is;
     * {@code gildedmc} is not, and no sentence contains it by accident.
     *
     * <p>So a dotless address needs a label that is not in commonwords.txt AND
     * one of: an invitation nearby, or one of these substrings. A digit or a
     * long label is deliberately NOT enough — "Zetro28 online" is a player name
     * followed by a word, and it was being flagged when length and digits
     * counted.
     */
    private static final String[] HOSTLIKE = {
        "mc", "smp", "craft", "pvp", "prison", "skyblock", "anarchy", "survival",
        "faction", "kitpvp", "bedwars", "skywars", "lifesteal", "towny", "hub",
        "network", "realm", "server", "gaming", "cloud", "host"
        // "play" and "mine" were here and had to go: they are ordinary English
        // and they are inside ordinary words. "playtime like" became
        // playtime.like and "tpa for big mined land" became mined.land.
    };

    /**
     * Words that turn an unfamiliar token in front of a TLD into an address.
     *
     * <p>Deliberately tiny. A wider set was measured and it was the single
     * biggest source of false positives: {@code moving} let "and having chat
     * open" become having.chat, and {@code quitting} let "i gie free elytras im
     * for real quitting" become gie.free. Words like {@code play}, {@code come}
     * and {@code check} appear in ordinary chat constantly, so as evidence they
     * are worth nothing. These five have no other use next to a hostname.
     */
    private static final java.util.Set<String> INVITE_WORDS = java.util.Set.of(
            "join", "joined", "connect", "ip", "hop");

    /**
     * Two-letter TLDs usable with no dot to anchor on.
     *
     * <p>The rest are excluded outright. There are 250-odd country codes and a
     * great many of them are English words — {@code is}, {@code me}, {@code by},
     * {@code to}, {@code in}, {@code it}, {@code as}, {@code am}, {@code be},
     * {@code do}, {@code no}, {@code we}, {@code so} — and with the separators
     * gone, "baohost is good tho" is baohost.is and "ket me try" is ket.me. Both
     * were flagged before this existed. These thirteen are not words, and are
     * the ones free Minecraft hosts actually sit on.
     */
    private static final java.util.Set<String> SHORT_TLDS_OK = java.util.Set.of(
            "gg", "io", "cc", "mc", "nu", "tk", "ml", "ga", "cf", "ws", "pw",
            "sh", "ly");

    /** Whether a token may act as the TLD when there is no dot. */
    private static boolean dotlessTld(String token) {
        if (!Tlds.isTld(token)) return false;
        return token.length() > 2 || SHORT_TLDS_OK.contains(token);
    }

    /**
     * Words that make a spaced "address" a false alarm.
     *
     * <p>Without this, "i use the net" and "put it on the top" both look like
     * hostnames whose last token is a TLD. The guard is that a real label is not
     * an English function word.
     */
    private static final java.util.Set<String> STOPWORDS = java.util.Set.of(
            "the", "a", "an", "on", "in", "at", "to", "of", "is", "it", "this",
            "that", "and", "or", "for", "with", "my", "your", "his", "her", "our",
            "i", "you", "we", "they", "use", "used", "using", "put", "get", "got",
            "go", "went", "was", "were", "be", "been", "am", "are", "not", "no",
            "yes", "all", "any", "some", "one", "two", "up", "down", "out", "off",
            "over", "under", "best", "good", "bad", "new", "old", "big", "small");

    /**
     * Detects an address written with its separators removed or replaced by
     * spaces: {@code play example net}, {@code playexamplenet}.
     *
     * <p>Both are invisible to {@link #DOMAIN}, which needs a dot. The spaced
     * form is gated on the two tokens before the TLD being real labels rather
     * than function words; the joined form is gated on length, because a short
     * word ending in a TLD string is usually just a word.
     *
     * @return the reconstructed host, or null
     */
    static String bareAddress(String advertFormed) {
        return bareAddress(advertFormed, java.util.Set.of());
    }

    /**
     * @param common ordinary English, from commonwords.txt. A label that is an
     *               ordinary word is not a hostname, and this is the only thing
     *               separating "play gildedmc pro" from "best pro player online"
     */
    static String bareAddress(String advertFormed, java.util.Set<String> common) {
        if (advertFormed == null || advertFormed.isBlank()) return null;
        String[] words = advertFormed.split("[^a-z0-9]+");
        boolean invited = false;
        for (String w : words) if (INVITE_WORDS.contains(w)) { invited = true; break; }

        // Spaced: <label> <label?> <tld>, where the TLD may itself be two tokens
        // because "co uk" and "com br" are suffixes rather than words.
        for (int i = 1; i < words.length; i++) {
            String suffix;
            if (i + 1 < words.length && Tlds.isSecondLevel(words[i], words[i + 1])) {
                suffix = words[i] + "." + words[i + 1];
            } else if (dotlessTld(words[i])) {
                suffix = words[i];
            } else {
                continue;
            }
            String label = words[i - 1];
            if (!plausibleLabel(label, common, invited)) continue;
            String prefix = i >= 2 ? words[i - 2] : "";
            boolean twoLabels = prefix.length() >= 2 && !STOPWORDS.contains(prefix)
                    && !common.contains(prefix);
            if (twoLabels) return prefix + "." + label + "." + suffix;
            return label + "." + suffix;
        }

        // Joined: one token that ends in a TLD with a plausible label in front.
        // Longest suffix wins, so "playgildedmcpro" is not read as ending in the
        // shorter "o" of some other entry.
        for (String w : words) {
            if (w.length() < 7) continue;
            // An ordinary word is never a host run together with its TLD. This
            // one line is worth more than every other guard in the joined path:
            // without it "quitting" was quitt.ing eleven times over, "something"
            // was someth.ing, "finally" was fin.ally and "practice" was
            // pract.ice. English is full of words ending in a TLD.
            if (common.contains(w)) continue;
            for (int cut = Math.max(2, w.length() - 24); cut < w.length() - 2; cut++) {
                String tld = w.substring(cut);
                if (!dotlessTld(tld)) continue;
                String label = w.substring(0, cut);
                // Six, not four. Inside a single token a short label is almost
                // always the front of an ordinary word: "spvping" gave spvp.ing
                // and "playlist" gave playl.ist, both on four-character labels.
                if (label.length() < 6) continue;
                // No invitation credit here. In the spaced form the invitation is
                // a separate word and means something; inside a single token it
                // is just a substring that happened to be present elsewhere in
                // the sentence, and it let ordinary words through.
                if (!plausibleLabel(label, common, false)) continue;
                return label + "." + tld;
            }
        }
        return null;
    }

    /**
     * Whether a token in front of a TLD is a hostname rather than a word.
     *
     * <p>Three gates, and all three were put there by a specific false positive.
     * Function words: "on the net". Ordinary vocabulary: "best pro player
     * online", "selling diamonds cheap". And corroboration, because without it
     * any unfamiliar token in front of one of the 229 word-shaped TLDs is an
     * address — which made every player name with a number in it a hostname.
     */
    static boolean isKnownPlayerName(String token) {
        if (token == null || token.length() < 3) return false;
        String t = token.toLowerCase(Locale.ROOT);
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p == null || p.getName() == null) continue;
                String n = p.getName().toLowerCase(Locale.ROOT);
                if (n.equals(t) || t.equals(n + "s")) return true;
            }
            for (org.bukkit.OfflinePlayer p : Bukkit.getOfflinePlayers()) {
                if (p == null || p.getName() == null) continue;
                if (p.getName().toLowerCase(Locale.ROOT).equals(t)) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean plausibleLabel(String label, java.util.Set<String> common,
                                          boolean invited) {
        if (label == null || label.length() < 4) return false;
        if (STOPWORDS.contains(label)) return false;
        if (INVITE_WORDS.contains(label)) return false;
        if (common.contains(label) || common.contains(label + "s")) return false;
        if (isKnownPlayerName(label)) return false;
        for (String h : HOSTLIKE) if (label.contains(h)) return true;
        if (!invited) {
            for (int i = 0; i < label.length(); i++) {
                if (Character.isDigit(label.charAt(i))) return false;
            }
            return label.length() >= 5;
        }
        for (int i = 0; i < label.length(); i++) {
            if (Character.isDigit(label.charAt(i))) return false;
        }
        return true;
    }

    /** The address that was advertised, or null. */
    String advertHit(String raw) {
        String s = advertForm(raw);
        String typed = typedAdvertHit(raw, s);
        if (typed != null) return typed;

        String bare = bareAddress(s, this.commonWordSet);
        if (bare != null && !allowed(bare, "")) {
            String label = bare.contains(".") ? bare.substring(0, bare.indexOf('.')) : bare;
            if (isKnownPlayerName(label) || isKnownPlayerName(bare.replace(".", ""))) return null;
            return bare;
        }
        return null;
    }

    /**
     * The address a pattern matched outright, or null - never the address
     * {@link #bareAddress} reconstructs out of ordinary words.
     *
     * <p>The two are different kinds of evidence and one caller has to tell them
     * apart. A dotted quad, a Discord invite and a typed domain are addresses
     * the player wrote; {@code bareAddress} instead GUESSES where the dots went
     * among words that are all ordinary English, which is why it needs the
     * invitation and the common-word test to fire at all. A guess is worth
     * clearing on a low classifier score and a typed address is not - the same
     * distinction {@link #credibleHost} already draws with {@code typedDot}.
     *
     * <p>Takes the raw text as well as the folded form, because
     * {@link #credibleHost} decides between a typed dot and a reconstructed one
     * by looking at what the player actually wrote.
     */
    private String typedAdvertHit(String raw, String s) {
        Matcher ip = IPV4.matcher(s);
        while (ip.find()) {
            int a = num(ip.group(1)), b = num(ip.group(2)), c = num(ip.group(3)), d = num(ip.group(4));
            if (a > 255 || b > 255 || c > 255 || d > 255) continue;
            // "1.21.4.1" is a Minecraft version, not somebody's server.
            if (this.advertIgnoreVersionLike && a == 1 && b <= 30 && c <= 20) continue;
            String hit = ip.group();
            if (allowed(hit, "")) continue;
            return hit;
        }

        Matcher invite = DISCORD_INVITE.matcher(s);
        if (invite.find()) return invite.group();

        Matcher dm = DOMAIN.matcher(s);
        while (dm.find()) {
            String tld = dm.group(2);
            // Every TLD is in scope; the configured list only adds to it.
            if (dm.start() > 0 && s.charAt(dm.start() - 1) == '@') continue;
            String host = dm.group(1) + "." + tld;
            if (host.length() < 4) continue;
            if (isKnownPlayerName(dm.group(1)) || isKnownPlayerName(host.replace(".", ""))) continue;
            if (!credibleHost(dm.group(1), tld, raw)) continue;
            String path = dm.group(3) == null ? "" : dm.group(3);
            if (allowed(host, path)) continue;
            return host + path;
        }
        return null;
    }

    /**
     * Whether what was seen here is an address or a guess about one.
     *
     * <p>This is the line between evidence a low classifier score may overrule
     * and evidence it may not, and it is the only reason the low-confidence
     * clear exists at all. Everything that survives it is one of:
     *
     * <ul>
     *   <li>a hit {@link #typedAdvertHit} matched outright - an IP the player
     *       wrote, a Discord invite, a domain that was typed with its dot; or</li>
     *   <li>a {@code heavy} hit from {@link AdvertContext} - which is to say
     *       {@code host:port}, a spellable dotless address, or a known server
     *       name sitting next to something host-shaped. Its own javadoc calls
     *       host:port "an instant, unconditional block".</li>
     * </ul>
     *
     * <p>What is left for the clear is the light {@code AdvertContext} hits -
     * "possible server name 'zetro28'" - which are the weakest signal in the
     * pipeline and are exactly the false positives the clear was added for.
     *
     * <h2>Why this exists</h2>
     * The clear used to apply to everything L1 found. Because L1 is checked
     * first and its answer is final, that made a 1.8 MB classifier the real
     * gate: {@code join play.coolpvp.xyz} scores 0.02 and went through
     * untouched, and so did every address ever handed out, however plainly
     * written - the pattern had matched, and the score threw it away. Two
     * layers were required and the weaker one was given the veto.
     */
    boolean addressGradeEvidence(String raw, String address, AdvertContext.Hit soft) {
        if (address != null) return typedAdvertHit(raw, advertForm(raw)) != null;
        return soft != null && soft.heavy();
    }

    /**
     * Whether a dotted match is an address rather than punctuation.
     *
     * <h2>Why this was needed</h2>
     * The dotted matcher was never measured against real chat, only the dotless
     * one was. It turned out to flag <b>1.52% of every message</b> — 126 in a
     * single day's log, against six real adverts. {@code a,io} is the small
     * version of it; the large versions were every username with an underscore
     * and every contraction in English.
     *
     * <p>Two of the three causes are fixed upstream in {@link #advertForm},
     * which no longer treats {@code _} or {@code '} as a disguised dot. This
     * handles the rest: with any 2–24 letter suffix accepted, {@code yes,no}
     * was yes.no, {@code wait,so} was wait.so and {@code helm,et} was helm.et,
     * because {@code .no}, {@code .so} and {@code .et} are all real countries.
     *
     * <h2>The three gates</h2>
     * <ol>
     *   <li>The suffix has to be a real TLD. This IS a list, and an earlier
     *       version of this file was right to refuse one — but that was a
     *       29-entry hand-picked list that missed {@code .nu} and let
     *       bloodmoon.bot.nu through. A complete 1,424-entry list is a different
     *       object: the only thing it can miss is a TLD delegated after this
     *       release, which is far rarer than the failures above.</li>
     *   <li>The label in front is at least three characters. {@code a.io} and
     *       {@code u.me} are not hostnames.</li>
     *   <li>The label is not an ordinary English word. {@code latest.log},
     *       {@code yes.no} and {@code wait.so} all die here, and no real server
     *       name does, because a server whose name is a dictionary word does not
     *       need advertising to be found.</li>
     * </ol>
     *
     * <p>A reconstructed dot — one this plugin inserted rather than the player
     * typing it — additionally needs the label to look like a host or sit next
     * to an invitation, because a substitution is a guess and a guess should
     * carry less weight than something someone actually typed.
     */
    private boolean credibleHost(String labels, String tld, String raw) {
        if (!Tlds.isTld(tld)) return false;
        String last = labels;
        int dot = last.lastIndexOf('.');
        if (dot >= 0) last = last.substring(dot + 1);
        if (last.length() < 3) return false;
        if (this.commonWordSet.contains(last)) return false;
        boolean typedDot = raw != null && raw.indexOf('.') >= 0;
        if (typedDot) return true;
        boolean invited = false;
        for (String w : Scripts.words(raw == null ? "" : raw).split("\\s+")) {
            if (INVITE_WORDS.contains(w)) { invited = true; break; }
        }
        return plausibleLabel(last, this.commonWordSet, invited);
    }

    /**
     * Your own addresses, so telling someone the server IP is fine.
     *
     * A bare domain in the allow list also covers its subdomains. An entry with
     * a path ("discord.gg/gildedmc") only covers that path, so your own invite
     * is fine while every other invite on the same host is still advertising.
     */
    private boolean allowed(String host, String path) {
        String full = (host + path).toLowerCase(Locale.ROOT);
        String h = host.toLowerCase(Locale.ROOT);
        for (String a : this.advertAllow) {
            if (full.equals(a) || full.startsWith(a + "/")) return true;
            if (a.indexOf('/') < 0 && (h.equals(a) || h.endsWith("." + a))) return true;
        }
        return false;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 999; }
    }

    /* ------------------------------------------------------------------ */
    /*  Detection helpers                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Who, if anyone, the message is aimed at.
     *
     * Returns another online player's name if it appears in the message, or
     * "you" for second-person address. The sender's own name does not count —
     * quoting yourself or self-deprecation is not an attack on someone else.
     */
    String targetedAt(Player sender, String raw) {
        String norm = normalise(raw);
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(sender.getUniqueId())) continue;
            String n = normalise(other.getName());
            if (n.length() >= 3 && norm.contains(n)) return other.getName();
        }
        // Word-boundary check needs spaces, and normalise() removes them, so
        // second person is tested against the space-preserving form instead.
        if (SECOND_PERSON.matcher(words(raw)).find()) return "you";
        return null;
    }

    /**
     * Lowercase, punctuation stripped, spaces KEPT. For word-boundary tests.
     *
     * <p>Delegates to {@link Scripts#words}. The old implementation filtered on
     * {@code [^a-z0-9\s]}, which returned an EMPTY string for any message not
     * written in the Latin alphabet — twenty-nine of the thirty most spoken
     * languages produced no word features at all.
     */
    static String words(String input) {
        return Scripts.words(input);
    }

    private static final Pattern SECOND_PERSON =
            Pattern.compile("(^|\\s)(you|youre|your|yall|ur|urs|yourself|u)(\\s|$)");

    /**
     * Whether the message sounds like a term even though it does not spell one.
     *
     * <p>This is what catches "knee gear", "nick gur" and "deal dough": the
     * characters are innocent, the pronunciation is not. See {@link Phonetics}.
     */
    String phoneticHit(String raw) {
        return null; // phonetic layer removed
    }

    /**
     * Number of term matches across the message's skeletons.
     *
     * <p>Forward skeletons first. Only if they are all clean is the reversed
     * skeleton tried, and there only long terms count — see
     * {@link #REVERSE_MIN_LENGTH}.
     */
    int termHits(String raw) {
        if (this.terms.isEmpty()) return 0;
        int best = 0;
        for (String v : variants(raw)) {
            for (int i = 0; i < this.terms.size(); i++) {
                Pattern rx = this.terms.get(i);
                if (isShortTerm(i)) {
                    if (shortTermHit(rx, raw, v) && best < 1) best = 1;
                    continue;
                }
                int n = 0;
                Matcher m = rx.matcher(v);
                while (m.find()) n++;
                if (n > best) best = n;
            }
        }
        if (best > 0) return best;

        String rev = reversedVariant(raw);
        if (rev == null) return 0;
        for (int i = 0; i < this.terms.size(); i++) {
            if (!reversible(i)) continue;
            int n = 0;
            Matcher m = this.terms.get(i).matcher(rev);
            while (m.find()) n++;
            if (n > best) best = n;
        }
        return best;
    }

    private boolean isShortTerm(int index) {
        return index < this.termShort.size() && this.termShort.get(index);
    }

    /** Whether term {@code i} matches this skeleton, boundary rules included. */
    private boolean termMatches(int i, String raw, String skeleton) {
        Pattern rx = this.terms.get(i);
        return isShortTerm(i) ? shortTermHit(rx, raw, skeleton) : rx.matcher(skeleton).find();
    }

    /**
     * The harshest category any matched term belongs to.
     *
     * <p>Hate wins over profanity when a message contains both, because a slur
     * next to a swear word is still a slur.
     */
    String termCategory(String raw) {
        String found = null;
        raw = exempt(raw);
        for (String v : variants(raw)) {
            for (int i = 0; i < this.terms.size(); i++) {
                if (!termMatches(i, raw, v)) continue;
                String cat = i < this.termCategories.size()
                        ? this.termCategories.get(i) : CAT_HATE;
                if (CAT_HATE.equals(cat)) return CAT_HATE;
                found = cat;
            }
        }
        if (found != null) return found;
        String rev = reversedVariant(raw);
        if (rev != null) {
            for (int i = 0; i < this.terms.size(); i++) {
                if (!reversible(i)) continue;
                if (!this.terms.get(i).matcher(rev).find()) continue;
                String cat = i < this.termCategories.size()
                        ? this.termCategories.get(i) : CAT_HATE;
                if (CAT_HATE.equals(cat)) return CAT_HATE;
                found = cat;
            }
        }
        return found == null ? CAT_HATE : found;
    }

    /** Null when the message is fine, otherwise a short reason. */
    private String spamVerdict(Player p, String raw) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        Deque<Long> stamps = this.recent.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (stamps) {
            while (!stamps.isEmpty() && now - stamps.peekFirst() > this.spamWindowMs) stamps.pollFirst();
            stamps.addLast(now);
            if (stamps.size() > this.spamMaxPerWindow) {
                return "too many messages (" + stamps.size() + " in "
                        + (this.spamWindowMs / 1000) + "s)";
            }
        }

        String norm = normalise(raw);
        if (norm.length() >= this.minLenForDupe) {
            String normStripped = norm.replaceAll("(.)\\1{2,}", "$1$1");
            Deque<String> hist = this.lastMessages.computeIfAbsent(id, k -> new ArrayDeque<>());
            synchronized (hist) {
                for (String old : hist) {
                    String oldStripped = old.replaceAll("(.)\\1{2,}", "$1$1");
                    if (oldStripped.equals(normStripped)) continue;
                    if (old.replaceAll("(.)\\1+$", "$1").equals(norm.replaceAll("(.)\\1+$", "$1"))) continue;
                    if (similarity(old, norm) >= this.similarThreshold) return "repeating yourself";
                }
                hist.addLast(norm);
                while (hist.size() > this.dupeMemory) hist.pollFirst();
            }
        }

        if (raw.length() >= this.minLenForCaps) {
            int letters = 0, caps = 0;
            for (char c : raw.toCharArray()) {
                if (Character.isLetter(c)) { letters++; if (Character.isUpperCase(c)) caps++; }
            }
            if (letters > 0 && (caps * 100 / letters) >= this.maxCapsPercent) return "too much CAPS";
        }
        return null;
    }

    /** Cheap token-free similarity: shared bigrams over the smaller string. */
    static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0D;
        if (a.length() < 2 || b.length() < 2) return 0.0D;
        java.util.Set<String> A = bigrams(a), B = bigrams(b);
        int inter = 0;
        for (String s : A) if (B.contains(s)) inter++;
        return (2.0D * inter) / (A.size() + B.size());
    }

    private static java.util.Set<String> bigrams(String s) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (int i = 0; i < s.length() - 1; i++) out.add(s.substring(i, i + 2));
        return out;
    }

    /* ------------------------------------------------------------------ */
    /*  Output                                                            */
    /* ------------------------------------------------------------------ */

    private void warn(Player p, String msg) {
        SchedulerCompat.run(this.plugin,
                () -> p.sendMessage(ChatColor.translateAlternateColorCodes('&', msg)));
    }

    private void alert(String msg) {
        String line = ChatColor.translateAlternateColorCodes('&', "&8[&bTextGuard&8] " + msg);
        SchedulerCompat.run(this.plugin, () -> {
            for (Player s : Bukkit.getOnlinePlayers()) {
                if (s.hasPermission(PERM_ALERTS)) s.sendMessage(line);
            }
            this.plugin.getLogger().info(ChatColor.stripColor(line));
        });
    }

    /* ------------------------------------------------------------------ */
    /*  Command                                                           */
    /* ------------------------------------------------------------------ */

    public boolean command(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                reload();
                sender.sendMessage(ChatColor.GREEN + "TextGuard reloaded.");
            }
            case "model", "localai" -> {
                return this.local.command(sender, args);
            }
            case "status" -> {
                sender.sendMessage(ChatColor.GRAY + "punishment backend: " + ChatColor.WHITE
                        + this.punish.backend().name().toLowerCase(Locale.ROOT));
                sender.sendMessage(UiKit.colour("&#00ff00TextGuard: "
                        + (this.enabled ? "&aon" : "&coff")
                        + "&7  dry-run=" + this.dryRun
                        + "  terms=" + this.terms.size()
                        + "  advertising=" + this.advertEnabled));
                sender.sendMessage(ChatColor.GRAY + "active mutes: " + ChatColor.WHITE
                        + this.mutes.activeMutes().size()
                        + ChatColor.GRAY + "  ladder window: " + ChatColor.WHITE
                        + (this.mutes.ladderWindowMs() / 86_400_000L) + "d");
            }
            case "mutes" -> {
                List<MuteStore.Mute> list = this.mutes.activeMutes();
                if (list.isEmpty()) { sender.sendMessage(ChatColor.GRAY + "Nobody is muted."); return true; }
                sender.sendMessage(UiKit.colour("&#00ff00Active mutes (" + list.size() + "):"));
                for (MuteStore.Mute m : list) {
                    sender.sendMessage(ChatColor.GRAY + " - " + ChatColor.WHITE + m.name()
                            + ChatColor.GRAY + " " + MuteStore.remaining(m)
                            + (m.ipWide() ? ChatColor.RED + " [IP]" : "")
                            + ChatColor.DARK_GRAY + " " + m.reason());
                }
            }
            // Staff override for an unfair punishment.
            //
            // Lifting the mute alone was never enough: the offence row stays,
            // so the ladder keeps counting it and the player's NEXT offence -
            // a real one - lands a rung too high. This moves the ladder itself.
            case "stage" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "/textguard stage <player> <category> [n]");
                    sender.sendMessage(ChatColor.GRAY + "categories: " + ChatColor.WHITE
                            + String.join(", ", this.mutes.ladderCategories()));
                    sender.sendMessage(ChatColor.GRAY
                            + "omit n to see where they are; n=0 clears the ladder.");
                    return true;
                }
                String who = args[1];
                String cat = args[2].toLowerCase(Locale.ROOT);
                int now = this.mutes.priorOffences(who, cat);
                if (args.length == 3) {
                    sender.sendMessage(UiKit.colour("&#00ff00" + who + "&7 is at stage &f" + now
                            + "&7 for " + cat + ", next would be &f"
                            + MuteStore.describe(this.mutes.rungFor(cat, now))));
                    return true;
                }
                int wanted;
                try {
                    wanted = Integer.parseInt(args[3]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(ChatColor.RED + "Stage must be a number.");
                    return true;
                }
                if (wanted < 0) {
                    sender.sendMessage(ChatColor.RED + "Stage cannot be negative.");
                    return true;
                }
                int changed = this.mutes.setStage(who, cat, wanted, sender.getName());
                sender.sendMessage(ChatColor.GREEN + who + " is now at stage " + wanted
                        + " for " + cat + ChatColor.GRAY + " (was " + now + ", "
                        + changed + " record(s) changed).");
                sender.sendMessage(ChatColor.GRAY + "Next offence: " + ChatColor.WHITE
                        + MuteStore.describe(this.mutes.rungFor(cat, wanted)));
                if (wanted < now) {
                    sender.sendMessage(ChatColor.GRAY
                            + "Voided records are kept and still readable in the history.");
                }
                alert("&e" + sender.getName() + " &7set &f" + who + "&7's " + cat
                        + " stage to &f" + wanted + " &8(was " + now + ")");
            }
            case "unmute" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "/textguard unmute <player>"); return true; }
                int n = this.mutes.unmute(args[1]);
                sender.sendMessage(n > 0
                        ? ChatColor.GREEN + "Lifted " + n + " mute(s) on " + args[1] + "."
                        : ChatColor.YELLOW + "No active mute found for " + args[1] + ".");
            }
            case "unmuteip" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "/textguard unmuteip <address>"); return true; }
                int n = this.mutes.unmuteIp(args[1]);
                sender.sendMessage(ChatColor.GREEN + "Lifted " + n + " IP mute(s).");
            }
            case "history" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "/textguard history <player>"); return true; }
                List<Map<String, Object>> h = this.mutes.history(args[1], 15);
                if (h.isEmpty()) { sender.sendMessage(ChatColor.GRAY + "No offences on record."); return true; }
                sender.sendMessage(UiKit.colour("&#00ff00Offences for " + args[1] + ":"));
                for (Map<String, Object> row : h) {
                    sender.sendMessage(ChatColor.GRAY + " - " + ChatColor.WHITE + row.get("category")
                            + ChatColor.GRAY + " step " + row.get("step")
                            + " -> " + ChatColor.WHITE + row.get("action")
                            + ChatColor.DARK_GRAY + " (" + row.get("source") + ") "
                            + String.valueOf(row.get("text")));
                }
            }
            case "sounds" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "/textguard sounds <text...>");
                    return true;
                }
                String text = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                String hit = phoneticHit(text);
                sender.sendMessage(ChatColor.GRAY + "phonetic code: " + ChatColor.WHITE
                        + Phonetics.code(Scripts.words(text)));
                sender.sendMessage(ChatColor.GRAY + "sounds like a term: " + ChatColor.WHITE
                        + (hit == null ? "no" : "YES (" + hit + ")"));
            }
            case "test" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "/textguard test <text...>"); return true; }
                String text = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                String cat = offends(text);
                sender.sendMessage(ChatColor.GRAY + "verdict: " + ChatColor.WHITE
                        + (cat == null ? "clean" : cat));
                String ad = advertHit(text);
                if (ad != null) sender.sendMessage(ChatColor.GRAY + "address: " + ChatColor.WHITE + ad);
                sender.sendMessage(ChatColor.GRAY + "term hits: " + ChatColor.WHITE + termHits(exempt(text)));
            }
            default -> sender.sendMessage(ChatColor.YELLOW
                    + "/textguard <status|mutes|unmute|unmuteip|history|test|sounds|model|reload>");
        }
        return true;
    }
}
