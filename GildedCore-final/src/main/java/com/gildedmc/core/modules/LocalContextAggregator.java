package com.gildedmc.core.modules;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Local stage-three aggregation over recent chat-filter text only. */
final class LocalContextAggregator {

    record Verdict(boolean block, boolean review, double confidence, String reason) {
        static Verdict pass() {
            return new Verdict(false, false, 0.0D, "context clear");
        }
    }

    private record Entry(String text, boolean addressSignal, long timestamp) { }

    private static final long WINDOW_MS = 300_000L;
    private static final int HISTORY_SIZE = 8;
    private static final Pattern INVITE = Pattern.compile(
            "\\b(join|come|visit|invite|server|ip|discord|free|ranks?)\\b");

    private final Map<UUID, Deque<Entry>> history = new ConcurrentHashMap<>();

    Verdict evaluate(UUID playerId, String rawText, boolean addressSignal, double modelProbability) {
        String normalized = ChatGuardModule.advertForm(rawText);
        Deque<Entry> entries = this.history.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
        double contextScore;
        String reason;
        synchronized (entries) {
            long now = System.currentTimeMillis();
            entries.removeIf(entry -> now - entry.timestamp > WINDOW_MS);
            int previousSignals = 0;
            double similarity = 0.0D;
            for (Entry entry : entries) {
                if (entry.addressSignal) previousSignals++;
                similarity = Math.max(similarity, similarity(entry.text, normalized));
            }
            boolean invited = INVITE.matcher(normalized).find();
            if (previousSignals >= 2) {
                contextScore = 1.0D;
                reason = previousSignals + " address signals in 5 minutes";
            } else if (previousSignals == 1 && similarity >= 0.70D) {
                contextScore = 0.90D;
                reason = "repeated similar address message";
            } else if (previousSignals == 1 && invited) {
                contextScore = 0.70D;
                reason = "second invited address";
            } else if (similarity >= 0.80D) {
                contextScore = 0.60D;
                reason = "similar recent message";
            } else if (addressSignal && invited) {
                contextScore = 0.45D;
                reason = "invitation with address";
            } else {
                contextScore = 0.0D;
                reason = "context clear";
            }
            entries.addLast(new Entry(normalized, addressSignal, now));
            while (entries.size() > HISTORY_SIZE) entries.removeFirst();
        }

        double model = modelProbability < 0.0D ? 0.50D : modelProbability;
        double pattern = addressSignal ? 1.0D : 0.0D;
        double confidence = 0.55D * model + 0.25D * pattern + 0.20D * contextScore;
        if (confidence >= 0.78D) return new Verdict(true, false, confidence, reason);
        if (confidence >= 0.60D) return new Verdict(false, true, confidence, reason);
        return Verdict.pass();
    }

    void clear(UUID playerId) {
        this.history.remove(playerId);
    }

    void clear() {
        this.history.clear();
    }

    private static double similarity(String left, String right) {
        if (left.equals(right)) return 1.0D;
        Set<String> a = trigrams(left);
        Set<String> b = trigrams(right);
        if (a.isEmpty() || b.isEmpty()) return 0.0D;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        return (2.0D * intersection.size()) / (a.size() + b.size());
    }

    private static Set<String> trigrams(String text) {
        Set<String> output = new HashSet<>();
        for (int index = 0; index + 3 <= text.length(); index++) {
            output.add(text.substring(index, index + 3));
        }
        return output;
    }
}
