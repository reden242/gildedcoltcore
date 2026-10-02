package dev.rdbot.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rdbot.Services;
import dev.rdbot.ai.AiService;
import dev.rdbot.kb.Chunk;
import dev.rdbot.store.Daos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON API behind the panel. Every handler here runs pre-authenticated by
 * {@link PanelServer}; the token never reaches this class.
 */
public final class ApiRoutes {

    private static final ObjectMapper M = new ObjectMapper();

    private final Services services;

    public ApiRoutes(Services services) {
        this.services = services;
    }

    public ApiReply route(String method, String path, Map<String, String> query,
                          Map<String, Object> body) {
        try {
            return switch (path) {
                case "/api/guilds" -> guilds();
                case "/api/settings" -> "GET".equals(method)
                        ? getSettings(query) : putSettings(body);
                case "/api/prompt" -> "GET".equals(method)
                        ? getPrompt(query) : putPrompt(body);
                case "/api/knowledge" -> switch (method) {
                    case "GET" -> listKnowledge(query);
                    case "POST" -> addKnowledge(body);
                    case "PUT" -> updateKnowledge(body);
                    case "DELETE" -> deleteKnowledge(query, body);
                    default -> ApiReply.error(405, "method not allowed");
                };
                case "/api/knowledge/export" -> exportKnowledge(query);
                case "/api/knowledge/import" -> importKnowledge(body);
                case "/api/insights" -> listInsights(query);
                case "/api/escalations" -> listEscalations(query);
                case "/api/escalations/archive" -> archiveEscalation(body);
                case "/api/gaps" -> listGaps(query);
                case "/api/gaps/add" -> addGap(body);
                case "/api/keys" -> keys();
                case "/api/test-chat" -> testChat(body);
                case "/api/channels" -> channels(query);
                case "/api/sources/web" -> addWeb(body);
                case "/api/sources/web/resync" -> resyncWeb(body);
                case "/api/sources/discord" -> addDiscord(body);
                case "/api/sources/discord/sync" -> syncDiscord(body);
                case "/api/documents" -> addDocument(body);
                case "/api/logout" -> ApiReply.ok(Map.of("ok", true));
                default -> ApiReply.error(404, "unknown api");
            };
        } catch (IllegalArgumentException e) {
            return ApiReply.error(400, e.getMessage());
        } catch (Exception e) {
            return ApiReply.error(500, "internal error");
        }
    }

    /* ---------------- guilds / settings / prompt ---------------- */

    private ApiReply guilds() {
        List<Map<String, Object>> out = new ArrayList<>();
        var jda = services.jda().get();
        if (jda != null) {
            for (var guild : jda.getGuilds()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", guild.getId());
                row.put("name", guild.getName());
                out.add(row);
            }
        }
        return ApiReply.ok(Map.of("guilds", out));
    }

    private ApiReply getSettings(Map<String, String> query) {
        String guild = requireGuild(query, null);
        Map<String, Object> merged = new LinkedHashMap<>(defaults());
        services.ai().guildSettings(guild).forEach(merged::put);
        return ApiReply.ok(Map.of("settings", merged));
    }

    @SuppressWarnings("unchecked")
    private ApiReply putSettings(Map<String, Object> body) {
        String guild = requireGuild(null, body);
        Object values = body.get("values");
        if (!(values instanceof Map<?, ?> map)) throw new IllegalArgumentException("values missing");
        Map<String, Object> current = new LinkedHashMap<>(services.ai().guildSettings(guild));
        map.forEach((k, v) -> current.put(String.valueOf(k), v));
        Daos.saveSettings(services.db(), guild, current);
        return ApiReply.ok(Map.of("ok", true));
    }

    private ApiReply getPrompt(Map<String, String> query) {
        String guild = requireGuild(query, null);
        return ApiReply.ok(Map.of("text", services.ai().promptText(guild)));
    }

    private ApiReply putPrompt(Map<String, Object> body) {
        String guild = requireGuild(null, body);
        Object text = body.get("text");
        if (!(text instanceof String s)) throw new IllegalArgumentException("text missing");
        try {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of(services.config().str("paths.prompt-file", "prompt.md")), s);
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot write prompt.md: " + e.getMessage());
        }
        Object placeholders = body.get("placeholders");
        if (placeholders instanceof Map<?, ?> map) {
            Map<String, Object> current = new LinkedHashMap<>(services.ai().guildSettings(guild));
            Map<String, Object> merged = new LinkedHashMap<>();
            Object existing = current.get("placeholders");
            if (existing instanceof Map<?, ?> em) em.forEach((k, v) -> merged.put(String.valueOf(k), v));
            map.forEach((k, v) -> merged.put(String.valueOf(k), String.valueOf(v)));
            current.put("placeholders", merged);
            Daos.saveSettings(services.db(), guild, current);
        }
        return ApiReply.ok(Map.of("ok", true));
    }

    private Map<String, Object> defaults() {
        var c = services.config();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unknownPolicy", c.str("ai.unknown-topic-policy", "escalate"));
        out.put("escalationPreset", c.str("ai.escalation-preset", "balanced"));
        out.put("afterEscalation", c.str("ai.after-escalation", "stop"));
        out.put("escalationRoleId", c.str("discord.escalation-role-id", ""));
        out.put("personality", Map.of("tone", "Friendly", "language", "Auto-detect", "detail", "balanced"));
        return out;
    }

    /* ---------------- knowledge ---------------- */

    private ApiReply listKnowledge(Map<String, String> query) {
        String guild = requireGuild(query, null);
        String kind = query.get("kind");
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : Daos.knowledge(services.db(), guild, kind)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.id());
            row.put("kind", e.kind());
            row.put("topic", e.topic());
            row.put("content", e.content().length() > 400
                    ? e.content().substring(0, 400) + "…" : e.content());
            row.put("url", e.url());
            row.put("meta", e.meta());
            row.put("createdAt", e.createdAt());
            row.put("syncedAt", e.syncedAt());
            out.add(row);
        }
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("custom", Daos.knowledgeCount(services.db(), guild, "custom"));
        usage.put("web", Daos.knowledgeCount(services.db(), guild, "web"));
        usage.put("discord", Daos.knowledgeCount(services.db(), guild, "discord"));
        usage.put("document", Daos.knowledgeCount(services.db(), guild, "document"));
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("custom", services.config().intOf("knowledge.limits.custom", 20));
        limits.put("web", services.config().intOf("knowledge.limits.web", 10));
        limits.put("discord", services.config().intOf("knowledge.limits.discord", 10));
        limits.put("document", services.config().intOf("knowledge.limits.files", 5));
        return ApiReply.ok(Map.of("items", out, "usage", usage, "limits", limits));
    }

    private ApiReply addKnowledge(Map<String, Object> body) {
        String guild = requireGuild(null, body);
        String topic = str(body, "topic");
        String info = str(body, "information");
        if (topic.isBlank() || topic.length() > 200) throw new IllegalArgumentException("topic 1-200 chars");
        if (info.isBlank() || info.length() > 5000) throw new IllegalArgumentException("information 1-5000 chars");
        int limit = services.config().intOf("knowledge.limits.custom", 20);
        if (Daos.knowledgeCount(services.db(), guild, "custom") >= limit) {
            throw new IllegalArgumentException("custom knowledge is full (" + limit + ")");
        }
        long id = services.store().addCustom(guild, topic, info);
        return ApiReply.ok(Map.of("id", id));
    }

    private ApiReply updateKnowledge(Map<String, Object> body) {
        long id = num(body, "id");
        var entry = Daos.knowledgeById(services.db(), id)
                .orElseThrow(() -> new IllegalArgumentException("unknown entry"));
        if (!"custom".equals(entry.kind())) throw new IllegalArgumentException("only custom entries are editable");
        String topic = str(body, "topic");
        String info = str(body, "information");
        if (topic.isBlank() || info.isBlank()) throw new IllegalArgumentException("topic and information required");
        Daos.updateKnowledge(services.db(), id, topic, info, entry.meta());
        services.store().reindex(entry.guildId(), id, topic, info);
        return ApiReply.ok(Map.of("ok", true));
    }

    private ApiReply deleteKnowledge(Map<String, String> query, Map<String, Object> body) {
        String idRaw = query.get("id");
        if (idRaw == null && body != null) idRaw = String.valueOf(body.getOrDefault("id", ""));
        long id = Long.parseLong(idRaw);
        services.store().remove(id);
        return ApiReply.ok(Map.of("ok", true));
    }

    private ApiReply exportKnowledge(Map<String, String> query) {
        String guild = requireGuild(query, null);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : Daos.knowledge(services.db(), guild, "custom")) {
            out.add(Map.<String, Object>of("topic", e.topic(), "information", e.content()));
        }
        return ApiReply.ok(Map.of("items", out));
    }

    @SuppressWarnings("unchecked")
    private ApiReply importKnowledge(Map<String, Object> body) {
        String guild = requireGuild(null, body);
        Object items = body.get("items");
        if (!(items instanceof List<?> list)) throw new IllegalArgumentException("items missing");
        int added = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String topic = opt(m, "topic").strip();
            String info = opt(m, "information").strip();
            if (topic.isBlank() || info.isBlank()) continue;
            services.store().addCustom(guild,
                    topic.substring(0, Math.min(200, topic.length())),
                    info.substring(0, Math.min(5000, info.length())));
            added++;
        }
        return ApiReply.ok(Map.of("added", added));
    }

    /* ---------------- insights / escalations / gaps ---------------- */

    private ApiReply listInsights(Map<String, String> query) {
        String guild = requireGuild(query, null);
        int limit = Math.min(100, Math.max(1, parseInt(query.get("limit"), 20)));
        int offset = Math.max(0, parseInt(query.get("offset"), 0));
        Boolean escalated = query.containsKey("escalated")
                ? Boolean.parseBoolean(query.get("escalated")) : null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (var i : Daos.insights(services.db(), guild, limit, offset, escalated)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i.id());
            row.put("user", i.userTag() + " (" + i.userId() + ")");
            row.put("channel", i.channelId());
            row.put("question", i.question());
            row.put("answer", i.answer());
            row.put("confidence", i.confidence());
            row.put("category", i.category());
            row.put("reason", i.reason());
            row.put("summary", i.summary());
            row.put("suggestedTopic", i.suggestedTopic());
            row.put("sources", i.sources());
            row.put("escalated", i.escalated());
            row.put("createdAt", i.createdAt());
            out.add(row);
        }
        return ApiReply.ok(Map.of("insights", out));
    }

    private ApiReply listEscalations(Map<String, String> query) {
        String guild = requireGuild(query, null);
        int limit = Math.min(100, Math.max(1, parseInt(query.get("limit"), 20)));
        int offset = Math.max(0, parseInt(query.get("offset"), 0));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : Daos.escalations(services.db(), guild, limit, offset)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.id());
            row.put("user", e.userTag() + " (" + e.userId() + ")");
            row.put("channel", e.channelId());
            row.put("category", e.category());
            row.put("reason", e.reason());
            row.put("summary", e.summary());
            row.put("confidence", e.confidence());
            row.put("status", e.status());
            row.put("createdAt", e.createdAt());
            out.add(row);
        }
        return ApiReply.ok(Map.of("escalations", out));
    }

    private ApiReply archiveEscalation(Map<String, Object> body) {
        String guild = requireGuild(null, body);
        long id = num(body, "id");
        if (!Daos.archiveEscalation(services.db(), guild, id)) {
            throw new IllegalArgumentException("unknown escalation");
        }
        // Lifts the "stop replying" lock for that channel.
        for (var e : Daos.escalations(services.db(), guild, 200, 0)) {
            if (e.id() == id) {
                Daos.unlockChannel(services.db(), e.channelId());
                break;
            }
        }
        return ApiReply.ok(Map.of("ok", true));
    }

    private ApiReply listGaps(Map<String, String> query) {
        String guild = requireGuild(query, null);
        int limit = Math.min(100, Math.max(1, parseInt(query.get("limit"), 20)));
        int offset = Math.max(0, parseInt(query.get("offset"), 0));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var g : Daos.gaps(services.db(), guild, limit, offset)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", g.id());
            row.put("question", g.question());
            row.put("topic", g.topic());
            row.put("answer", g.answer());
            row.put("status", g.status());
            row.put("createdAt", g.createdAt());
            out.add(row);
        }
        return ApiReply.ok(Map.of("gaps", out));
    }

    private ApiReply addGap(Map<String, Object> body) {
        String guild = requireGuild(null, body);
        long id = num(body, "id");
        var gap = Daos.gapById(services.db(), guild, id)
                .orElseThrow(() -> new IllegalArgumentException("unknown gap"));
        String topic = gap.topic().isBlank() ? "Gap #" + id : gap.topic();
        String content = gap.answer().isBlank() ? gap.question() : gap.answer();
        services.store().addCustom(guild, topic, content);
        Daos.markGap(services.db(), id, "added");
        return ApiReply.ok(Map.of("ok", true));
    }

    /* ---------------- keys / test chat / sources ---------------- */

    private ApiReply keys() {
        return ApiReply.ok(Map.of("keys", services.keys().stats()));
    }

    private ApiReply testChat(Map<String, Object> body) {
        String guild = requireGuild(null, body);
        String question = str(body, "question");
        if (question.isBlank()) throw new IllegalArgumentException("question required");
        AiService.Result result = services.ai().handle(guild, "panel", "panel-test",
                "panel-test", question, "");
        if (result == null) return ApiReply.error(429, "cooldown, try again");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answer", result.displayText());
        out.put("confidence", result.response().confidence());
        out.put("label", new dev.rdbot.ai.ConfidencePolicy(
                services.config().dbl("ai.confidence-high", 0.80),
                services.config().dbl("ai.confidence-medium", 0.50), 0).label(
                result.response().confidence()));
        out.put("category", result.response().category());
        out.put("reason", result.response().reason());
        out.put("summary", result.response().summary());
        out.put("suggestedTopic", result.response().suggestedTopic());
        out.put("sources", result.response().sources());
        out.put("escalated", result.escalationCategory() != null);
        return ApiReply.ok(out);
    }

    private ApiReply channels(Map<String, String> query) {
        String guild = requireGuild(query, null);
        List<Map<String, Object>> out = new ArrayList<>();
        var jda = services.jda().get();
        if (jda != null) {
            var g = jda.getGuildById(guild);
            if (g != null) {
                for (var channel : g.getTextChannels()) {
                    out.add(Map.of("id", channel.getId(), "name", "#" + channel.getName(),
                            "topic", channel.getTopic() == null ? "" : channel.getTopic()));
                }
            }
        }
        return ApiReply.ok(Map.of("channels", out));
    }

    private ApiReply addWeb(Map<String, Object> body) {
        String guild = requireGuild(null, body);
        String url = str(body, "url");
        int limit = services.config().intOf("knowledge.limits.web", 10);
        if (Daos.knowledgeCount(services.db(), guild, "web") >= limit) {
            throw new IllegalArgumentException("web sources are full (" + limit + ")");
        }
        try {
            long id = services.web().add(guild, url);
            return ApiReply.ok(Map.of("id", id));
        } catch (Exception e) {
            throw new IllegalArgumentException("fetch failed: " + e.getMessage());
        }
    }

    private ApiReply resyncWeb(Map<String, Object> body) {
        long id = num(body, "id");
        try {
            services.web().resync(id);
        } catch (Exception e) {
            throw new IllegalArgumentException("re-sync failed: " + e.getMessage());
        }
        return ApiReply.ok(Map.of("ok", true));
    }

    private ApiReply addDiscord(Map<String, Object> body) {
        String guild = requireGuild(null, body);
        String channelId = str(body, "channelId");
        if (channelId.isBlank()) throw new IllegalArgumentException("channelId required");
        int limit = services.config().intOf("knowledge.limits.discord", 10);
        if (Daos.knowledgeCount(services.db(), guild, "discord") >= limit) {
            throw new IllegalArgumentException("discord sources are full (" + limit + ")");
        }
        String name = str(body, "name");
        long id = services.discord().add(guild, channelId, name.isBlank() ? channelId : name);
        var jda = services.jda().get();
        if (jda != null) {
            var channel = jda.getTextChannelById(channelId);
            if (channel != null) {
                try {
                    services.discord().syncEntry(channel);
                } catch (Exception e) {
                    return ApiReply.ok(Map.of("id", id, "warning", "added, first sync failed: " + e.getMessage()));
                }
            }
        }
        return ApiReply.ok(Map.of("id", id));
    }

    private ApiReply syncDiscord(Map<String, Object> body) {
        String guild = requireGuild(null, body);
        long id = num(body, "id");
        var entry = Daos.knowledgeById(services.db(), id)
                .orElseThrow(() -> new IllegalArgumentException("unknown source"));
        var jda = services.jda().get();
        if (jda == null) throw new IllegalArgumentException("bot offline");
        String channelId = entry.url().replace("discord:", "");
        var channel = jda.getTextChannelById(channelId);
        if (channel == null) throw new IllegalArgumentException("channel not visible to the bot");
        try {
            services.discord().syncEntry(channel);
        } catch (Exception e) {
            throw new IllegalArgumentException("sync failed: " + e.getMessage());
        }
        return ApiReply.ok(Map.of("ok", true));
    }

    private ApiReply addDocument(Map<String, Object> body) {
        String guild = requireGuild(null, body);
        String name = str(body, "name");
        String base64 = str(body, "data");
        if (name.isBlank() || base64.isBlank()) throw new IllegalArgumentException("name and data required");
        int limit = services.config().intOf("knowledge.limits.files", 5);
        if (Daos.knowledgeCount(services.db(), guild, "document") >= limit) {
            throw new IllegalArgumentException("document sources are full (" + limit + ")");
        }
        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(base64);
        } catch (Exception e) {
            throw new IllegalArgumentException("data is not valid base64");
        }
        if (bytes.length > 12 * 1024 * 1024) throw new IllegalArgumentException("file too large (12 MB max)");
        try {
            long id = services.documents().add(guild, name, bytes);
            return ApiReply.ok(Map.of("id", id));
        } catch (Exception e) {
            throw new IllegalArgumentException("document failed: " + e.getMessage());
        }
    }

    /* ---------------- helpers ---------------- */

    private static String requireGuild(Map<String, String> query, Map<String, Object> body) {
        String guild = query != null ? query.getOrDefault("guild", "") : "";
        if (guild.isBlank() && body != null) guild = String.valueOf(body.getOrDefault("guild", ""));
        if (guild.isBlank()) throw new IllegalArgumentException("guild required");
        return guild;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? "" : String.valueOf(v).strip();
    }

    private static String opt(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static long num(Map<String, Object> body, String key) {
        try {
            return Long.parseLong(str(body, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number");
        }
    }

    private static int parseInt(String raw, int def) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception e) {
            return def;
        }
    }

    /** {status, json} for the HTTP layer. */
    public record ApiReply(int status, Map<String, Object> json) {
        public static ApiReply ok(Map<String, Object> json) {
            return new ApiReply(200, json);
        }

        public static ApiReply error(int status, String message) {
            return new ApiReply(status, Map.of("ok", false, "error", message));
        }

        public String serialize() {
            try {
                return M.writeValueAsString(json);
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"encode\"}";
            }
        }
    }
}
