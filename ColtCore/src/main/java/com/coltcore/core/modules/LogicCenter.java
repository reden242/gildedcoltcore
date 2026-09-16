package com.coltcore.core.modules;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The self logic center Ã¢â‚¬â€ a reasoning pass that sits between the classifier's
 * verdict and the decision to punish.
 *
 * <h2>Why this exists</h2>
 * The original model was, to quote the owner, dumb as bricks: typing "bamboo"
 * flagged as advertising, "Angelogaming123" (a player standing in chat) flagged
 * as a server advert, and ordinary opinions about other players escalated the
 * hate ladder. A bag of character n-grams cannot tell sarcasm from sincerity or
 * an opinion from an attack; it only knows which bucket the n-grams resemble.
 *
 * <p>This layer asks three questions the classifier cannot:
 *
 * <ol>
 *   <li><b>Is this actually advertising?</b> An advert needs somewhere to go Ã¢â‚¬â€
 *       a host-shaped token, an invite verb, or an IP. A Minecraft item name,
 *       a player name, or a bare number is not an address.</li>
 *   <li><b>Is this actually hate?</b> Hate is aimed. A slur aimed at nobody is
 *       banter at best; "X is my alt" contains no slur at all. Skill opinions
 *       ("you're bad at pvp") are not hate either.</li>
 *   <li><b>Is this sarcasm?</b> Sarcasm markers ("lol", "jk", "/s", "obviously")
 *       next to a doxxing-shaped claim mean the claim is not credible. Doxxing
 *       still requires real identifiers Ã¢â‚¬â€ PatternPack owns that check Ã¢â‚¬â€ but a
 *       sarcastic frame drops the severity instead of escalating it.</li>
 * </ol>
 */
public final class LogicCenter {

    private LogicCenter() { }

    public enum Decision {
        /** The message is fine. Overrides any model verdict. */
        ALLOW,
        /** The message is suspicious but this layer cannot be sure. Staff review. */
        REVIEW,
        /** Evidence checks out. Let the punishment proceed. */
        PROCEED
    }

    public record Verdict(Decision decision, String reason) { }

    /** Sarcasm and joking markers. Any one of these downgrades certainty. */
    private static final List<String> SARCASM_MARKERS = List.of(
            "lol", "lmao", "lmfao", "haha", "hehe", "xd", "jk", "jkk",
            "kidding", "kiddin", "/s", "sarcastic", "obviously", "clearly",
            "yeah right", "sure buddy", "totally", "for sure", "fr fr",
            "no way", "imagine", "bro thinks", "cap", "no cap", "fr"
    );

    /**
     * Opinion and relationship frames. None of these are attacks no matter
     * what names appear inside them.
     */
    private static final List<String> OPINION_FRAMES = List.of(
            "is my alt", "is my brother", "is my sister", "is my friend",
            "is my cousin", "is my main", "i think", "in my opinion", "imo",
            "imho", "i feel like", "maybe", "probably", "might be",
            "could be", "kinda", "sorta", "not gonna lie", "ngl"
    );

    /** Skill/gameplay complaints. Aimed at a person, still not hate. */
    private static final List<String> SKILL_FRAMES = List.of(
            "bad at", "trash at", "terrible at", "worst at", "good at",
            "better than", "worse than", "carried", "hard stuck", "no life",
            "get good", "skill issue", "l skill", "free", "eZ", "ez clap"
    );

    /**
     * Minecraft vocabulary that must never be read as a hostname. These were
     * all measured false positives of the joined-token path before the
     * common-word list caught them; anything the list misses lands here.
     */
    private static final Set<String> NEVER_HOSTS = Set.of(
            "bamboo", "bamboos", "obsidian", "netherite", "diamond", "diamonds",
            "emerald", "emeralds", "redstone", "glowstone", "quartz", "amethyst",
            "copper", "iron", "gold", "coal", "lapis", "sand", "gravel",
            "clay", "dirt", "stone", "cobblestone", "deepslate", "wood",
            "planks", "sticks", "string", "feather", "leather", "gunpowder",
            "blazerod", "blaze", "enderpearl", "pearl", "pearls", "eyeofender",
            "totem", "totems", "elytra", "shulker", "shulkers", "beacon",
            "conduit", "trident", "mace", "crossbow", "shield", "arrow",
            "arrows", "bow", "sword", "pickaxe", "axe", "shovel", "hoe",
            "helmet", "chestplate", "leggings", "boots", "apple", "gapple",
            "goldenapple", "steak", "beef", "porkchop", "chicken", "melon",
            "pumpkin", "carrot", "potato", "beetroot", "wheat", "sugar",
            "cane", "kelp", "cactus", "vine", "vines", "moss", "sculk",
            "spawner", "spawners", "crate", "crates", "key", "keys", "kit",
            "kits", "rank", "ranks", "shard", "shards", "token", "tokens",
            "vote", "votes", "voting", "auction", "auctions", "shop",
            "shops", "market", "marketplace", "trade", "trades", "trading",
            "sell", "selling", "buy", "buying", "price", "prices", "cheap",
            "expensive", "stack", "stacks", "chunk", "chunks", "spawn",
            "nether", "end", "overworld", "portal", "portals", "farm",
            "farms", "base", "bases", "grinder", "grinders", "mobdrop"
    );

    /** Invite verbs that make a bare token look like a destination. */
    private static final Set<String> INVITE_VERBS = Set.of(
            "join", "come", "play", "visit", "connect", "hop on", "hopon",
            "log onto", "login", "ip", "server", "address", "host", "port"
    );

    /**
     * Runs the three questions over a model verdict.
     *
     * @param raw        the raw message text
     * @param label      the model's top label
     * @param confidence its confidence
     * @param hasAddress whether a deterministic layer found a real address
     */
    public static Verdict evaluate(String raw, String label, double confidence, boolean hasAddress) {
        if (raw == null || raw.isBlank()) return new Verdict(Decision.ALLOW, "empty");
        if (label == null || label.equals(LocalAiModule.LABEL_CLEAN)) {
            return new Verdict(Decision.ALLOW, "model says clean");
        }
        String s = raw.toLowerCase(Locale.ROOT);

        boolean sarcastic = SARCASM_MARKERS.stream().anyMatch(s::contains);
        boolean opinion = OPINION_FRAMES.stream().anyMatch(s::contains);
        boolean skill = SKILL_FRAMES.stream().anyMatch(s::contains);
        boolean mentionsPlayer = mentionsKnownPlayer(s);

        switch (label) {
            case ChatGuardModule.CAT_ADVERT, ChatGuardModule.CAT_LIGHT_ADVERT -> {
                // No address anywhere and no invite verb: the classifier is
                // reacting to word shape, not to an advert.
                boolean inviteShaped = INVITE_VERBS.stream().anyMatch(s::contains);
                if (!hasAddress && !inviteShaped) {
                    return new Verdict(Decision.ALLOW,
                            "advertising verdict without any destination");
                }
                // Host-shaped but the "host" is just an item or a player name.
                if (containsOnlyNeverHostToken(s) && !inviteShaped) {
                    return new Verdict(Decision.ALLOW,
                            "advertising verdict over game vocabulary");
                }
                // Sarcastic mention of another server without a destination.
                if (sarcastic && !hasAddress) {
                    return new Verdict(Decision.REVIEW,
                            "sarcastic advertising shape, no destination");
                }
                return new Verdict(Decision.PROCEED, "advert with destination");
            }
            case ChatGuardModule.CAT_HATE -> {
                // "X is my alt" and friends are relationships, not attacks.
                if (opinion && !containsStrongSlurEvidence(s)) {
                    return new Verdict(Decision.ALLOW, "opinion frame, no slur evidence");
                }
                // Skill talk aimed at someone is trash talk, not hate.
                if (skill && !containsStrongSlurEvidence(s)) {
                    return new Verdict(Decision.ALLOW, "skill opinion, no slur evidence");
                }
                // A slur with nobody aimed at and a joke marker is banter at most.
                if (sarcastic && !mentionsPlayer) {
                    return new Verdict(Decision.REVIEW,
                            "slur shape inside a sarcastic frame, nobody targeted");
                }
                return new Verdict(Decision.PROCEED, "aimed hate");
            }
            default -> {
                return new Verdict(Decision.PROCEED, "structural category");
            }
        }
    }

    /** Whether the message names somebody we can see. */
    private static boolean mentionsKnownPlayer(String s) {
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p == null || p.getName() == null) continue;
                if (s.contains(p.getName().toLowerCase(Locale.ROOT))) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /**
     * True when every word-shaped candidate in the message is known game
     * vocabulary. "selling bamboo cheap" is commerce, not an advert.
     */
    private static boolean containsOnlyNeverHostToken(String s) {
        String[] words = s.replaceAll("[^a-z0-9 ]", " ").split("\\s+");
        boolean sawCandidate = false;
        for (String w : words) {
            if (w.length() < 5) continue;
            sawCandidate = true;
            if (!NEVER_HOSTS.contains(w)) return false;
        }
        return sawCandidate;
    }

    /**
     * Whether a deterministic layer would find actual slur material here.
     * Cheap approximation: known slur stems. This does NOT replace the regex
     * layers Ã¢â‚¬â€ it only tells the logic centre whether the message contains
     * something concrete or merely resembles hate.
     */
    private static final List<String> SLUR_STEMS = List.of(
            "nigger", "nigga", "n1gg", "faggot", "f4ggot", "coon ", " kike",
            "spic ", "chink", "wetback", "towelhead", "raghead", "tranny",
            "retard", "r3tard", "dyke ", "ngga", "ngger"
    );

    private static boolean containsStrongSlurEvidence(String s) {
        for (String stem : SLUR_STEMS) {
            if (s.contains(stem)) return true;
        }
        return false;
    }
}
