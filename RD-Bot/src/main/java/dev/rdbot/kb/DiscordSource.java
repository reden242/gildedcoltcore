package dev.rdbot.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rdbot.store.Daos;
import dev.rdbot.store.Database;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discord knowledge: pulls readable messages from an FAQ/announcement channel
 * and indexes them. Incremental: stores the newest seen message id in the
 * entry meta and only pulls newer messages on re-sync.
 */
public final class DiscordSource {

    private static final ObjectMapper M = new ObjectMapper();

    private final Database db;
    private final KnowledgeStore store;

    public DiscordSource(Database db, KnowledgeStore store) {
        this.db = db;
        this.store = store;
    }

    public long add(String guildId, String channelId, String channelName) {
        Map<String, Object> meta = Map.of("channelId", channelId, "lastMessageId", "0");
        long id = Daos.addKnowledge(db, guildId, "discord", channelName, "",
                "discord:" + channelId, write(meta));
        Daos.replaceChunks(db, id, guildId, List.of());
        return id;
    }

    /** Pulls new messages from every discord source of every guild the bot is in. */
    public void syncAll(JDA jda) {
        for (var guild : jda.getGuilds()) {
            for (Daos.Entry entry : Daos.knowledge(db, guild.getId(), "discord")) {
                try {
                    syncEntry(guild.getTextChannelById(channelIdOf(entry)));
                } catch (Exception e) {
                    Daos.updateKnowledge(db, entry.id(), entry.topic(), entry.content(),
                            "{\"status\":\"error: " + e.getMessage().replace("\"", "'") + "\"}");
                }
            }
        }
    }

    /** Pulls new messages for one source entry. */
    public void syncEntry(TextChannel channel) {
        if (channel == null) return;
        List<Daos.Entry> entries = Daos.knowledge(db, channel.getGuild().getId(), "discord");
        Daos.Entry match = null;
        for (Daos.Entry e : entries) {
            if (channel.getId().equals(channelIdOf(e))) match = e;
        }
        if (match == null) return;

        String lastId = lastMessageIdOf(match);
        List<Message> fresh = new ArrayList<>();
        for (Message message : channel.getIterableHistory().cache(false).limit(300)) {
            if (message.getId().equals(lastId)) break;
            fresh.add(message);
        }
        java.util.Collections.reverse(fresh);
        List<Message> history = fresh.size() > 200 ? fresh.subList(fresh.size() - 200, fresh.size()) : fresh;
        if (history.isEmpty()) {
            Daos.touchKnowledgeSync(db, match.id());
            return;
        }
        history.sort((a, b) -> Long.compare(a.getTimeCreated().toInstant().toEpochMilli(),
                b.getTimeCreated().toInstant().toEpochMilli()));
        StringBuilder batch = new StringBuilder(match.content());
        long newest = 0;
        for (Message message : history) {
            if (message.getAuthor().isBot() || message.getContentRaw().isBlank()) continue;
            batch.append('\n').append(message.getAuthor().getName()).append(": ")
                    .append(message.getContentRaw().strip());
            newest = Math.max(newest, message.getIdLong());
        }
        String content = batch.toString().strip();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("channelId", channel.getId());
        meta.put("lastMessageId", String.valueOf(newest));
        meta.put("status", "synced");
        Daos.updateKnowledge(db, match.id(), match.topic(), content, write(meta));
        Daos.replaceChunks(db, match.id(), match.guildId(), store.split(content));
        Daos.touchKnowledgeSync(db, match.id());
    }

    public void refreshMetaOnly(Daos.Entry entry) {
        Daos.touchKnowledgeSync(db, entry.id());
    }

    private static String channelIdOf(Daos.Entry entry) {
        try {
            Object v = M.readValue(entry.meta(), Map.class).get("channelId");
            return v == null ? entry.url().replace("discord:", "") : String.valueOf(v);
        } catch (Exception e) {
            return entry.url().replace("discord:", "");
        }
    }

    private static String lastMessageIdOf(Daos.Entry entry) {
        try {
            Object v = M.readValue(entry.meta(), Map.class).get("lastMessageId");
            return v == null ? "0" : String.valueOf(v);
        } catch (Exception e) {
            return "0";
        }
    }

    private static String write(Map<String, Object> meta) {
        try {
            return M.writeValueAsString(meta);
        } catch (Exception e) {
            return "{}";
        }
    }

    public List<TextChannel> candidates(net.dv8tion.jda.api.entities.Guild guild) {
        return new ArrayList<>(guild.getTextChannels());
    }
}
