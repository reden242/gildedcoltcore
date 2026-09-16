package com.gildedmc.core.modules;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offence history, the escalation ladder, and mute enforcement.
 *
 * <h2>Why mutes are enforced here rather than by a command</h2>
 * A ladder that ends in "permanent IP mute" is worthless if the last rung is a
 * console command to a punishment plugin that may not be installed, may name
 * its command something else, or may have no IP-scoped mute at all. So the mute
 * is a row in this store and it is enforced by cancelling the event. A command
 * template can still be configured on top — it runs as well, not instead, so
 * your existing punishment plugin keeps its own record.
 *
 * <h2>The ladder</h2>
 * Each category ("hate", "advertising", ...) has an ordered list of rungs in
 * config. The rung used is the number of prior offences the player already has
 * in that category inside the ladder window; past the end of the list the last
 * rung repeats. Advertising ships as a one-rung ladder, which is what makes it
 * an instant IP mute.
 *
 * <h2>Active mutes live in memory</h2>
 * Chat is on the async thread and runs for every message; hitting SQLite there
 * would put database latency in the chat path. Active mutes are small, so they
 * are all loaded at startup and the maps are updated on every write.
 */
public final class MuteStore {

    /** until &lt; 0 means permanent. */
    public record Mute(long id, String uuid, String name, String ip,
                       long until, String reason, String source) {

        public boolean permanent() { return this.until < 0L; }

        public boolean active(long now) { return this.until < 0L || this.until > now; }

        public boolean ipWide() { return this.ip != null && !this.ip.isBlank(); }
    }

    /** One rung: what to do and for how long. */
    public record Rung(String action, long durationMs) {

        public boolean permanent() { return this.durationMs < 0L; }
    }

    private static final Pattern DURATION =
            Pattern.compile("^(\\d{1,6})\\s*([smhdw])$", Pattern.CASE_INSENSITIVE);

    private final JavaPlugin plugin;
    private Connection db;

    /** uuid -> mute, for account-scoped mutes. */
    private final Map<String, Mute> byUuid = new ConcurrentHashMap<>();
    /** lowercase name -> mute, for players we only know by name. */
    private final Map<String, Mute> byName = new ConcurrentHashMap<>();
    /** ip -> mute, for IP-scoped mutes. */
    private final Map<String, Mute> byIp = new ConcurrentHashMap<>();

    private final Map<String, List<Rung>> ladders = new LinkedHashMap<>();
    private long ladderWindowMs = 30L * 86_400_000L;

    public MuteStore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /* ------------------------------------------------------------------ */
    /*  Lifecycle                                                         */
    /* ------------------------------------------------------------------ */

    public boolean enable() {
        File f = new File(this.plugin.getDataFolder(), "textguard.db");
        if (!f.getParentFile().exists() && !f.getParentFile().mkdirs()) return false;
        try {
            this.db = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
            try (Statement st = this.db.createStatement()) {
                st.executeUpdate("PRAGMA journal_mode=WAL");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS mutes ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "uuid TEXT, name TEXT NOT NULL, ip TEXT,"
                        + "until INTEGER NOT NULL, reason TEXT, source TEXT,"
                        + "revoked INTEGER NOT NULL DEFAULT 0, ts INTEGER NOT NULL)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_mutes_name ON mutes(name)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_mutes_ip ON mutes(ip)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS offences ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "uuid TEXT, name TEXT NOT NULL, ip TEXT, category TEXT NOT NULL,"
                        + "step INTEGER NOT NULL, action TEXT NOT NULL,"
                        + "text TEXT, source TEXT, staff TEXT,"
                        // Reversed by staff. Kept rather than deleted: a
                        // punishment that was overturned is worth being able to
                        // see, and deleting the row would hide the mistake as
                        // well as the offence.
                        + "voided INTEGER NOT NULL DEFAULT 0, ts INTEGER NOT NULL)");
                // Existing databases predate the column. SQLite has no
                // ADD COLUMN IF NOT EXISTS, so the duplicate error is the check.
                try {
                    st.executeUpdate("ALTER TABLE offences ADD COLUMN voided INTEGER NOT NULL DEFAULT 0");
                } catch (SQLException alreadyThere) {
                    // Column present. Nothing to do.
                }
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_off_name_cat "
                        + "ON offences(name, category, ts)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_off_ip_cat "
                        + "ON offences(ip, category, ts)");
                // Bans live apart from mutes: a ban is enforced at login, a mute
                // at speech, and mixing them in one table means every lookup has
                // to filter by kind on the hot chat path.
                st.executeUpdate("CREATE TABLE IF NOT EXISTS bans ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "uuid TEXT, name TEXT NOT NULL, ip TEXT,"
                        + "until INTEGER NOT NULL, reason TEXT, source TEXT, staff TEXT,"
                        + "revoked INTEGER NOT NULL DEFAULT 0, ts INTEGER NOT NULL)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bans_name ON bans(name)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bans_ip ON bans(ip)");
            }
        } catch (SQLException ex) {
            this.plugin.getLogger().warning("[TextGuard] SQLite unavailable: " + ex.getMessage());
            return false;
        }
        loadActive();
        return true;
    }

    public void disable() {
        this.byUuid.clear();
        this.byName.clear();
        this.byIp.clear();
        if (this.db != null) {
            try { this.db.close(); } catch (SQLException ignored) { }
            this.db = null;
        }
    }

    public boolean ready() { return this.db != null; }

    /* ------------------------------------------------------------------ */
    /*  Ladder configuration                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Replaces the ladders. Each entry is "ACTION" or "ACTION DURATION", e.g.
     * {@code WARN}, {@code MUTE 1d}, {@code IPMUTE permanent}.
     */
    public void setLadders(Map<String, List<String>> raw, long windowMs) {
        this.ladders.clear();
        this.ladderWindowMs = Math.max(3_600_000L, windowMs);
        for (Map.Entry<String, List<String>> e : raw.entrySet()) {
            List<Rung> rungs = new ArrayList<>();
            for (String line : e.getValue()) {
                Rung r = parseRung(line);
                if (r != null) rungs.add(r);
                else this.plugin.getLogger().warning("[TextGuard] unreadable ladder rung '"
                        + line + "' in category " + e.getKey());
            }
            if (!rungs.isEmpty()) this.ladders.put(e.getKey().toLowerCase(Locale.ROOT), rungs);
        }
    }

    static Rung parseRung(String line) {
        if (line == null || line.isBlank()) return null;
        String[] parts = line.trim().split("\\s+", 2);
        String action = parts[0].toUpperCase(Locale.ROOT);
        // STAFF-ONLY is a rung that punishes nobody. The message is still
        // deleted and staff are still alerted, but no ladder step is applied and
        // no record is written against the address. It exists for offences where
        // the call needs a human - doxxing, where the punishment is an IP ban and
        // telling a leak from someone sharing their own details is a judgement.
        if (action.equals("STAFF-ONLY") || action.equals("STAFF_ONLY")
                || action.equals("ALERT")) {
            return new Rung("STAFF-ONLY", 0L);
        }
        if (!action.equals("WARN") && !action.equals("MUTE") && !action.equals("IPMUTE")
                && !action.equals("BAN") && !action.equals("IPBAN")
                && !action.equals("NONE")) {
            return null;
        }
        if (action.equals("WARN") || action.equals("NONE")) return new Rung(action, 0L);
        // A mute rung must state its length. A typo must NOT quietly become a
        // permanent mute, so anything unreadable rejects the whole rung and the
        // caller logs it.
        if (parts.length < 2) return null;
        long ms = parseDuration(parts[1]);
        return ms == INVALID ? null : new Rung(action, ms);
    }

    /** Returned by {@link #parseDuration} when the text is not a duration. */
    static final long INVALID = Long.MIN_VALUE;

    /** Milliseconds, -1 for permanent, or {@link #INVALID}. */
    static long parseDuration(String s) {
        if (s == null) return INVALID;
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.equals("permanent") || t.equals("perm") || t.equals("forever")) return -1L;
        Matcher m = DURATION.matcher(t);
        if (!m.matches()) return INVALID;
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "s" -> n * 1_000L;
            case "m" -> n * 60_000L;
            case "h" -> n * 3_600_000L;
            case "d" -> n * 86_400_000L;
            case "w" -> n * 604_800_000L;
            default -> INVALID;
        };
    }

    /** The rung this offence lands on. Never null: an unknown category warns. */
    public Rung rungFor(String category, int priorOffences) {
        List<Rung> rungs = this.ladders.get(category.toLowerCase(Locale.ROOT));
        if (rungs == null || rungs.isEmpty()) return new Rung("WARN", 0L);
        int idx = Math.min(Math.max(0, priorOffences), rungs.size() - 1);
        return rungs.get(idx);
    }

    public long ladderWindowMs() { return this.ladderWindowMs; }

    /** Category names that have a ladder, for command help. */
    public java.util.Set<String> ladderCategories() {
        return java.util.Collections.unmodifiableSet(this.ladders.keySet());
    }

    /* ------------------------------------------------------------------ */
    /*  Offence history                                                   */
    /* ------------------------------------------------------------------ */

    /** How many offences this player already has in this category, in-window. */
    public int priorOffences(String name, String category) {
        if (this.db == null) return 0;
        try (PreparedStatement ps = this.db.prepareStatement(
                "SELECT COUNT(*) FROM offences WHERE name=? AND category=? AND ts>=?"
                + " AND voided=0")) {
            ps.setString(1, name);
            ps.setString(2, category.toLowerCase(Locale.ROOT));
            ps.setLong(3, System.currentTimeMillis() - this.ladderWindowMs);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException ex) {
            return 0;
        }
    }

    public void recordOffence(String uuid, String name, String category, int step,
                              String action, String text, String source) {
        recordOffence(uuid, name, null, category, step, action, text, source, null);
    }

    /**
     * @param ip    the address the offence came from. Violations are counted per
     *              address, not per account — an alt is the same person.
     * @param staff who issued it, or null when the plugin decided by itself.
     */
    public void recordOffence(String uuid, String name, String ip, String category, int step,
                              String action, String text, String source, String staff) {
        if (this.db == null) return;
        try (PreparedStatement ps = this.db.prepareStatement(
                "INSERT INTO offences(uuid,name,ip,category,step,action,text,source,staff,ts)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setString(3, ip);
            ps.setString(4, category.toLowerCase(Locale.ROOT));
            ps.setInt(5, step);
            ps.setString(6, action);
            ps.setString(7, text == null ? "" : (text.length() > 300 ? text.substring(0, 300) : text));
            ps.setString(8, source);
            ps.setString(9, staff);
            ps.setLong(10, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            this.plugin.getLogger().warning("[TextGuard] offence insert failed: " + ex.getMessage());
        }
    }

    /**
     * Forces a player's ladder position in a category.
     *
     * <h2>Why this exists</h2>
     * The ladder counts prior offences and never asks whether they were fair.
     * One wrong detection therefore does not cost one wrong mute, it costs every
     * mute after it as well, because the next real offence lands a rung too
     * high. Lifting the mute does not undo that — the offence row is still
     * there, still counting.
     *
     * <h2>What it does to the record</h2>
     * It does not delete history. The rows are marked {@code voided}, so they
     * stop counting towards the ladder while remaining visible to anyone reading
     * the record. A punishment that was reversed is a thing worth being able to
     * see, and quietly deleting it would hide both the offence and the mistake.
     *
     * <p>Raising a stage adds placeholder rows instead, attributed to the staff
     * member who asked for it.
     *
     * @param stage the number of prior offences the player should now have
     * @return the number of rows changed
     */
    public int setStage(String name, String category, int stage, String staff) {
        if (this.db == null || stage < 0) return 0;
        String cat = category.toLowerCase(Locale.ROOT);
        int current = priorOffences(name, category);
        if (current == stage) return 0;
        long since = this.ladderWindowMs <= 0 ? 0L
                : System.currentTimeMillis() - this.ladderWindowMs;
        if (stage < current) {
            // Void the most recent ones first. The newest offence is the one a
            // staff member is most likely to be overturning.
            try (PreparedStatement ps = this.db.prepareStatement(
                    "UPDATE offences SET voided=1, staff=? WHERE id IN ("
                    + "SELECT id FROM offences WHERE name=? AND category=? AND ts>=?"
                    + " AND voided=0 ORDER BY ts DESC LIMIT ?)")) {
                ps.setString(1, staff);
                ps.setString(2, name);
                ps.setString(3, cat);
                ps.setLong(4, since);
                ps.setInt(5, current - stage);
                return ps.executeUpdate();
            } catch (SQLException ex) {
                this.plugin.getLogger().warning("[TextGuard] stage lower failed: " + ex.getMessage());
                return 0;
            }
        }
        for (int i = current; i < stage; i++) {
            recordOffence("", name, null, cat, i, "MANUAL",
                    "stage set by " + staff, "override", staff);
        }
        return stage - current;
    }

    /**
     * Violations recorded for an <em>address</em> in a category.
     *
     * This is the number /offend escalates on. Counting per account would reset
     * the ladder every time someone made a new alt, which is exactly the
     * behaviour the ladder exists to defeat.
     */
    public int violationsForIp(String ip, String category, long windowMs) {
        if (this.db == null || ip == null || ip.isBlank()) return 0;
        try (PreparedStatement ps = this.db.prepareStatement(
                "SELECT COUNT(*) FROM offences WHERE ip=? AND category=? AND ts>=?"
                + " AND voided=0")) {
            ps.setString(1, ip);
            ps.setString(2, category.toLowerCase(Locale.ROOT));
            ps.setLong(3, windowMs <= 0 ? 0L : System.currentTimeMillis() - windowMs);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException ex) {
            return 0;
        }
    }

    /** Total violations for an address across every category. */
    public int violationsForIp(String ip, long windowMs) {
        if (this.db == null || ip == null || ip.isBlank()) return 0;
        try (PreparedStatement ps = this.db.prepareStatement(
                "SELECT COUNT(*) FROM offences WHERE ip=? AND ts>=?")) {
            ps.setString(1, ip);
            ps.setLong(2, windowMs <= 0 ? 0L : System.currentTimeMillis() - windowMs);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException ex) {
            return 0;
        }
    }

    /** Every account this store has seen offend from one address. */
    public List<String> accountsOnIp(String ip) {
        List<String> out = new ArrayList<>();
        if (this.db == null || ip == null) return out;
        try (PreparedStatement ps = this.db.prepareStatement(
                "SELECT DISTINCT name FROM offences WHERE ip=?")) {
            ps.setString(1, ip);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getString(1)); }
        } catch (SQLException ignored) { }
        return out;
    }

    /** Newest first, for /textguard history. */
    public List<Map<String, Object>> history(String name, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (this.db == null) return out;
        try (PreparedStatement ps = this.db.prepareStatement(
                "SELECT category,step,action,text,source,ts FROM offences"
                + " WHERE name=? ORDER BY ts DESC LIMIT ?")) {
            ps.setString(1, name);
            ps.setInt(2, Math.max(1, Math.min(50, limit)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("category", rs.getString(1));
                    m.put("step", rs.getInt(2));
                    m.put("action", rs.getString(3));
                    m.put("text", rs.getString(4));
                    m.put("source", rs.getString(5));
                    m.put("ts", rs.getLong(6));
                    out.add(m);
                }
            }
        } catch (SQLException ignored) { }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /*  Mutes                                                             */
    /* ------------------------------------------------------------------ */

    private void loadActive() {
        this.byUuid.clear();
        this.byName.clear();
        this.byIp.clear();
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = this.db.prepareStatement(
                "SELECT id,uuid,name,ip,until,reason,source FROM mutes"
                + " WHERE revoked=0 AND (until<0 OR until>?) ORDER BY ts ASC")) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Mute m = new Mute(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7));
                    index(m);
                }
            }
        } catch (SQLException ex) {
            this.plugin.getLogger().warning("[TextGuard] could not load mutes: " + ex.getMessage());
        }
        int n = this.byUuid.size() + this.byName.size() + this.byIp.size();
        if (n > 0) this.plugin.getLogger().info("[TextGuard] " + n + " active mute record(s) loaded.");
    }

    private void index(Mute m) {
        if (m.uuid() != null && !m.uuid().isBlank()) this.byUuid.put(m.uuid(), m);
        if (m.name() != null && !m.name().isBlank()) this.byName.put(lower(m.name()), m);
        if (m.ipWide()) this.byIp.put(m.ip(), m);
    }

    /**
     * Writes a mute and starts enforcing it immediately.
     *
     * @param ip non-null makes it IP-scoped, so alts on the same address are
     *           caught too. Pass null for an account-only mute.
     */
    public Mute mute(String uuid, String name, String ip, long durationMs,
                     String reason, String source) {
        long until = durationMs < 0L ? -1L : System.currentTimeMillis() + durationMs;
        long id = -1L;
        if (this.db != null) {
            // No RETURN_GENERATED_KEYS: not every sqlite-jdbc build supports it,
            // and the row id is only ever cosmetic here.
            try (PreparedStatement ps = this.db.prepareStatement(
                    "INSERT INTO mutes(uuid,name,ip,until,reason,source,revoked,ts)"
                    + " VALUES(?,?,?,?,?,?,0,?)")) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                ps.setString(3, ip);
                ps.setLong(4, until);
                ps.setString(5, reason);
                ps.setString(6, source);
                ps.setLong(7, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException ex) {
                this.plugin.getLogger().warning("[TextGuard] mute insert failed: " + ex.getMessage());
            }
        }
        Mute m = new Mute(id, uuid, name, ip, until, reason, source);
        index(m);
        return m;
    }

    /** Lifts every active mute matching a name, plus any IP mute they carry. */
    public int unmute(String name) {
        int n = 0;
        Mute byName = this.byName.remove(lower(name));
        if (byName != null) {
            n++;
            if (byName.uuid() != null) this.byUuid.remove(byName.uuid());
            if (byName.ipWide()) this.byIp.remove(byName.ip());
        }
        if (this.db != null) {
            try (PreparedStatement ps = this.db.prepareStatement(
                    "UPDATE mutes SET revoked=1 WHERE revoked=0 AND name=?")) {
                ps.setString(1, name);
                n = Math.max(n, ps.executeUpdate());
            } catch (SQLException ex) {
                this.plugin.getLogger().warning("[TextGuard] unmute failed: " + ex.getMessage());
            }
        }
        return n;
    }

    /** Lifts an IP mute directly, for the case where the name has changed. */
    public int unmuteIp(String ip) {
        this.byIp.remove(ip);
        if (this.db == null) return 0;
        try (PreparedStatement ps = this.db.prepareStatement(
                "UPDATE mutes SET revoked=1 WHERE revoked=0 AND ip=?")) {
            ps.setString(1, ip);
            return ps.executeUpdate();
        } catch (SQLException ex) {
            return 0;
        }
    }

    /**
     * The mute that applies to this player right now, or null.
     * Checked in order of scope: account by UUID, account by name, then the
     * address they are connected from.
     */
    public Mute activeFor(Player p) {
        long now = System.currentTimeMillis();
        Mute m = fresh(this.byUuid.get(p.getUniqueId().toString()), now);
        if (m != null) return m;
        m = fresh(this.byName.get(lower(p.getName())), now);
        if (m != null) return m;
        String ip = addressOf(p);
        return ip == null ? null : fresh(this.byIp.get(ip), now);
    }

    /** Same lookup for someone who is not online, so /textguard check works. */
    public Mute activeFor(String name) {
        long now = System.currentTimeMillis();
        return fresh(this.byName.get(lower(name)), now);
    }

    /** Drops the entry when it has simply run out, so the maps self-clean. */
    private Mute fresh(Mute m, long now) {
        if (m == null) return null;
        if (m.active(now)) return m;
        if (m.uuid() != null) this.byUuid.remove(m.uuid());
        this.byName.remove(lower(m.name()));
        if (m.ipWide()) this.byIp.remove(m.ip());
        return null;
    }

    public List<Mute> activeMutes() {
        long now = System.currentTimeMillis();
        List<Mute> out = new ArrayList<>();
        for (Mute m : this.byName.values()) if (m.active(now)) out.add(m);
        for (Mute m : this.byIp.values()) {
            if (m.active(now) && !out.contains(m)) out.add(m);
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /*  Bans                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Writes a ban.
     *
     * Enforced by {@link #activeBan} at login rather than handed to the vanilla
     * ban list, so IP scope works the same way mutes do and a name change does
     * not lift it.
     */
    public Mute ban(String uuid, String name, String ip, long durationMs,
                    String reason, String source, String staff) {
        long until = durationMs < 0L ? -1L : System.currentTimeMillis() + durationMs;
        if (this.db != null) {
            try (PreparedStatement ps = this.db.prepareStatement(
                    "INSERT INTO bans(uuid,name,ip,until,reason,source,staff,revoked,ts)"
                    + " VALUES(?,?,?,?,?,?,?,0,?)")) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                ps.setString(3, ip);
                ps.setLong(4, until);
                ps.setString(5, reason);
                ps.setString(6, source);
                ps.setString(7, staff);
                ps.setLong(8, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException ex) {
                this.plugin.getLogger().warning("[TextGuard] ban insert failed: " + ex.getMessage());
            }
        }
        return new Mute(-1L, uuid, name, ip, until, reason, source);
    }

    /**
     * The ban that applies to this login, or null.
     *
     * Hits the database rather than a cache: logins are rare and a stale cache
     * here would either let a banned player in or keep an unbanned one out.
     */
    public Mute activeBan(UUID uuid, String name, String ip) {
        if (this.db == null) return null;
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = this.db.prepareStatement(
                "SELECT uuid,name,ip,until,reason,source FROM bans"
                + " WHERE revoked=0 AND (until<0 OR until>?)"
                + " AND (uuid=? OR lower(name)=? OR (ip IS NOT NULL AND ip<>'' AND ip=?))"
                + " ORDER BY CASE WHEN until<0 THEN 1 ELSE 0 END DESC, until DESC LIMIT 1")) {
            ps.setLong(1, now);
            ps.setString(2, uuid == null ? "" : uuid.toString());
            ps.setString(3, lower(name));
            ps.setString(4, ip == null ? "" : ip);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Mute(-1L, rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getString(5), rs.getString(6));
            }
        } catch (SQLException ex) {
            this.plugin.getLogger().warning("[TextGuard] ban lookup failed: " + ex.getMessage());
            return null;
        }
    }

    public int unban(String name) {
        if (this.db == null) return 0;
        try (PreparedStatement ps = this.db.prepareStatement(
                "UPDATE bans SET revoked=1 WHERE revoked=0 AND lower(name)=?")) {
            ps.setString(1, lower(name));
            return ps.executeUpdate();
        } catch (SQLException ex) {
            return 0;
        }
    }

    public int unbanIp(String ip) {
        if (this.db == null) return 0;
        try (PreparedStatement ps = this.db.prepareStatement(
                "UPDATE bans SET revoked=1 WHERE revoked=0 AND ip=?")) {
            ps.setString(1, ip);
            return ps.executeUpdate();
        } catch (SQLException ex) {
            return 0;
        }
    }

    public List<Mute> activeBans(int limit) {
        List<Mute> out = new ArrayList<>();
        if (this.db == null) return out;
        try (PreparedStatement ps = this.db.prepareStatement(
                "SELECT uuid,name,ip,until,reason,source FROM bans"
                + " WHERE revoked=0 AND (until<0 OR until>?) ORDER BY ts DESC LIMIT ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setInt(2, Math.max(1, Math.min(100, limit)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Mute(-1L, rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getLong(4), rs.getString(5), rs.getString(6)));
                }
            }
        } catch (SQLException ignored) { }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /*  Retention                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * Removes punishment rows that can no longer affect anything.
     *
     * <p>Only rows that are revoked, or expired past the retention period, are
     * touched. A live mute is never deleted however old it is.
     *
     * <p><b>Offences are a separate decision.</b> They are the ladder's memory:
     * delete them and a player who has been muted four times starts again at a
     * warning. So they are only pruned when {@code offence-keep-days} is set
     * above zero, and the log says plainly when it happens.
     *
     * @return rows deleted per table
     */
    public Map<String, Integer> prune(long punishmentRetainMs, long offenceRetainMs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (this.db == null) return out;
        long now = System.currentTimeMillis();
        long cutoff = now - Math.max(86_400_000L, punishmentRetainMs);
        for (String table : new String[]{ "mutes", "bans" }) {
            try (PreparedStatement ps = this.db.prepareStatement(
                    "DELETE FROM " + table + " WHERE ts<? AND (revoked=1 OR (until>=0 AND until<?))")) {
                ps.setLong(1, cutoff);
                ps.setLong(2, now);
                out.put(table, ps.executeUpdate());
            } catch (SQLException ex) {
                this.plugin.getLogger().warning("[TextGuard] prune of " + table
                        + " failed: " + ex.getMessage());
            }
        }
        if (offenceRetainMs > 0) {
            try (PreparedStatement ps = this.db.prepareStatement(
                    "DELETE FROM offences WHERE ts<?")) {
                ps.setLong(1, now - offenceRetainMs);
                int n = ps.executeUpdate();
                out.put("offences", n);
                if (n > 0) {
                    this.plugin.getLogger().info("[TextGuard] pruned " + n + " offence record(s). "
                            + "Ladder positions for those players have reset.");
                }
            } catch (SQLException ignored) { }
        }
        // Drop anything that expired out of the in-memory maps too.
        loadActive();
        return out;
    }

    /** Row counts per table, for diagnostics. */
    public Map<String, Integer> counts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (this.db == null) return out;
        for (String table : new String[]{ "mutes", "bans", "offences" }) {
            try (PreparedStatement ps = this.db.prepareStatement(
                    "SELECT COUNT(*) FROM " + table);
                 ResultSet rs = ps.executeQuery()) {
                out.put(table, rs.next() ? rs.getInt(1) : 0);
            } catch (SQLException ignored) { }
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                           */
    /* ------------------------------------------------------------------ */

    public static String addressOf(Player p) {
        InetSocketAddress a = p.getAddress();
        return a == null || a.getAddress() == null ? null : a.getAddress().getHostAddress();
    }

    /** Best-effort UUID for a name, without blocking on a Mojang lookup. */
    public static String uuidOf(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId().toString();
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
        UUID id = off == null ? null : off.getUniqueId();
        return id == null ? null : id.toString();
    }

    /** "permanent", or a short human duration like "2d 3h". */
    public static String remaining(Mute m) {
        if (m.permanent()) return "permanent";
        long ms = m.until() - System.currentTimeMillis();
        if (ms <= 0) return "expired";
        long days = ms / 86_400_000L;
        long hours = (ms % 86_400_000L) / 3_600_000L;
        long mins = (ms % 3_600_000L) / 60_000L;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + mins + "m";
        return Math.max(1L, mins) + "m";
    }

    public static String describe(Rung r) {
        if (r.action().equals("WARN") || r.action().equals("NONE")) return r.action();
        return r.action() + " " + (r.permanent() ? "permanent" : humanise(r.durationMs()));
    }

    static String humanise(long ms) {
        if (ms % 604_800_000L == 0) return (ms / 604_800_000L) + "w";
        if (ms % 86_400_000L == 0) return (ms / 86_400_000L) + "d";
        if (ms % 3_600_000L == 0) return (ms / 3_600_000L) + "h";
        return Math.max(1L, ms / 60_000L) + "m";
    }

    private static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
