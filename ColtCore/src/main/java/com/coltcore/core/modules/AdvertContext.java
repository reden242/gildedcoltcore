package com.coltcore.core.modules;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catches the adverts that carry no address.
 *
 * <h2>The gap this closes</h2>
 * Address detection was at 100% precision and 95.9% recall, and the misses were
 * all the same kind: <em>"lets go play 2b2t"</em>, <em>"join minehut bro"</em>,
 * <em>"this server is dead, everyone moved to purple prison"</em>. There is no
 * host to match. A player who wants someone to leave does not need to hand them
 * a URL — they need only give them something to search for, and a name is
 * enough.
 *
 * <h2>Two independent signals, and why both are needed</h2>
 * <ol>
 *   <li><b>A known name.</b> Cheap and precise, and useless against a server
 *       nobody has listed.</li>
 *   <li><b>Context.</b> An invitation verb pointed at a proper noun that is not
 *       a Minecraft word. This catches names never seen before, and on its own
 *       it would be far too eager, which is why it requires <em>both</em> halves
 *       and rejects a long list of ordinary nouns.</li>
 * </ol>
 *
 * <h2>Free hosting providers are the giveaway</h2>
 * {@code aternos}, {@code minehut}, {@code falix}, {@code exaroton} and the rest
 * are how almost every small advertised server is hosted, and the provider name
 * survives every obfuscation of the subdomain. {@code hateiscool.aternos} has
 * no valid TLD and so is invisible to the domain pattern, but the word
 * {@code aternos} in a sentence about joining is not ambiguous.
 *
 * <h2>What it deliberately will not do</h2>
 * It does not fire on a server being named without an invitation — "i used to
 * play on hypixel" is a fact about someone's history, and a filter that punishes
 * it makes ordinary conversation impossible. The invitation is the offence.
 */
public final class AdvertContext {

    private AdvertContext() { }

    /** What was found, and how strong it is. */
    public record Hit(String evidence, boolean heavy) { }

    /**
     * Server names and hosting providers.
     *
     * <p>Hosting providers matter more than the big networks: a name like
     * {@code aternos} appears in the advert of every free server, so one entry
     * covers thousands of them.
     */
    private static final Set<String> KNOWN = new LinkedHashSet<>(List.of(
            // Free / cheap hosting, the highest-value entries here
            "aternos", "minehut", "falix", "exaroton", "ploudos", "apexhosting",
            "bisecthosting", "shockbyte", "serverpro", "scalacube", "mcprohosting",
            // Large public networks
            "hypixel", "mineplex", "cubecraft", "wynncraft", "manacube",
            "hivemc", "thehive", "pikanetwork", "complexgaming", "minecadia",
            "herobrine", "applecraft", "purpleprison", "opprison", "archonhq",
            "datblock", "mineberry", "vanillaverse", "craftyourtown",
            // Anarchy and lifesteal, the direct competitors for this audience
            "2b2t", "9b9t", "constantiam", "donutsmp", "loyalsmp", "oldfagsmp",
            "lifestealsmp", "originsmp", "earthsmp", "boxsmp", "anarchysmp",
            "crystalpvp", "potpvp", "kitpvp", "bedwars", "skywars"
    ));

    /** Phrasing that turns a name into an invitation. */
    private static final Pattern INVITE = Pattern.compile(
            "\\b(join|joining|come(?:\\s+(?:to|play|on|over))?|play(?:\\s+on)?|"
            + "hop\\s+on|hop\\s+onto|switch\\s+to|moved?\\s+to|moving\\s+to|"
            + "go\\s+(?:to|play)|lets\\s+(?:go|play)|check\\s+out|try\\s+out|try|"
            + "server\\s+ip|the\\s+ip|our\\s+ip|ip\\s+is|address\\s+is|"
            + "better\\s+(?:server|than)|quit(?:ting)?\\s+for|leave\\s+(?:this|here)|"
            + "everyone\\s+(?:is\\s+)?(?:on|at)|we\\s+play\\s+on|im\\s+on)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Words that look like a server name to the context rule but are not.
     *
     * <p>Without this, "come play survival with me" and "join the end run" both
     * read as an invitation pointed at a proper noun. Everything a Minecraft
     * player would legitimately invite someone to has to be here.
     */
    private static final Set<String> NOT_A_SERVER = new LinkedHashSet<>(List.of(
            "spawn", "survival", "creative", "hardcore", "nether", "end", "the",
            "overworld", "arena", "shop", "market", "base", "farm", "mine",
            "party", "team", "discord", "vc", "call", "voice", "lobby", "hub",
            "event", "raid", "war", "fight", "duel", "pvp", "pve", "game",
            "world", "server", "realm", "here", "there", "us", "me", "him",
            "her", "them", "everyone", "anyone", "someone", "again", "back",
            "now", "later", "tonight", "tomorrow", "today", "soon", "first",
            "next", "this", "that", "it", "one", "my", "your", "our", "their",
            "minecraft", "java", "bedrock", "vanilla", "modded", "smp",
            "and", "or", "with", "for", "on", "in", "at", "to", "up", "out",
            "bro", "man", "mate", "dude", "guys", "lol", "please", "pls"
    ));

    /**
     * Reminiscence, which is not an invitation.
     *
     * <p>"i used to play on hypixel" matched the invite pattern because
     * "play on" is in it, and it is a fact about someone's history rather than
     * an attempt to move anyone. Past tense and departure language veto the
     * whole check - a server a player has left is not a server they are
     * recruiting for.
     */
    private static final Pattern PAST = Pattern.compile(
            "\\b(used?\\s+to|played|playing\\s+before|was\\s+on|were\\s+on|"
            + "back\\s+when|years?\\s+ago|months?\\s+ago|weeks?\\s+ago|"
            + "last\\s+(?:year|month|week)|when\\s+i\\s+(?:played|was)|"
            + "i\\s+(?:quit|left|stopped)|no\\s+longer|not\\s+anymore|anymore|"
            + "remember\\s+when|miss\\s+that|old\\s+days)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A candidate name: letters and digits, long enough to mean something. */
    private static final Pattern WORD = Pattern.compile("[a-z][a-z0-9]{2,24}");

    /**
     * Something that already looks like a host.
     *
     * <p>Decides heavy versus light: a known name alongside an actual address is
     * full advertising, a bare name is not.
     */
    private static final Pattern HOSTLIKE = Pattern.compile("[a-z0-9]\\.[a-z]{2,}");

    /**
     * Numeric addresses written without dots.
     *
     * <p>"51 79 62 14" and "51-79-62-14" are an IP to any reader and are not an
     * IP to a pattern expecting dots. Four groups of one to three digits, each
     * in range, is not something that occurs by accident in chat — coordinates
     * are usually three numbers and frequently negative.
     */
    private static final Pattern SPACED_IP = Pattern.compile(
            "\\b(\\d{1,3})[\\s,;_/|-]+(\\d{1,3})[\\s,;_/|-]+(\\d{1,3})[\\s,;_/|-]+(\\d{1,3})\\b");

    /**
     * host:port. An instant, unconditional block.
     *
     * <p>The owner's examples were all this shape: berlin.caspianhost.com:30336,
     * bloodmoon.bot.nu:6001, Play.SurgeSMP.fun:20027. A port number after a host
     * has exactly one meaning in chat, and none of the TLD lists help - .bot.nu
     * and .xubi.org are not TLDs anyone enumerates, but ":30336" is unambiguous.
     *
     * <p>Deliberately does NOT require a known TLD, because that is the whole
     * bypass. Anything host-shaped followed by a colon and a plausible port is an
     * address being handed over.
     */
    private static final Pattern HOST_PORT = Pattern.compile(
            "\\b([a-z0-9][a-z0-9.-]{2,60}\\.[a-z]{2,24})\\s*:\\s*(\\d{2,5})\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Looks for an advert with no usable address in it.
     *
     * @param raw          the message as typed
     * @param advertFormed the same text after {@link ChatGuardModule#advertForm}
     * @return a hit, or null. {@code heavy} means treat it as full advertising
     */
    public static Hit find(String raw, String advertFormed) {
        if (advertFormed == null || advertFormed.isBlank()) return null;
        String s = advertFormed.toLowerCase(Locale.ROOT);

        // host:port first, and it ignores every other rule. A port is not
        // something a player types by accident, and reminiscence does not excuse
        // it - "i used to play on x.y.z:25565" is still handing over an address.
        Matcher hp = HOST_PORT.matcher(s);
        while (hp.find()) {
            int port = n(hp.group(2));
            if (port < 1 || port > 65535) continue;
            return new Hit("host:port " + hp.group(1) + ":" + port, true);
        }

        // Reminiscence is never an advert, whatever else is in the sentence.
        if (PAST.matcher(s).find()) return null;
        boolean invited = INVITE.matcher(s).find();

        // 1. A hosting provider or known network by name.
        // Naming a server is not advertising; inviting someone to it is. An
        // earlier version returned a hit either way, which flagged "hypixel is
        // fun i guess" and "2b2t has a long queue" - both ordinary conversation.
        // The invitation is the offence, so without one there is nothing here.
        if (invited) {
            String squashed = s.replaceAll("[^a-z0-9]", "");
            for (String name : KNOWN) {
                if (!squashed.contains(name)) continue;
                // Heavy only when an actual address came with it; a bare name is
                // light advertising and carries the lighter ladder.
                boolean hasHost = HOSTLIKE.matcher(s).find();
                return new Hit("server name '" + name + "'", hasHost);
            }
        }

        // 2. A dotless numeric address.
        Matcher ip = SPACED_IP.matcher(s);
        while (ip.find()) {
            int a = n(ip.group(1)), b = n(ip.group(2)), c = n(ip.group(3)), d = n(ip.group(4));
            if (a > 255 || b > 255 || c > 255 || d > 255) continue;
            // Coordinates are the false positive to avoid. A Minecraft version
            // starts 1.x, and a coordinate triple is three numbers - this needs
            // four, all in octet range, which coordinates rarely are.
            if (a == 1 && b <= 30) continue;
            return new Hit("dotless ip " + a + "." + b + "." + c + "." + d, true);
        }

        // 3. Invitation pointed at an unknown proper noun. The weakest signal, so
        //    it needs the invitation AND a word that is not ordinary vocabulary.
        if (!invited) return null;
        Matcher w = WORD.matcher(s);
        while (w.find()) {
            String word = w.group();
            if (NOT_A_SERVER.contains(word)) continue;
            if (Lexicon.size() > 0 && ORDINARY.contains(word)) continue;
            // A name that mixes letters and digits, or is unusually long, is far
            // more likely to be a server than an English word.
            boolean hasDigit = word.chars().anyMatch(Character::isDigit);
            if (hasDigit && word.length() >= 4) {
                return new Hit("possible server name '" + word + "'", false);
            }
        }
        return null;
    }

    /**
     * Everyday words that survive the {@link #NOT_A_SERVER} filter.
     *
     * <p>Kept separate because that list is about Minecraft nouns and this one is
     * about English. Both are needed: the context rule is the only part of this
     * class that can fire on a word nobody listed, and it is therefore the only
     * part that can be wrong in a way the owner cannot predict.
     */
    private static final Set<String> ORDINARY = new LinkedHashSet<>(List.of(
            "play", "playing", "played", "join", "joined", "joining", "come",
            "coming", "came", "going", "went", "gone", "get", "got", "make",
            "made", "want", "need", "like", "know", "think", "said", "says",
            "tell", "told", "give", "gave", "take", "took", "look", "see",
            "seen", "saw", "help", "helped", "build", "built", "find", "found",
            "time", "times", "day", "days", "week", "night", "morning",
            "good", "great", "best", "better", "cool", "nice", "fun", "dead",
            "new", "old", "big", "small", "more", "most", "less", "very",
            "really", "actually", "honestly", "maybe", "probably", "sure"
    ));

    private static int n(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 999; }
    }
}
