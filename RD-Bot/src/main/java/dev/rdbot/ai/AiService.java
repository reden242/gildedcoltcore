package dev.rdbot.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rdbot.Config;
import dev.rdbot.kb.Chunk;
import dev.rdbot.kb.KnowledgeStore;
import dev.rdbot.store.Daos;
import dev.rdbot.store.Database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One question in, one ready-to-send display text out.
 *
 * <p>Retrieval, prompt assembly, the Groq call, JSON validation, the
 * escalation decision, the "Sources:" line, mention stripping, working-hours
 * gating and insight logging all live here, so Discord delivery and the panel
 * Test Chat share one code path and cannot drift.
 */
public final class AiService {

    private static final ObjectMapper M = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final Config config;
    private final Database db;
    private final GroqClient groq;
    private final KnowledgeStore store;

    // question cache: guild -> (normalized question, epochMillis, Result)
    private final Map<String, CachedAnswer> cache = new ConcurrentHashMap<>();
    // user cooldown: guild:user -> epochMillis of last reply
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    // ticket reply counts: channel -> replies sent (max replies per ticket)
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> channelReplies =
            new ConcurrentHashMap<>();

    /** True once the channel hit the per-ticket reply cap. */
    public boolean channelOverCap(String channelId, int cap) {
        java.util.concurrent.atomic.AtomicInteger count =
                channelReplies.computeIfAbsent(channelId, k -> new java.util.concurrent.atomic.AtomicInteger());
        if (channelReplies.size() > 5000) channelReplies.clear();
        return count.get() >= cap;
    }

    /** Call after the bot actually sends a reply in the channel. */
    public void countReply(String channelId) {
        channelReplies.computeIfAbsent(channelId, k -> new java.util.concurrent.atomic.AtomicInteger())
                .incrementAndGet();
    }

    private int effective(String guildId, String configKey, int fallback) {
        Object v = guildSettings(guildId).get("aiSettings");
        if (v instanceof Map<?, ?> map && map.get(shortKey(configKey)) instanceof Number n) {
            return n.intValue();
        }
        return fallback;
    }

    private static String shortKey(String dotted) {
        int dot = dotted.lastIndexOf('.');
        return dot >= 0 ? dotted.substring(dot + 1) : dotted;
    }

    public AiService(Config config, Database db, KeyPool pool, KnowledgeStore store) {
        this.config = config;
        this.db = db;
        this.store = store;
        this.groq = new GroqClient(
                config.str("ai.base-url", "https://api.groq.com/openai/v1"), pool,
                config.str("ai.model", "llama-3.1-8b-instant"),
                config.str("ai.fallback-model", "llama-3.3-70b-versatile"),
                config.dbl("ai.temperature", 0.2),
                config.intOf("ai.max-tokens", 700),
                config.intOf("ai.timeout-seconds", 30));
    }

    public record Result(AiResponse response, long insightId, boolean shouldAnswer,
                         String displayText, String escalationCategory, String extra) {
        /** Out-of-hours ping role id (empty when none). */
        public String outOfHoursRole() {
            return "out-of-hours".equals(escalationCategory) ? extra : "";
        }
    }

    public Result handle(String guildId, String userId, String userTag, String channelId,
                         String question, String history) {
        String clean = question == null ? "" : question.strip();
        if (clean.isEmpty() || clean.length() > config.intOf("ai.max-message-length", 1200)) {
            return decline(guildId, userId, channelId, question, "message too long");
        }

        // Working hours: outside the schedule there is no AI call at all.
        if (!workingHoursOpen(guildId)) {
            return outOfHours(guildId, userId, channelId, question);
        }

        // Per-user cooldown and per-ticket reply cap.
        long cooldown = config.intOf("ai.user-cooldown-seconds", 8) * 1000L;
        long last = cooldowns.getOrDefault(guildId + ":" + userId, 0L);
        if (System.currentTimeMillis() - last < cooldown) return null;
        cooldowns.put(guildId + ":" + userId, System.currentTimeMillis());

        int maxReplies = effective(guildId, "ai.max-replies-per-ticket",
                config.intOf("ai.max-replies-per-ticket", 0));
        if (maxReplies > 0 && channelOverCap(channelId, maxReplies)) {
            return forceEscalate(guildId, userId, userTag, channelId, clean, userTag + " hit the reply cap");
        }

        // Identical-question cache per guild.
        CachedAnswer cached = cache.get(guildId + ":" + normalize(clean));
        if (cached != null && System.currentTimeMillis() - cached.at()
                < config.intOf("ai.cache-seconds", 60) * 1000L) {
            return logDecision(guildId, userId, userTag, channelId, clean, cached.response());
        }

        // Forced escalation topics hit before any AI call (saves tokens).
        String forced = forcedTopic(clean, guildId);
        if (forced != null) {
            AiResponse r = new AiResponse(
                    "I'm escalating this to a team member who can help.",
                    0.35, true, AiResponse.CATEGORY_ACTION,
                    "Matched always-escalate topic: " + forced, clean, forced, List.of());
            return logDecision(guildId, userId, userTag, channelId, clean, r);
        }

        List<dev.rdbot.kb.Retriever.Hit> chunks = store.retrieve(guildId, clean, config.intOf("ai.top-k", 4));
        String system = new PromptBuilder(promptText(guildId), personalityOf(guildId),
                !"escalate".equals(unknownPolicy(guildId))).systemPrompt(chunks);
        String userMessage = history == null || history.isBlank()
                ? clean : "Recent ticket history:\n" + history + "\n\nCurrent question: " + clean;

        AiResponse response = groq.chat(guildId, system, userMessage);
        if ("external-knowledge".equals(unknownPolicy(guildId))) {
            // Permitted general-knowledge answer: still logged, still sourced.
        } else if (response.confidence() < threshold(guildId) && !response.isEscalation()) {
            response = new AiResponse("", response.confidence(), true,
                    AiResponse.CATEGORY_ACTION,
                    "Below the answer threshold (" + response.confidence()
                            + " < " + threshold(guildId) + ").",
                    response.summary(), response.suggestedTopic(), response.sources());
        }
        cache.put(guildId + ":" + normalize(clean),
                new CachedAnswer(System.currentTimeMillis(), response));
        return logDecision(guildId, userId, userTag, channelId, clean, response);
    }

    /* ---------------- decision + logging ---------------- */

    private Result logDecision(String guildId, String userId, String userTag, String channelId,
                               String question, AiResponse response) {
        boolean escalate = response.isEscalation() || response.confidence() < threshold(guildId);
        String text = escalate
                ? escalationText(response)
                : stripMentions(response.answer());
        String withSources = addSources(text, response, escalate);
        long insightId = Daos.addInsight(db, new Daos.Insight(0, guildId, userId, userTag,
                channelId, "", question, withSources, response.confidence(),
                response.category(), response.reason(), response.summary(),
                response.suggestedTopic(), response.sources(), escalate,
                System.currentTimeMillis()));
        if (escalate && AiResponse.CATEGORY_UNKNOWN.equals(response.category())
                && !"external-knowledge".equals(unknownPolicy(guildId))) {
            Daos.addGap(db, guildId, question, response.suggestedTopic(),
                    response.answer(), insightId, channelId);
        }
        return new Result(response, insightId, !escalate, withSources,
                escalate ? response.category() : null, "");
    }

    private static String escalationText(AiResponse response) {
        String a = response.answer();
        return a == null || a.isBlank()
                ? "I'm escalating this to a team member who can help."
                : stripMentions(a);
    }

    private String addSources(String text, AiResponse response, boolean escalate) {
        if (escalate || !config.bool("discord.cite-sources", true)) return text;
        if (response.sources().isEmpty()) return text;
        return text + "\n\nSources: " + String.join(", ", response.sources());
    }

    /** Neutralizes @everyone, @here and role/user mention syntax in AI output. */
    private static String stripMentions(String text) {
        if (text == null) return "";
        return text.replace("@everyone", "@\u200Beveryone")
                .replace("@here", "@\u200Bhere")
                .replaceAll("<@&\\d+>", "[role]")
                .replaceAll("<@!?\\d+>", "[user]");
    }

    private Result decline(String guildId, String userId, String channelId, String q, String why) {
        return new Result(AiResponse.aiUnavailable("Declined: " + why), -1, false, "", null, "");
    }

    private Result forceEscalate(String guildId, String userId, String userTag, String channelId,
                                 String question, String why) {
        AiResponse r = new AiResponse(
                "I'm escalating this to a team member who can help.", 0.3, true,
                AiResponse.CATEGORY_ACTION, why, question, null, List.of());
        return logDecision(guildId, userId, userTag, channelId, question, r);
    }

    private Result outOfHours(String guildId, String userId, String channelId, String question) {
        Map<String, Object> settings = guildSettings(guildId);
        Object wh = settings.get("workingHours");
        String offline = config.str("working-hours.offline-message",
                "We're outside working hours. A staff member will reply when we're back online.");
        if (wh instanceof Map<?, ?> map && map.get("offlineMessage") != null) {
            offline = String.valueOf(map.get("offlineMessage"));
        }
        String roleId = config.str("working-hours.out-of-hours-role-id", "");
        if (wh instanceof Map<?, ?> map && map.get("outOfHoursRoleId") != null) {
            roleId = String.valueOf(map.get("outOfHoursRoleId"));
        }
        return new Result(new AiResponse(offline, 0, true, AiResponse.CATEGORY_ACTION,
                "Outside working hours - no AI used.", question, null, List.of()),
                -1, true, offline, "out-of-hours", roleId);
    }

    /* ---------------- prompt, settings, policy ---------------- */

    /** prompt.md read from disk on every call: edits hot-reload for free. */
    public String promptText(String guildId) {
        Path file = Path.of(config.str("paths.prompt-file", "prompt.md"));
        String raw;
        try {
            raw = Files.readString(file);
        } catch (Exception e) {
            raw = "# Server\nUnconfigured.\n";
        }
        Map<String, Object> settings = guildSettings(guildId);
        Object placeholders = settings.get("placeholders");
        Map<String, String> values = new LinkedHashMap<>();
        if (placeholders instanceof Map<?, ?> map) {
            map.forEach((k, v) -> values.put(String.valueOf(k), String.valueOf(v)));
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            raw = raw.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return raw;
    }

    public Map<String, Object> guildSettings(String guildId) {
        return Daos.settings(db, guildId).orElseGet(Map::of);
    }

    public Map<String, Object> personalityOf(String guildId) {
        Object p = guildSettings(guildId).get("personality");
        if (p instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of("tone", "Friendly", "language", "Auto-detect", "detail", "balanced");
    }

    private String unknownPolicy(String guildId) {
        Object v = guildSettings(guildId).get("unknownPolicy");
        return v == null ? config.str("ai.unknown-topic-policy", "escalate") : String.valueOf(v);
    }

    private double threshold(String guildId) {
        Object v = guildSettings(guildId).get("escalationThreshold");
        if (v instanceof Number n) return Math.min(1.0, Math.max(0.0, n.doubleValue()));
        String preset = String.valueOf(
                guildSettings(guildId).getOrDefault("escalationPreset",
                        config.str("ai.escalation-preset", "balanced")));
        return ConfidencePolicy.fromPreset(preset).answerThreshold();
    }

    /** Line from the "Escalate to Staff" list that the question matches, if any. */
    private String forcedTopic(String question, String guildId) {
        Object topics = personalityOf(guildId).get("escalateTopics");
        if (!(topics instanceof String s) || s.isBlank()) return null;
        String lower = question.toLowerCase();
        for (String line : s.split("\n")) {
            String topic = line.strip().toLowerCase();
            if (topic.length() >= 3 && lower.contains(topic)) return line.strip();
        }
        return null;
    }

    private boolean workingHoursOpen(String guildId) {
        Map<String, Object> settings = guildSettings(guildId);
        String mode = String.valueOf(settings.getOrDefault("workingHoursMode",
                config.str("working-hours.mode", "always")));
        if (!"scheduled".equalsIgnoreCase(mode) && !"scheduled".equalsIgnoreCase(
                config.str("working-hours.mode", "always"))) {
            Object wh = settings.get("workingHours");
            if (!(wh instanceof Map<?, ?> map) || !"scheduled".equalsIgnoreCase(
                    String.valueOf(map.get("mode")))) return true;
        }
        ZoneId zone = ZoneId.of(config.str("working-hours.timezone", "UTC"));
        Object wh = settings.get("workingHours");
        Map<?, ?> sched = config.map("working-hours.schedule");
        if (wh instanceof Map<?, ?> map) {
            if (map.get("timezone") != null) {
                try { zone = ZoneId.of(String.valueOf(map.get("timezone"))); }
                catch (Exception ignored) { }
            }
            if (map.get("schedule") instanceof Map<?, ?> s) sched = s;
        }
        LocalDateTime now = LocalDateTime.now(zone);
        String weekday = String.valueOf((now.getDayOfWeek().getValue()) % 7); // 0 = Sunday
        Object day = sched.get(weekday);
        if (!(day instanceof List<?> ranges)) return false;
        LocalTime time = now.toLocalTime();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("H:mm");
        for (Object range : ranges) {
            if (!(range instanceof List<?> r) || r.size() < 2) continue;
            try {
                if (!time.isBefore(LocalTime.parse(String.valueOf(r.get(0)), fmt))
                        && time.isBefore(LocalTime.parse(String.valueOf(r.get(1)), fmt))) {
                    return true;
                }
            } catch (Exception ignored) { }
        }
        return false;
    }

    private static String normalize(String question) {
        return question.toLowerCase().replaceAll("\\s+", " ").strip();
    }

    private record CachedAnswer(long at, AiResponse response) { }
}
