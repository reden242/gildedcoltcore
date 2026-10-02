package dev.rdbot.ai;

import java.util.List;

/**
 * Strict AI output contract. Every field in the spec's JSON shape, parsed and
 * validated by {@link ResponseParser}.
 */
public record AiResponse(
        String answer,
        double confidence,
        boolean needsStaff,
        String category,
        String reason,
        String summary,
        String suggestedTopic,
        List<String> sources) {

    public static final String CATEGORY_ANSWERED = "answered";
    public static final String CATEGORY_ACTION = "action required";
    public static final String CATEGORY_UNKNOWN = "unknown topic";
    public static final String CATEGORY_RULE = "rule violation";

    /** Unparseable model output: always escalate, never guess. */
    public static AiResponse parseFailure() {
        return new AiResponse("", 0.20, true, CATEGORY_ACTION,
                "Model output could not be parsed as the required JSON.",
                "Unreadable AI reply", null, List.of());
    }

    /** Escalation with no model answer, used by the "AI unavailable" path. */
    public static AiResponse aiUnavailable(String reason) {
        return new AiResponse("", 0.10, true, CATEGORY_ACTION, reason,
                "AI service unavailable", null, List.of());
    }

    public boolean isEscalation() {
        return needsStaff || !CATEGORY_ANSWERED.equals(category);
    }

    /** High >= 0.80, medium >= 0.50, low below that (policy can override). */
    public String confidenceLabel() {
        if (confidence >= 0.80) return "high";
        if (confidence >= 0.50) return "medium";
        return "low";
    }
}
