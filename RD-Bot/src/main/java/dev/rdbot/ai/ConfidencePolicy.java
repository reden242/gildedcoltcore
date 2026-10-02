package dev.rdbot.ai;

/** Escalation thresholds. Panel presets write into these three numbers. */
public record ConfidencePolicy(double high, double medium, double answerThreshold) {

    /** conservative 0.85 / balanced 0.65 / autonomous 0.45 (spec example mapping). */
    public static ConfidencePolicy fromPreset(String preset) {
        return switch (preset == null ? "balanced" : preset.toLowerCase()) {
            case "conservative" -> new ConfidencePolicy(0.90, 0.65, 0.85);
            case "autonomous" -> new ConfidencePolicy(0.75, 0.40, 0.45);
            default -> new ConfidencePolicy(0.80, 0.50, 0.65);
        };
    }

    public String label(double confidence) {
        if (confidence >= high) return "high";
        if (confidence >= medium) return "medium";
        return "low";
    }

    /** Below this the bot must escalate instead of answering. */
    public boolean canAnswer(double confidence) {
        return confidence >= answerThreshold;
    }
}
