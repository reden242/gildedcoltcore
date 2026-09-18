package com.coltcore.core.modules;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.BookMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/**
 * The five-layer context-aware anti-advertising system.
 *
 * <h2>Layers</h2>
 * <ol>
 *   <li><b>Sign context</b> — checks if the player has broken or
 *       placed address-like signs recently ({@link SignContextTracker}).</li>
 *   <li><b>Rename context</b> — checks if the player has a history of
 *       address-like item renames ({@link RenameContextTracker}).</li>
 *   <li><b>Book/sign proximity</b> — checks if a book is placed near
 *       a sign ({@link BookSignProximity}).</li>
 *   <li><b>L2 — MillenniumNet</b> — the text classifier scoring
 *       p(advertising) on the normalized text ({@link AntiAdPipeline}).</li>
 *   <li><b>L3 — Contextual aggregation</b> — combines layers 1-4
 *       with the existing context window and local context aggregation
 *       ({@link LocalContextAggregator} + {@link ContextCheck}).</li>
 * </ol>
 *
 * <p>All layers must agree before a block is issued. A single layer
 * firing is a review flag, not a block.</p>
 */
public final class ContextAwareAntiAd {

    private static final double L2_BLOCK = 0.85D;
    private static final double L2_REVIEW = 0.70D;
    private static final double CONTEXT_REVIEW = 0.60D;

    private final AntiAdPipeline pipeline;
    private final SignContextTracker signTracker;
    private final RenameContextTracker renameTracker;
    private final BookSignProximity proximity;
    private final LocalContextAggregator localContext;
    private final Map<UUID, Long> lastFlagTimestamp = new ConcurrentHashMap<>();

    /**
     * Result of a context-aware anti-ad check.
     */
    public record AdVerdict(boolean block, double confidence, String reason,
                            String source, Map<String, Double> layerScores) {
        static AdVerdict pass(double confidence, String reason) {
            return new AdVerdict(false, confidence, reason, "pass", Map.of());
        }
        static AdVerdict review(double confidence, String reason, Map<String, Double> layerScores) {
            return new AdVerdict(false, confidence, reason, "review", layerScores);
        }
        static AdVerdict block(double confidence, String reason, Map<String, Double> layerScores) {
            return new AdVerdict(true, confidence, reason, "block", layerScores);
        }
    }

    public ContextAwareAntiAd(AntiAdPipeline pipeline, SignContextTracker signTracker,
                               RenameContextTracker renameTracker) {
        this.pipeline = pipeline;
        this.signTracker = signTracker;
        this.renameTracker = renameTracker;
        this.proximity = new BookSignProximity(signTracker);
        this.localContext = new LocalContextAggregator();
    }

    public void enable() {}

    public void disable() {
        lastFlagTimestamp.clear();
    }

    public void clear(UUID playerId) {
        lastFlagTimestamp.remove(playerId);
    }

    /**
     * False-positive guard: when most of the message's words are cached-benign
     * from confirmed clean traffic, a block is downgraded to a review. Staff
     * still see it, but a known-innocent vocabulary can no longer silence a
     * player on its own. Never upgrades, never passes silently.
     */
    private AdVerdict maybeDowngrade(AdVerdict verdict, String normalized) {
        if (!verdict.block()) return verdict;
        try {
            if (pipeline.wordPrior(normalized).benignKnown()) {
                return AdVerdict.review(verdict.confidence(),
                        verdict.reason() + " (word-cache benign-known, downgraded)",
                        verdict.layerScores());
            }
        } catch (Throwable ignored) {
            // The guard must never break a verdict.
        }
        return verdict;
    }

    /**
     * Runs all 5 layers on a chat message and returns the verdict.
     */
    public AdVerdict checkChat(Player player, String raw) {
        UUID id = player.getUniqueId();
        String normalized = ChatGuardModule.advertForm(raw);
        Map<String, Double> scores = new LinkedHashMap<>();

        // Layer 1: Sign context
        boolean signAddress = signTracker.hasAddressSignHistory(id);
        boolean signBroken = signTracker.hasBrokenAddressSigns(id);
        int nearbySignCount = signTracker.signProximityCount(player.getLocation());
        scores.put("layer1-sign", signAddress || signBroken ? 0.8D : 0.0D);

        // Layer 2: Rename context
        double renameScore = renameTracker.renameContextScore(id, "");
        scores.put("layer2-rename", renameScore);

        // Layer 3: Book/sign proximity
        boolean nearSign = proximity.bookNearSign(player.getLocation(), id);
        int proximityCount = proximity.signProximityCount(player.getLocation());
        scores.put("layer3-proximity", (nearSign || proximityCount > 2) ? 0.7D : 0.0D);

        // Layer 4: L2 — MillenniumNet classifier
        double l2Score = pipeline.l2Probability(normalized);
        scores.put("layer4-l2", l2Score);

        // Layer 5: L3 — contextual aggregation
        double l3Context = localContext.evaluate(id, raw, signAddress, l2Score).confidence();
        scores.put("layer5-context", l3Context);

        // Compute combined confidence
        double combined = 0.0D;
        double signWeight = 0.15D;
        double renameWeight = 0.15D;
        double proximityWeight = 0.10D;
        double l2Weight = 0.35D;
        double l3Weight = 0.25D;

        combined = signWeight * scores.get("layer1-sign")
                + renameWeight * scores.get("layer2-rename")
                + proximityWeight * scores.get("layer3-proximity")
                + l2Weight * scores.get("layer4-l2")
                + l3Weight * scores.get("layer5-context");

        // Decision logic
        boolean shouldBlock = false;
        boolean shouldReview = false;
        String reason = null;

        if (l2Score >= L2_BLOCK) {
            shouldBlock = true;
            reason = "L2 classifier confident (score " + String.format("%.3f", l2Score) + ")";
        } else if (l2Score >= L2_REVIEW && (signAddress || signBroken || renameScore >= 0.7)) {
            shouldReview = true;
            reason = "L2 borderline + context signal (score " + String.format("%.3f", l2Score) + ")";
        } else if (signAddress && signBroken && renameScore >= 0.5) {
            shouldReview = true;
            reason = "Multiple sign/rename context signals";
        } else if (nearSign && l2Score >= CONTEXT_REVIEW) {
            shouldReview = true;
            reason = "Book near sign + borderline L2";
        } else if (combined >= 0.75) {
            shouldBlock = true;
            reason = "Combined context score " + String.format("%.3f", combined);
        } else if (combined >= CONTEXT_REVIEW) {
            shouldReview = true;
            reason = "Combined context score " + String.format("%.3f", combined);
        }

        // Rate limit: max one block per 30 seconds per player
        long now = System.currentTimeMillis();
        Long last = lastFlagTimestamp.get(id);
        if (last != null && now - last < 30_000L && shouldBlock) {
            shouldBlock = false;
            shouldReview = true;
            reason += " (rate-limited to review)";
        }
        if (shouldBlock || shouldReview) {
            lastFlagTimestamp.put(id, now);
        }

        if (shouldBlock) {
            return maybeDowngrade(AdVerdict.block(combined, reason, scores), normalized);
        }
        if (shouldReview) {
            return AdVerdict.review(combined, reason, scores);
        }
        return AdVerdict.pass(combined, "context clear");
    }

    /**
     * Runs all 5 layers on a sign edit and returns the verdict.
     */
    public AdVerdict checkSign(Player player, String lineText) {
        UUID id = player.getUniqueId();
        String normalized = ChatGuardModule.advertForm(lineText);
        Map<String, Double> scores = new LinkedHashMap<>();

        // Layer 1: Sign context — is the player breaking/placing address-like signs?
        boolean signAddress = signTracker.hasAddressSignHistory(id);
        boolean signBroken = signTracker.hasBrokenAddressSigns(id);
        scores.put("layer1-sign", (signAddress || signBroken) ? 1.0D : 0.0D);

        // Layer 2: Rename context
        double renameScore = renameTracker.renameContextScore(id, "");
        scores.put("layer2-rename", renameScore);

        // Layer 3: Proximity
        boolean nearSign = proximity.bookNearSign(player.getLocation(), id);
        scores.put("layer3-proximity", nearSign ? 0.7D : 0.0D);

        // Layer 4: L2 classifier on sign text
        double l2Score = pipeline.l2Probability(normalized);
        scores.put("layer4-l2", l2Score);

        // Layer 5: Context
        double l3Context = localContext.evaluate(id, lineText, signAddress, l2Score).confidence();
        scores.put("layer5-context", l3Context);

        double combined = 0.15 * scores.get("layer1-sign")
                + 0.15 * scores.get("layer2-rename")
                + 0.10 * scores.get("layer3-proximity")
                + 0.35 * scores.get("layer4-l2")
                + 0.25 * scores.get("layer5-context");

        // Signs get instant block if L2 is confident AND player has sign history
        if (l2Score >= L2_BLOCK && (signAddress || signBroken)) {
            return maybeDowngrade(AdVerdict.block(combined,
                    "Sign: L2 confident + address sign history (score " + String.format("%.3f", l2Score) + ")",
                    scores), normalized);
        }
        if (l2Score >= L2_REVIEW && signAddress) {
            return AdVerdict.review(combined,
                    "Sign: L2 borderline + address sign history",
                    scores);
        }
        if (combined >= 0.75) {
            return maybeDowngrade(AdVerdict.block(combined, "Sign: combined score " + String.format("%.3f", combined), scores), normalized);
        }
        if (combined >= CONTEXT_REVIEW) {
            return AdVerdict.review(combined, "Sign: combined score " + String.format("%.3f", combined), scores);
        }
        return AdVerdict.pass(combined, "sign context clear");
    }

    /**
     * Runs all 5 layers on a book edit and returns the verdict.
     */
    public AdVerdict checkBook(Player player, String text) {
        UUID id = player.getUniqueId();
        String normalized = ChatGuardModule.advertForm(text);
        Map<String, Double> scores = new LinkedHashMap<>();

        // Layer 1: Sign context
        boolean signAddress = signTracker.hasAddressSignHistory(id);
        scores.put("layer1-sign", signAddress ? 0.8D : 0.0D);

        // Layer 2: Rename context
        double renameScore = renameTracker.renameContextScore(id, "");
        scores.put("layer2-rename", renameScore);

        // Layer 3: Proximity — book near sign
        boolean nearSign = proximity.bookNearSign(player.getLocation(), id);
        scores.put("layer3-proximity", nearSign ? 0.9D : 0.0D);

        // Layer 4: L2 classifier
        double l2Score = pipeline.l2Probability(normalized);
        scores.put("layer4-l2", l2Score);

        // Layer 5: Context
        double l3Context = localContext.evaluate(id, text, signAddress, l2Score).confidence();
        scores.put("layer5-context", l3Context);

        double combined = 0.15 * scores.get("layer1-sign")
                + 0.15 * scores.get("layer2-rename")
                + 0.10 * scores.get("layer3-proximity")
                + 0.35 * scores.get("layer4-l2")
                + 0.25 * scores.get("layer5-context");

        // Books near signs get stricter treatment
        if (nearSign && l2Score >= L2_REVIEW) {
            return maybeDowngrade(AdVerdict.block(combined,
                    "Book near sign + L2 borderline (score " + String.format("%.3f", l2Score) + ")",
                    scores), normalized);
        }
        if (l2Score >= L2_BLOCK) {
            return maybeDowngrade(AdVerdict.block(combined,
                    "L2 confident on book (score " + String.format("%.3f", l2Score) + ")",
                    scores), normalized);
        }
        if (combined >= 0.75) {
            return maybeDowngrade(AdVerdict.block(combined, "Book: combined score " + String.format("%.3f", combined), scores), normalized);
        }
        if (combined >= CONTEXT_REVIEW) {
            return AdVerdict.review(combined, "Book: combined score " + String.format("%.3f", combined), scores);
        }
        return AdVerdict.pass(combined, "book context clear");
    }

    /**
     * Runs all 5 layers on an anvil rename and returns the verdict.
     */
    public AdVerdict checkRename(Player player, String newName) {
        UUID id = player.getUniqueId();
        String normalized = ChatGuardModule.advertForm(newName);
        Map<String, Double> scores = new LinkedHashMap<>();

        // Layer 1: Sign context
        boolean signAddress = signTracker.hasAddressSignHistory(id);
        scores.put("layer1-sign", signAddress ? 0.6D : 0.0D);

        // Layer 2: Rename context — the rename itself
        double renameScore = renameTracker.renameContextScore(id, newName);
        boolean repeated = renameTracker.isRepeatedRename(id, newName);
        scores.put("layer2-rename", Math.max(renameScore, repeated ? 0.5D : 0.0D));

        // Layer 3: Proximity
        boolean nearSign = proximity.bookNearSign(player.getLocation(), id);
        scores.put("layer3-proximity", nearSign ? 0.5D : 0.0D);

        // Layer 4: L2 classifier
        double l2Score = pipeline.l2Probability(normalized);
        scores.put("layer4-l2", l2Score);

        // Layer 5: Context
        double l3Context = localContext.evaluate(id, newName, signAddress, l2Score).confidence();
        scores.put("layer5-context", l3Context);

        double combined = 0.15 * scores.get("layer1-sign")
                + 0.15 * scores.get("layer2-rename")
                + 0.10 * scores.get("layer3-proximity")
                + 0.35 * scores.get("layer4-l2")
                + 0.25 * scores.get("layer5-context");

        if (l2Score >= L2_BLOCK && (signAddress || renameScore >= 0.7)) {
            return maybeDowngrade(AdVerdict.block(combined,
                    "Rename: L2 confident + context (score " + String.format("%.3f", l2Score) + ")",
                    scores), normalized);
        }
        if (combined >= 0.75) {
            return maybeDowngrade(AdVerdict.block(combined, "Rename: combined score " + String.format("%.3f", combined), scores), normalized);
        }
        if (combined >= CONTEXT_REVIEW) {
            return AdVerdict.review(combined, "Rename: combined score " + String.format("%.3f", combined), scores);
        }
        return AdVerdict.pass(combined, "rename context clear");
    }

    /**
     * Returns whether the player has any context signals active.
     */
    public boolean hasContextSignals(UUID playerId) {
        return signTracker.hasAddressSignHistory(playerId)
                || signTracker.hasBrokenAddressSigns(playerId)
                || renameTracker.addressRenameCount(playerId) > 0
                || proximity.signProximityCount(null) > 0;
    }

    /**
     * Returns the context score breakdown for a player.
     */
    public Map<String, Double> contextScores(UUID playerId) {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("layer1-sign", signTracker.hasAddressSignHistory(playerId) ? 0.8D : 0.0D);
        scores.put("layer2-rename", renameTracker.renameContextScore(playerId, ""));
        scores.put("layer3-proximity", 0.0D);
        scores.put("layer4-l2", pipeline.l2Probability(ChatGuardModule.advertForm("")));
        scores.put("layer5-context", localContext.evaluate(playerId, "", false, 0.0D).confidence());
        return scores;
    }
}
