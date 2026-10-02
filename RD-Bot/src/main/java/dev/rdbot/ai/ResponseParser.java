package dev.rdbot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates and repairs the model's JSON into {@link AiResponse}.
 * Anything unusable becomes an escalation - never a blank or invented reply.
 */
public final class ResponseParser {

    private static final ObjectMapper M = new ObjectMapper();

    private ResponseParser() { }

    public static AiResponse parseOrEscalate(String raw) {
        try {
            JsonNode node = M.readTree(extract(raw));
            String category = text(node, "category");
            if (category.isBlank()) category = AiResponse.CATEGORY_UNKNOWN;
            double confidence = node.path("confidence").asDouble(0);
            if (Double.isNaN(confidence) || confidence < 0) confidence = 0;
            if (confidence > 1) confidence = confidence > 1.5 ? confidence / 100.0 : 1;
            confidence = Math.min(1.0, confidence);
            boolean needsStaff = node.path("needs_staff").asBoolean(false);
            List<String> sources = new ArrayList<>();
            if (node.path("sources").isArray()) {
                node.path("sources").forEach(s -> sources.add(s.asText()));
            }
            AiResponse response = new AiResponse(
                    text(node, "answer"),
                    confidence,
                    needsStaff,
                    category,
                    text(node, "reason"),
                    text(node, "summary"),
                    node.path("suggested_topic").isNull() || text(node, "suggested_topic").isBlank()
                            ? null : text(node, "suggested_topic"),
                    sources);
            if (response.answer().isBlank() && !response.isEscalation()) {
                // An empty answer with no escalation would drop the user's
                // message silently: escalate instead.
                return escalate(response);
            }
            return response;
        } catch (Exception e) {
            return AiResponse.parseFailure();
        }
    }

    private static AiResponse escalate(AiResponse r) {
        return new AiResponse("", Math.min(r.confidence(), 0.30), true,
                AiResponse.CATEGORY_ACTION, "Model returned an empty answer without escalation.",
                r.summary(), r.suggestedTopic(), r.sources());
    }

    /** Pulls the first JSON object out of a possibly chatty model reply. */
    private static String extract(String raw) {
        if (raw == null) return "{}";
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return "{}";
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : (v.isMissingNode() || v.isNull() ? "" : v.toString());
    }
}
