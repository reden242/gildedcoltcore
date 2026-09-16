package com.gildedmc.core.modules;

import java.util.Map;

/**
 * Asks whether the evidence for a model's verdict is actually in the message.
 *
 * <h2>The problem this fixes</h2>
 * The classifier is a bag of character n-grams. It is good at "this looks like
 * the offences I was trained on" and it has no notion of whether the thing being
 * alleged is present. Measured on real chat, it scored {@code imma leave} as hate
 * at 100% and {@code i would be mad to} as hate at 100%. Both were then muted,
 * because a verdict was treated as a decision.
 *
 * <p>Nothing in those sentences is a slur. That is a fact about the text, and it
 * is checkable, and checking it costs microseconds.
 *
 * <h2>The question it asks</h2>
 * For each label there is one thing that must be there:
 *
 * <ul>
 *   <li><b>advertising</b> — an address. A domain, an IP, a host:port, or a
 *       dotless hostname. An advert with nowhere to go is not an advert.</li>
 *   <li><b>hate</b> — a slur, spelled or sounded. The literal and phonetic
 *       layers have already run by the time the model is consulted, so if they
 *       found nothing then the model is claiming a slur that is not written
 *       anywhere in the message.</li>
 *   <li><b>nsfw</b> — a sexual term, on the same reasoning.</li>
 * </ul>
 *
 * <h2>"Is it X or Y"</h2>
 * When the top two labels are close, the model has not really decided, and the
 * runner-up may be the one the message actually supports. So the two are
 * compared on evidence rather than on score: if the top label has none and the
 * runner-up has some, the runner-up wins. That is the difference between a
 * classifier and a decision.
 *
 * <h2>Why unsupported is not the same as clean</h2>
 * A verdict with no evidence is not proof of innocence — it is proof that this
 * layer cannot tell. Novel phrasing is exactly what the model exists to catch,
 * and demanding a known slur would make it unable to. So an unsupported verdict
 * is not thrown away; it is handed to a human, with no punishment attached.
 * Nobody gets muted for a sentence in which nothing prohibited appears, and
 * nothing that looked wrong is silently dropped.
 */
public final class ContextCheck {

    private ContextCheck() { }

    /** What the caller should do. */
    public enum Action {
        /** Evidence found. Apply the ladder. */
        PUNISH,
        /** No evidence, but the model is confident. Tell staff, punish nobody. */
        REVIEW,
        /** No evidence and no confidence. Treat as clean. */
        DROP
    }

    public record Result(Action action, String label, String reason) { }

    /**
     * What the deterministic layers found in the message.
     *
     * <p>All of these are already computed by the time the model is consulted, so
     * this record costs nothing to fill.
     */
    public record Evidence(boolean address, boolean slur, boolean sexual,
                           boolean aimedAtSomeone, int length) { }

    /**
     * Score gap below which the top two labels are treated as undecided.
     *
     * <p>At a gap this small the model is not expressing a preference, it is
     * expressing noise, and picking the higher number is arbitrary.
     */
    private static final double CLOSE = 0.15D;

    /** Confidence below which an unsupported verdict is not worth a human's time. */
    private static final double REVIEW_FLOOR = 0.90D;

    /** Text shorter than this carries too few n-grams to be worth reviewing. */
    private static final int REVIEW_MIN_LENGTH = 12;

    public static Result assess(String label, double confidence,
                                Map<String, Double> scores, Evidence evidence) {
        if (label == null || label.equals(LocalAiModule.LABEL_CLEAN)) {
            return new Result(Action.DROP, LocalAiModule.LABEL_CLEAN, "clean");
        }

        String chosen = label;
        String reason = null;

        // Is it X or Y? Only asked when the two are genuinely close, and only
        // answered when the answer is unambiguous - the top has nothing behind
        // it and the runner-up does.
        String runnerUp = runnerUp(scores, label);
        if (runnerUp != null && !supported(label, evidence)) {
            double gap = confidence - scores.getOrDefault(runnerUp, 0.0D);
            if (gap <= CLOSE && supported(runnerUp, evidence)) {
                chosen = runnerUp;
                reason = "reassigned from " + label + " (no evidence) to " + runnerUp;
            }
        }

        if (supported(chosen, evidence)) {
            return new Result(Action.PUNISH, chosen,
                    reason != null ? reason : evidenceName(chosen) + " present");
        }

        // Nothing to point at. A human decides, or nobody does.
        if (confidence >= REVIEW_FLOOR && evidence.length() >= REVIEW_MIN_LENGTH) {
            return new Result(Action.REVIEW, chosen,
                    "no " + evidenceName(chosen) + " in the message");
        }
        return new Result(Action.DROP, chosen,
                "no " + evidenceName(chosen) + " and not confident");
    }

    private static boolean supported(String label, Evidence e) {
        if (label == null) return false;
        return switch (label) {
            case ChatGuardModule.CAT_ADVERT, ChatGuardModule.CAT_LIGHT_ADVERT -> e.address();
            case ChatGuardModule.CAT_HATE -> e.slur() || (e.sexual() && e.aimedAtSomeone());
            // A label with no evidence rule defined is not a label this check
            // can speak to, so it does not block it.
            default -> true;
        };
    }

    private static String evidenceName(String label) {
        if (label == null) return "evidence";
        return switch (label) {
            case ChatGuardModule.CAT_ADVERT, ChatGuardModule.CAT_LIGHT_ADVERT -> "address";
            case ChatGuardModule.CAT_HATE -> "slur";
            default -> "evidence";
        };
    }

    private static String runnerUp(Map<String, Double> scores, String top) {
        if (scores == null) return null;
        String best = null;
        double bestScore = -1.0D;
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            if (e.getKey().equals(top)) continue;
            if (e.getKey().equals(LocalAiModule.LABEL_CLEAN)) continue;
            if (e.getValue() > bestScore) { bestScore = e.getValue(); best = e.getKey(); }
        }
        return best;
    }
}
