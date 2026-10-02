package dev.rdbot.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * All statement-level persistence in one place. Everything goes through
 * {@link Database#tx} so JDA threads and panel threads never interleave.
 *
 * ponytail: one Daos class instead of five DAO files - same call sites,
 * split only if the file becomes unreadable.
 */
public final class Daos {

    public static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    private Daos() { }

    /** Jackson's checked exceptions become unchecked: call sites are lambdas. */
    public static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("json encode", e);
        }
    }

    public static <T> T fromJson(String raw, TypeReference<T> type) {
        try {
            return JSON.readValue(raw, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("json decode", e);
        }
    }

    /* ---------------- settings (per guild JSON blob) ---------------- */

    public static Optional<Map<String, Object>> settings(Database db, String guildId) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT json FROM settings WHERE guild_id=?")) {
                ps.setString(1, guildId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(fromJson(rs.getString(1), MAP));
                }
            }
        });
    }

    public static void saveSettings(Database db, String guildId, Map<String, Object> values) {
        String json = toJson(values);
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO settings(guild_id,json,updated_at) VALUES(?,?,?) "
                    + "ON CONFLICT(guild_id) DO UPDATE SET json=excluded.json, updated_at=excluded.updated_at")) {
                ps.setString(1, guildId);
                ps.setString(2, json);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /* ---------------- knowledge ---------------- */

    public record Entry(long id, String guildId, String kind, String topic,
                        String content, String url, String meta, long createdAt, long syncedAt) { }

    public static List<Entry> knowledge(Database db, String guildId, String kind) {
        return db.tx(c -> {
            String sql = kind == null
                    ? "SELECT id,guild_id,kind,topic,content,url,meta,created_at,synced_at FROM knowledge WHERE guild_id=? ORDER BY id"
                    : "SELECT id,guild_id,kind,topic,content,url,meta,created_at,synced_at FROM knowledge WHERE guild_id=? AND kind=? ORDER BY id";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, guildId);
                if (kind != null) ps.setString(2, kind);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Entry> out = new ArrayList<>();
                    while (rs.next()) out.add(readEntry(rs));
                    return out;
                }
            }
        });
    }

    public static Optional<Entry> knowledgeById(Database db, long id) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id,guild_id,kind,topic,content,url,meta,created_at,synced_at FROM knowledge WHERE id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(readEntry(rs)) : Optional.empty();
                }
            }
        });
    }

    public static long addKnowledge(Database db, String guildId, String kind, String topic,
                                    String content, String url, String meta) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO knowledge(guild_id,kind,topic,content,url,meta,created_at,synced_at) VALUES(?,?,?,?,?,?,?,0)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, guildId);
                ps.setString(2, kind);
                ps.setString(3, topic);
                ps.setString(4, content);
                ps.setString(5, url);
                ps.setString(6, meta == null ? "{}" : meta);
                ps.setLong(7, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    public static void updateKnowledge(Database db, long id, String topic, String content, String meta) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE knowledge SET topic=?, content=?, meta=? WHERE id=?")) {
                ps.setString(1, topic);
                ps.setString(2, content);
                ps.setString(3, meta == null ? "{}" : meta);
                ps.setLong(4, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public static void touchKnowledgeSync(Database db, long id) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE knowledge SET synced_at=? WHERE id=?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public static void removeKnowledge(Database db, long id) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM knowledge WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public static int knowledgeCount(Database db, String guildId, String kind) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM knowledge WHERE guild_id=? AND kind=?")) {
                ps.setString(1, guildId);
                ps.setString(2, kind);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    public static void replaceChunks(Database db, long entryId, String guildId, List<String> chunks) {
        db.tx(c -> {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM knowledge_chunks WHERE entry_id=?")) {
                del.setLong(1, entryId);
                del.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO knowledge_chunks(entry_id,guild_id,idx,text) VALUES(?,?,?,?)")) {
                for (int i = 0; i < chunks.size(); i++) {
                    ps.setLong(1, entryId);
                    ps.setString(2, guildId);
                    ps.setInt(3, i);
                    ps.setString(4, chunks.get(i));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    public static java.util.List<dev.rdbot.kb.Chunk> chunks(Database db, String guildId) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ch.entry_id, k.topic, k.kind, ch.idx, ch.text "
                    + "FROM knowledge_chunks ch JOIN knowledge k ON k.id=ch.entry_id "
                    + "WHERE ch.guild_id=? ORDER BY ch.entry_id, ch.idx")) {
                ps.setString(1, guildId);
                try (ResultSet rs = ps.executeQuery()) {
                    java.util.List<dev.rdbot.kb.Chunk> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new dev.rdbot.kb.Chunk(rs.getLong(1), rs.getString(2), rs.getString(3),
                                rs.getInt(4), rs.getString(5)));
                    }
                    return out;
                }
            }
        });
    }

    private static Entry readEntry(ResultSet rs) throws java.sql.SQLException {
        return new Entry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getLong(9));
    }

    /* ---------------- insights ---------------- */

    public record Insight(long id, String guildId, String userId, String userTag, String channelId,
                          String messageId, String question, String answer, double confidence,
                          String category, String reason, String summary, String suggestedTopic,
                          List<String> sources, boolean escalated, long createdAt) { }

    public static long addInsight(Database db, Insight i) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO insights(guild_id,user_id,user_tag,channel_id,message_id,question,"
                    + "answer,confidence,category,reason,summary,suggested_topic,sources,escalated,created_at) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, i.guildId());
                ps.setString(2, i.userId());
                ps.setString(3, i.userTag());
                ps.setString(4, i.channelId());
                ps.setString(5, i.messageId());
                ps.setString(6, i.question());
                ps.setString(7, i.answer());
                ps.setDouble(8, i.confidence());
                ps.setString(9, i.category());
                ps.setString(10, i.reason());
                ps.setString(11, i.summary());
                ps.setString(12, i.suggestedTopic());
                ps.setString(13, toJson(i.sources()));
                ps.setInt(14, i.escalated() ? 1 : 0);
                ps.setLong(15, i.createdAt());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    public static List<Insight> insights(Database db, String guildId, int limit, int offset,
                                         Boolean escalated) {
        return db.tx(c -> {
            String sql = "SELECT id,guild_id,user_id,user_tag,channel_id,message_id,question,answer,"
                    + "confidence,category,reason,summary,suggested_topic,sources,escalated,created_at "
                    + "FROM insights WHERE guild_id=?"
                    + (escalated == null ? "" : " AND escalated=?")
                    + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int n = 1;
                ps.setString(n++, guildId);
                if (escalated != null) ps.setBoolean(n++, escalated);
                ps.setInt(n++, limit);
                ps.setInt(n, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Insight> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new Insight(rs.getLong(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                                rs.getString(8), rs.getDouble(9), rs.getString(10), rs.getString(11),
                                rs.getString(12), rs.getString(13),
                                fromJson(rs.getString(14), STRINGS),
                                rs.getInt(15) == 1, rs.getLong(16)));
                    }
                    return out;
                }
            }
        });
    }

    /* ---------------- escalations ---------------- */

    public record Escalation(long id, String guildId, long insightId, String userId, String userTag,
                             String channelId, String category, String reason, String summary,
                             double confidence, String status, long createdAt, long handledAt) { }

    public static long addEscalation(Database db, String guildId, long insightId, String userId,
                                     String userTag, String channelId, String category,
                                     String reason, String summary, double confidence) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO escalations(guild_id,insight_id,user_id,user_tag,channel_id,category,"
                    + "reason,summary,confidence,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,'open',?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, guildId);
                ps.setLong(2, insightId);
                ps.setString(3, userId);
                ps.setString(4, userTag);
                ps.setString(5, channelId);
                ps.setString(6, category);
                ps.setString(7, reason);
                ps.setString(8, summary);
                ps.setDouble(9, confidence);
                ps.setLong(10, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    public static List<Escalation> escalations(Database db, String guildId, int limit, int offset) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id,guild_id,insight_id,user_id,user_tag,channel_id,category,reason,summary,"
                    + "confidence,status,created_at,COALESCE(handled_at,0) FROM escalations "
                    + "WHERE guild_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, guildId);
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Escalation> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new Escalation(rs.getLong(1), rs.getString(2), rs.getLong(3),
                                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                                rs.getString(8), rs.getString(9), rs.getDouble(10), rs.getString(11),
                                rs.getLong(12), rs.getLong(13)));
                    }
                    return out;
                }
            }
        });
    }

    public static boolean archiveEscalation(Database db, String guildId, long id) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE escalations SET status='archived', handled_at=? WHERE id=? AND guild_id=?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setLong(2, id);
                ps.setString(3, guildId);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /* ---------------- knowledge gaps ---------------- */

    public record Gap(long id, String guildId, String question, String topic, String answer,
                      long insightId, String channelId, String status, long createdAt) { }

    public static long addGap(Database db, String guildId, String question, String suggestedTopic,
                              String suggestedAnswer, long insightId, String channelId) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO gaps(guild_id,question,suggested_topic,suggested_answer,insight_id,channel_id,status,created_at) "
                    + "VALUES(?,?,?,?,?,?,'open',?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, guildId);
                ps.setString(2, question);
                ps.setString(3, suggestedTopic);
                ps.setString(4, suggestedAnswer);
                ps.setLong(5, insightId);
                ps.setString(6, channelId == null ? "" : channelId);
                ps.setLong(7, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    public static Optional<Gap> gapById(Database db, String guildId, long id) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id,guild_id,question,COALESCE(suggested_topic,''),suggested_answer,"
                    + "COALESCE(insight_id,0),COALESCE(channel_id,''),status,created_at "
                    + "FROM gaps WHERE id=? AND guild_id=?")) {
                ps.setLong(1, id);
                ps.setString(2, guildId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(new Gap(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7),
                            rs.getString(8), rs.getLong(9)));
                }
            }
        });
    }

    public static List<Gap> gaps(Database db, String guildId, int limit, int offset) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id,guild_id,question,COALESCE(suggested_topic,''),suggested_answer,"
                    + "COALESCE(insight_id,0),COALESCE(channel_id,''),status,created_at "
                    + "FROM gaps WHERE guild_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, guildId);
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Gap> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new Gap(rs.getLong(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7),
                                rs.getString(8), rs.getLong(9)));
                    }
                    return out;
                }
            }
        });
    }

    public static void markGap(Database db, long id, String status) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE gaps SET status=? WHERE id=?")) {
                ps.setString(1, status);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /* ---------------- guild -> key assignment ---------------- */

    public static Optional<Integer> keyAssignment(Database db, String guildId) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT key_index FROM key_assignments WHERE guild_id=?")) {
                ps.setString(1, guildId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getInt(1)) : Optional.empty();
                }
            }
        });
    }

    public static void saveKeyAssignment(Database db, String guildId, int keyIndex) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO key_assignments(guild_id,key_index,updated_at) VALUES(?,?,?) "
                    + "ON CONFLICT(guild_id) DO UPDATE SET key_index=excluded.key_index, updated_at=excluded.updated_at")) {
                ps.setString(1, guildId);
                ps.setInt(2, keyIndex);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /* ---------------- channel locks (stop replying after escalation) ---------------- */

    public static void lockChannel(Database db, String channelId, String userId, String reason) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO channel_locks(channel_id,user_id,reason,created_at) VALUES(?,?,?,?) "
                    + "ON CONFLICT(channel_id) DO UPDATE SET user_id=excluded.user_id, reason=excluded.reason, created_at=excluded.created_at")) {
                ps.setString(1, channelId);
                ps.setString(2, userId);
                ps.setString(3, reason);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public static boolean isLocked(Database db, String channelId, String userId) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT user_id FROM channel_locks WHERE channel_id=?")) {
                ps.setString(1, channelId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    String locked = rs.getString(1);
                    return locked == null || locked.isEmpty() || locked.equals(userId);
                }
            }
        });
    }

    public static void unlockChannel(Database db, String channelId) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM channel_locks WHERE channel_id=?")) {
                ps.setString(1, channelId);
                ps.executeUpdate();
            }
            return null;
        });
    }
}

