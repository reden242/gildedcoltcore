package com.gildedmc.core.modules;

import com.gildedmc.core.SchedulerCompat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * StaffMonitorModule — staff audit, risk scoring and AFK analytics.
 *
 * Everything is stored in SQLite (staffmonitor.db in the plugin folder). All
 * writes and all reads happen off the main thread; GUIs are populated on an
 * async query and then opened back on the main thread.
 *
 * Only players holding {@code staffmonitor.tracked} are recorded, so this does
 * not log your whole playerbase.
 *
 * Hook-up in GildedCorePlugin#onEnable():
 *     this.staffMonitorModule = new StaffMonitorModule(this);
 *     this.staffMonitorModule.enable();
 *     Bukkit.getPluginManager().registerEvents((Listener)this.staffMonitorModule, (Plugin)this);
 * onDisable(): if (this.staffMonitorModule != null) this.staffMonitorModule.disable();
 * reload():    this.staffMonitorModule.reload();
 */
public final class StaffMonitorModule implements Listener {

    /* ------------------------------------------------------------------ */
    /*  Permissions                                                       */
    /* ------------------------------------------------------------------ */

    public static final String PERM_VIEW    = "staffmonitor.view";
    public static final String PERM_ADMIN   = "staffmonitor.admin";
    public static final String PERM_TRACKED = "staffmonitor.tracked";
    public static final String PERM_ALERTS  = "staffmonitor.alerts";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final long DAY_MS = 86_400_000L;

    /* ------------------------------------------------------------------ */
    /*  State                                                             */
    /* ------------------------------------------------------------------ */

    private final JavaPlugin plugin;

    private boolean enabled;
    private Connection db;
    private SchedulerCompat.ManagedTask tickTask;
    private SchedulerCompat.ManagedTask cleanupTask;

    /** category id -> compiled pattern matched against the bare command label. */
    private final Map<String, Pattern> categories = new LinkedHashMap<>();
    /** category id -> risk weight added per action. */
    private final Map<String, Integer> riskWeights = new LinkedHashMap<>();
    /** category id -> actions within the window before it is flagged suspicious. */
    private final Map<String, Integer> thresholds = new LinkedHashMap<>();

    private long idleAfterMs = 300_000L;      // 5 min with no input = idle
    private long suspiciousWindowMs = 600_000L;
    private int keepDays = 30;
    /** Days the /staffafk leaderboard sums over. */
    private int afkBoardDays = 30;
    private String guiTitleMain = "&8Staff Monitor";
    private String guiTitleTimeline = "&8Timeline &7- &f%staff%";
    private String guiTitleTop = "&8Top Staff";
    private String guiTitleActivity = "&8Staff Activity";
    private String guiTitleAfk = "&8Most AFK Staff";

    /** last input timestamp per online tracked staff. */
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    /** last presence sample timestamp per online tracked staff. */
    private final Map<UUID, Long> lastSample = new ConcurrentHashMap<>();

    public StaffMonitorModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /* ------------------------------------------------------------------ */
    /*  Lifecycle                                                         */
    /* ------------------------------------------------------------------ */

    public void enable() {
        readConfig();
        if (!this.enabled) return;
        if (!openDatabase()) {
            this.enabled = false;
            return;
        }
        long everySec = 20L * 15;
        this.tickTask = SchedulerCompat.timer(
                this.plugin, this::samplePresence, everySec, everySec);
        this.cleanupTask = SchedulerCompat.timerAsync(
                this.plugin, this::cleanup, 20L * 60, 20L * 60 * 60 * 6);
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(PERM_TRACKED)) {
                this.lastActivity.put(p.getUniqueId(), now);
                this.lastSample.put(p.getUniqueId(), now);
            }
        }
        this.plugin.getLogger().info("[StaffMonitor] enabled, "
                + this.categories.size() + " categories, keeping " + this.keepDays + " days.");
    }

    public void reload() {
        boolean was = this.enabled;
        readConfig();
        if (this.enabled && !was) enable();
        if (!this.enabled && was) disable();
    }

    public void disable() {
        if (this.tickTask != null) { this.tickTask.cancel(); this.tickTask = null; }
        if (this.cleanupTask != null) { this.cleanupTask.cancel(); this.cleanupTask = null; }
        samplePresence();
        closeDatabase();
    }

    private void readConfig() {
        ConfigurationSection c = this.plugin.getConfig().getConfigurationSection("staff-monitor");
        if (c == null) {
            this.enabled = false;
            return;
        }
        this.enabled = c.getBoolean("enabled", false);
        this.idleAfterMs = Math.max(30L, c.getLong("idle-after-seconds", 300L)) * 1000L;
        this.suspiciousWindowMs = Math.max(60L, c.getLong("suspicious-window-seconds", 600L)) * 1000L;
        this.keepDays = Math.max(1, c.getInt("keep-days", 30));
        this.afkBoardDays = Math.max(1, c.getInt("afk-leaderboard-days", this.keepDays));

        ConfigurationSection g = c.getConfigurationSection("gui-titles");
        if (g != null) {
            this.guiTitleMain     = g.getString("main", this.guiTitleMain);
            this.guiTitleTimeline = g.getString("timeline", this.guiTitleTimeline);
            this.guiTitleTop      = g.getString("top", this.guiTitleTop);
            this.guiTitleActivity = g.getString("activity", this.guiTitleActivity);
            this.guiTitleAfk      = g.getString("afk", this.guiTitleAfk);
        }

        this.categories.clear();
        this.riskWeights.clear();
        this.thresholds.clear();
        ConfigurationSection cats = c.getConfigurationSection("categories");
        if (cats != null) {
            for (String id : cats.getKeys(false)) {
                ConfigurationSection cs = cats.getConfigurationSection(id);
                if (cs == null) continue;
                String rx = cs.getString("pattern", "");
                if (rx.isEmpty()) continue;
                try {
                    this.categories.put(id.toUpperCase(Locale.ROOT),
                            Pattern.compile(rx, Pattern.CASE_INSENSITIVE));
                } catch (PatternSyntaxException ex) {
                    this.plugin.getLogger().warning(
                            "[StaffMonitor] bad regex for category " + id + ": " + ex.getMessage());
                    continue;
                }
                this.riskWeights.put(id.toUpperCase(Locale.ROOT), cs.getInt("risk", 1));
                this.thresholds.put(id.toUpperCase(Locale.ROOT), cs.getInt("threshold", 0));
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Database                                                          */
    /* ------------------------------------------------------------------ */

    private boolean openDatabase() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ignored) {
            // Paper bundles the driver; if absent DriverManager may still resolve it.
        }
        File f = new File(this.plugin.getDataFolder(), "staffmonitor.db");
        if (!f.getParentFile().exists() && !f.getParentFile().mkdirs()) {
            this.plugin.getLogger().warning("[StaffMonitor] could not create plugin folder.");
            return false;
        }
        try {
            this.db = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
            try (Statement st = this.db.createStatement()) {
                st.executeUpdate("PRAGMA journal_mode=WAL");
                st.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS actions ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "uuid TEXT NOT NULL, name TEXT NOT NULL,"
                        + "command TEXT NOT NULL, category TEXT NOT NULL,"
                        + "target TEXT, world TEXT, x INTEGER, y INTEGER, z INTEGER,"
                        + "ts INTEGER NOT NULL, risk INTEGER NOT NULL,"
                        + "suspicious INTEGER NOT NULL DEFAULT 0)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_actions_uuid_ts ON actions(uuid, ts)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_actions_ts ON actions(ts)");
                st.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS presence ("
                        + "uuid TEXT NOT NULL, name TEXT NOT NULL, day TEXT NOT NULL,"
                        + "online_ms INTEGER NOT NULL DEFAULT 0,"
                        + "active_ms INTEGER NOT NULL DEFAULT 0,"
                        + "idle_ms INTEGER NOT NULL DEFAULT 0,"
                        + "PRIMARY KEY (uuid, day))");
            }
            return true;
        } catch (SQLException ex) {
            this.plugin.getLogger().warning("[StaffMonitor] SQLite unavailable: " + ex.getMessage());
            return false;
        }
    }

    private void closeDatabase() {
        if (this.db == null) return;
        try { this.db.close(); } catch (SQLException ignored) { }
        this.db = null;
    }

    private void async(Runnable r) {
        if (!this.plugin.isEnabled()) return;
        SchedulerCompat.runAsync(this.plugin, r);
    }

    private void sync(Runnable r) {
        if (!this.plugin.isEnabled()) return;
        SchedulerCompat.run(this.plugin, r);
    }

    /* ------------------------------------------------------------------ */
    /*  Capture                                                           */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!this.enabled || this.db == null) return;
        Player p = event.getPlayer();
        if (!p.hasPermission(PERM_TRACKED)) return;

        touch(p);

        String raw = event.getMessage();
        if (raw.startsWith("/")) raw = raw.substring(1);
        if (raw.isEmpty()) return;
        String[] parts = raw.split("\\s+");
        String label = parts[0].toLowerCase(Locale.ROOT);
        if (label.contains(":")) label = label.substring(label.indexOf(':') + 1);

        String category = categorise(label);
        int risk = this.riskWeights.getOrDefault(category, 1);
        String target = parts.length > 1 ? parts[1] : null;
        Location l = p.getLocation();

        final String fRaw = raw;
        final String fCat = category;
        final String fTarget = target;
        final UUID id = p.getUniqueId();
        final String name = p.getName();
        final long now = System.currentTimeMillis();

        async(() -> {
            boolean flagged = recentCount(id, fCat, now - this.suspiciousWindowMs)
                    + 1 > threshold(fCat);
            insertAction(id, name, fRaw, fCat, fTarget, l, now, risk, flagged);
            if (flagged) {
                sync(() -> alert(name, fCat, fRaw));
            }
        });
    }

    private int threshold(String category) {
        int t = this.thresholds.getOrDefault(category, 0);
        return t <= 0 ? Integer.MAX_VALUE : t;
    }

    private String categorise(String label) {
        for (Map.Entry<String, Pattern> e : this.categories.entrySet()) {
            if (e.getValue().matcher(label).matches()) return e.getKey();
        }
        return "OTHER";
    }

    private void alert(String staff, String category, String command) {
        String msg = ChatColor.translateAlternateColorCodes('&',
                "&8[&cStaffMonitor&8] &f" + staff + " &7tripped &c" + category
                + " &7threshold: &f/" + command);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(PERM_ALERTS)) p.sendMessage(msg);
        }
        this.plugin.getLogger().info("[StaffMonitor] SUSPICIOUS " + staff + " " + category + " /" + command);
    }

    private void insertAction(UUID id, String name, String cmd, String cat, String target,
                              Location l, long ts, int risk, boolean flagged) {
        if (this.db == null) return;
        String sql = "INSERT INTO actions(uuid,name,command,category,target,world,x,y,z,ts,risk,suspicious)"
                   + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = this.db.prepareStatement(sql)) {
            ps.setString(1, id.toString());
            ps.setString(2, name);
            ps.setString(3, cmd);
            ps.setString(4, cat);
            ps.setString(5, target);
            ps.setString(6, l.getWorld() == null ? "?" : l.getWorld().getName());
            ps.setInt(7, l.getBlockX());
            ps.setInt(8, l.getBlockY());
            ps.setInt(9, l.getBlockZ());
            ps.setLong(10, ts);
            ps.setInt(11, risk);
            ps.setInt(12, flagged ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException ex) {
            this.plugin.getLogger().warning("[StaffMonitor] insert failed: " + ex.getMessage());
        }
    }

    private int recentCount(UUID id, String category, long since) {
        if (this.db == null) return 0;
        try (PreparedStatement ps = this.db.prepareStatement(
                "SELECT COUNT(*) FROM actions WHERE uuid=? AND category=? AND ts>=?")) {
            ps.setString(1, id.toString());
            ps.setString(2, category);
            ps.setLong(3, since);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException ex) {
            return 0;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Presence / AFK                                                    */
    /* ------------------------------------------------------------------ */

    private void touch(Player p) {
        this.lastActivity.put(p.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!this.enabled) return;
        if (event.getTo() == null) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
         && event.getFrom().getBlockY() == event.getTo().getBlockY()
         && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;
        if (event.getPlayer().hasPermission(PERM_TRACKED)) touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!this.enabled) return;
        if (event.getPlayer().hasPermission(PERM_TRACKED)) touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!this.enabled) return;
        Player p = event.getPlayer();
        if (!p.hasPermission(PERM_TRACKED)) return;
        long now = System.currentTimeMillis();
        this.lastActivity.put(p.getUniqueId(), now);
        this.lastSample.put(p.getUniqueId(), now);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!this.enabled) return;
        Player p = event.getPlayer();
        samplePlayer(p, System.currentTimeMillis());
        this.lastActivity.remove(p.getUniqueId());
        this.lastSample.remove(p.getUniqueId());
    }

    /** Runs on the main thread; hands the accumulated slice to an async write. */
    private void samplePresence() {
        if (!this.enabled || this.db == null) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(PERM_TRACKED)) samplePlayer(p, now);
        }
    }

    private void samplePlayer(Player p, long now) {
        UUID id = p.getUniqueId();
        Long prev = this.lastSample.get(id);
        if (prev == null) { this.lastSample.put(id, now); return; }
        long slice = now - prev;
        if (slice <= 0) return;
        this.lastSample.put(id, now);

        long last = this.lastActivity.getOrDefault(id, now);
        boolean idle = (now - last) >= this.idleAfterMs;
        final String name = p.getName();
        final String day = LocalDate.now().toString();
        final long online = slice;
        final long active = idle ? 0L : slice;
        final long idleMs = idle ? slice : 0L;
        async(() -> upsertPresence(id, name, day, online, active, idleMs));
    }

    private void upsertPresence(UUID id, String name, String day,
                                long online, long active, long idle) {
        if (this.db == null) return;
        String sql = "INSERT INTO presence(uuid,name,day,online_ms,active_ms,idle_ms)"
                   + " VALUES(?,?,?,?,?,?)"
                   + " ON CONFLICT(uuid,day) DO UPDATE SET"
                   + " name=excluded.name,"
                   + " online_ms=online_ms+excluded.online_ms,"
                   + " active_ms=active_ms+excluded.active_ms,"
                   + " idle_ms=idle_ms+excluded.idle_ms";
        try (PreparedStatement ps = this.db.prepareStatement(sql)) {
            ps.setString(1, id.toString());
            ps.setString(2, name);
            ps.setString(3, day);
            ps.setLong(4, online);
            ps.setLong(5, active);
            ps.setLong(6, idle);
            ps.executeUpdate();
        } catch (SQLException ex) {
            this.plugin.getLogger().warning("[StaffMonitor] presence write failed: " + ex.getMessage());
        }
    }

    private void cleanup() {
        if (this.db == null) return;
        long cutoff = System.currentTimeMillis() - (long) this.keepDays * DAY_MS;
        try (PreparedStatement ps = this.db.prepareStatement("DELETE FROM actions WHERE ts<?")) {
            ps.setLong(1, cutoff);
            int n = ps.executeUpdate();
            if (n > 0) this.plugin.getLogger().info("[StaffMonitor] pruned " + n + " old actions.");
        } catch (SQLException ignored) { }
        try (PreparedStatement ps = this.db.prepareStatement("DELETE FROM presence WHERE day<?")) {
            ps.setString(1, LocalDate.now().minusDays(this.keepDays).toString());
            ps.executeUpdate();
        } catch (SQLException ignored) { }
    }

    /* ------------------------------------------------------------------ */
    /*  Commands                                                          */
    /* ------------------------------------------------------------------ */

    public boolean command(CommandSender sender, String label, String[] args) {
        if (!this.enabled) {
            sender.sendMessage(ChatColor.RED + "Staff monitor is disabled in config.");
            return true;
        }
        if (label.equalsIgnoreCase("staffmonitor") && args.length > 0
                && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(PERM_ADMIN)) {
                sender.sendMessage(ChatColor.RED + "No permission.");
                return true;
            }
            reload();
            sender.sendMessage(ChatColor.GREEN + "StaffMonitor reloaded.");
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return true;
        }
        Player p = (Player) sender;
        if (!p.hasPermission(PERM_VIEW)) {
            p.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
        switch (label.toLowerCase(Locale.ROOT)) {
            case "staffactivity" -> openActivity(p);
            case "staffafk" -> openAfk(p);
            default -> openMain(p);
        }
        return true;
    }

    /* ------------------------------------------------------------------ */
    /*  GUI plumbing                                                      */
    /* ------------------------------------------------------------------ */

    /** Marker so clicks in our menus are recognised and cancelled. */
    private static final class Menu implements InventoryHolder {
        final String kind;
        final UUID subject;
        final int page;
        final String filter;
        Inventory inv;
        Menu(String kind, UUID subject, int page, String filter) {
            this.kind = kind; this.subject = subject; this.page = page; this.filter = filter;
        }
        @Override public Inventory getInventory() { return this.inv; }
    }

    private Inventory make(Menu m, int rows, String title) {
        Inventory inv = Bukkit.createInventory(m, rows * 9,
                ChatColor.translateAlternateColorCodes('&', title));
        m.inv = inv;
        return inv;
    }

    private static ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            if (lore != null && !lore.isEmpty()) {
                List<String> out = new ArrayList<>(lore.size());
                for (String s : lore) out.add(ChatColor.translateAlternateColorCodes('&', s));
                meta.setLore(out);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack head(UUID id, String name, List<String> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = it.getItemMeta();
        if (meta instanceof SkullMeta sm) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
            sm.setOwningPlayer(op);
            sm.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            List<String> out = new ArrayList<>();
            for (String s : lore) out.add(ChatColor.translateAlternateColorCodes('&', s));
            sm.setLore(out);
            it.setItemMeta(sm);
        }
        return it;
    }

    private static ItemStack filler() {
        return item(Material.GRAY_STAINED_GLASS_PANE, " ", null);
    }

    private static String riskColour(int risk) {
        if (risk >= 100) return "&4";
        if (risk >= 50) return "&c";
        if (risk >= 20) return "&#EEBB01";
        if (risk >= 5) return "&e";
        return "&a";
    }

    private static String duration(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m";
        return s + "s";
    }

    /* ------------------------------------------------------------------ */
    /*  GUI: main audit panel                                             */
    /* ------------------------------------------------------------------ */

    private void openMain(Player viewer) {
        async(() -> {
            List<Object[]> rows = new ArrayList<>();
            long since = System.currentTimeMillis() - DAY_MS;
            String sql = "SELECT uuid, name, COUNT(*) AS n, SUM(risk) AS r,"
                       + " SUM(suspicious) AS s, MAX(ts) AS last"
                       + " FROM actions WHERE ts>=? GROUP BY uuid ORDER BY r DESC LIMIT 45";
            try (PreparedStatement ps = this.db.prepareStatement(sql)) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Object[]{ rs.getString("uuid"), rs.getString("name"),
                                rs.getInt("n"), rs.getInt("r"), rs.getInt("s"), rs.getLong("last") });
                    }
                }
            } catch (SQLException ex) {
                sync(() -> viewer.sendMessage(ChatColor.RED + "Query failed: " + ex.getMessage()));
                return;
            }
            sync(() -> {
                Menu m = new Menu("main", null, 0, null);
                Inventory inv = make(m, 6, this.guiTitleMain);
                for (int i = 45; i < 54; i++) inv.setItem(i, filler());
                int slot = 0;
                for (Object[] r : rows) {
                    if (slot >= 45) break;
                    UUID id = UUID.fromString((String) r[0]);
                    int risk = (Integer) r[3];
                    inv.setItem(slot++, head(id, "&f" + r[1], Arrays.asList(
                            "&7Actions (24h): &f" + r[2],
                            "&7Risk score: " + riskColour(risk) + risk,
                            "&7Suspicious: &c" + r[4],
                            "&7Last seen acting: &f" + STAMP.format(Instant.ofEpochMilli((Long) r[5])),
                            "",
                            "&eClick &7for the full timeline.")));
                }
                inv.setItem(48, item(Material.GOLD_INGOT, "&#EEBB01Top 10", Collections.singletonList("&7Leaderboards")));
                inv.setItem(49, item(Material.CLOCK, "&bActivity", Collections.singletonList("&7Online vs Active vs Idle")));
                inv.setItem(50, item(Material.ENDER_EYE, "&dMost AFK",
                    Arrays.asList("&7Ranked by total idle time",
                            "&7across the whole window.")));
                viewer.openInventory(inv);
            });
        });
    }

    /* ------------------------------------------------------------------ */
    /*  GUI: per-staff timeline                                           */
    /* ------------------------------------------------------------------ */

    private void openTimeline(Player viewer, UUID subject, int page, String filter) {
        async(() -> {
            List<String[]> rows = new ArrayList<>();
            String name = subject.toString();
            StringBuilder sql = new StringBuilder(
                    "SELECT name,command,category,target,world,x,y,z,ts,risk,suspicious"
                    + " FROM actions WHERE uuid=?");
            if (filter != null && !filter.equals("ALL")) {
                sql.append(filter.equals("SUS") ? " AND suspicious=1" : " AND category=?");
            }
            sql.append(" ORDER BY ts DESC LIMIT 45 OFFSET ?");
            try (PreparedStatement ps = this.db.prepareStatement(sql.toString())) {
                int i = 1;
                ps.setString(i++, subject.toString());
                if (filter != null && !filter.equals("ALL") && !filter.equals("SUS")) ps.setString(i++, filter);
                ps.setInt(i, page * 45);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (name.equals(subject.toString())) name = rs.getString("name");
                        rows.add(new String[]{
                                rs.getString("command"), rs.getString("category"),
                                rs.getString("target"), rs.getString("world"),
                                String.valueOf(rs.getInt("x")), String.valueOf(rs.getInt("y")),
                                String.valueOf(rs.getInt("z")), String.valueOf(rs.getLong("ts")),
                                String.valueOf(rs.getInt("risk")), String.valueOf(rs.getInt("suspicious")) });
                    }
                }
            } catch (SQLException ex) {
                sync(() -> viewer.sendMessage(ChatColor.RED + "Query failed: " + ex.getMessage()));
                return;
            }
            final String staffName = name;
            sync(() -> {
                Menu m = new Menu("timeline", subject, page, filter == null ? "ALL" : filter);
                Inventory inv = make(m, 6, this.guiTitleTimeline.replace("%staff%", staffName));
                for (int i = 45; i < 54; i++) inv.setItem(i, filler());
                int slot = 0;
                for (String[] r : rows) {
                    if (slot >= 45) break;
                    long ts = Long.parseLong(r[7]);
                    boolean sus = "1".equals(r[9]);
                    inv.setItem(slot++, item(sus ? Material.REDSTONE_BLOCK : Material.PAPER,
                            (sus ? "&c" : "&f") + "/" + r[0],
                            Arrays.asList(
                                    "&7Category: &f" + r[1],
                                    "&7Target: &f" + (r[2] == null ? "-" : r[2]),
                                    "&7Where: &f" + r[3] + " " + r[4] + " " + r[5] + " " + r[6],
                                    "&7When: &f" + STAMP.format(Instant.ofEpochMilli(ts)),
                                    "&7Risk: &f" + r[8])));
                }
                inv.setItem(45, item(Material.ARROW, "&ePrevious page",
                        Collections.singletonList("&7Page " + (page + 1))));
                inv.setItem(53, item(Material.ARROW, "&eNext page",
                        Collections.singletonList("&7Page " + (page + 1))));
                inv.setItem(46, item(Material.BARRIER, "&cBack", null));
                inv.setItem(48, item(Material.IRON_SWORD, "&cPUNISH", null));
                inv.setItem(49, item(Material.ENDER_PEARL, "&bTELEPORT", null));
                inv.setItem(50, item(Material.CHEST, "&aGIVE", null));
                inv.setItem(51, item(Material.GLASS, "&fVANISH", null));
                inv.setItem(52, item(Material.REDSTONE, "&4Suspicious only", null));
                viewer.openInventory(inv);
            });
        });
    }

    /* ------------------------------------------------------------------ */
    /*  GUI: top 10                                                       */
    /* ------------------------------------------------------------------ */

    private void openTop(Player viewer) {
        async(() -> {
            Map<String, List<String>> board = new LinkedHashMap<>();
            long now = System.currentTimeMillis();
            long[] spans = { DAY_MS, 7 * DAY_MS, 30 * DAY_MS };
            String[] labels = { "24h", "7d", "30d" };
            for (int i = 0; i < spans.length; i++) {
                List<String> lines = new ArrayList<>();
                try (PreparedStatement ps = this.db.prepareStatement(
                        "SELECT name, COUNT(*) n, SUM(risk) r FROM actions WHERE ts>=?"
                        + " GROUP BY uuid ORDER BY n DESC LIMIT 10")) {
                    ps.setLong(1, now - spans[i]);
                    try (ResultSet rs = ps.executeQuery()) {
                        int rank = 1;
                        while (rs.next()) {
                            lines.add("&7" + (rank++) + ". &f" + rs.getString("name")
                                    + " &8- &f" + rs.getInt("n") + " &7actions, risk &f" + rs.getInt("r"));
                        }
                    }
                } catch (SQLException ignored) { }
                if (lines.isEmpty()) lines.add("&8no data");
                board.put(labels[i], lines);
            }
            sync(() -> {
                Menu m = new Menu("top", null, 0, null);
                Inventory inv = make(m, 3, this.guiTitleTop);
                for (int i = 0; i < 27; i++) inv.setItem(i, filler());
                inv.setItem(11, item(Material.CLOCK, "&eLast 24 hours", board.get("24h")));
                inv.setItem(13, item(Material.MAP, "&eLast 7 days", board.get("7d")));
                inv.setItem(15, item(Material.BOOK, "&eLast 30 days", board.get("30d")));
                inv.setItem(22, item(Material.BARRIER, "&cBack", null));
                viewer.openInventory(inv);
            });
        });
    }

    /* ------------------------------------------------------------------ */
    /*  GUI: activity (7 day)                                             */
    /* ------------------------------------------------------------------ */

    private void openActivity(Player viewer) {
        async(() -> {
            List<Object[]> rows = new ArrayList<>();
            String from = LocalDate.now().minusDays(6).toString();
            try (PreparedStatement ps = this.db.prepareStatement(
                    "SELECT uuid, name, SUM(online_ms) o, SUM(active_ms) a, SUM(idle_ms) i"
                    + " FROM presence WHERE day>=? GROUP BY uuid ORDER BY o DESC LIMIT 45")) {
                ps.setString(1, from);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Object[]{ rs.getString("uuid"), rs.getString("name"),
                                rs.getLong("o"), rs.getLong("a"), rs.getLong("i") });
                    }
                }
            } catch (SQLException ex) {
                sync(() -> viewer.sendMessage(ChatColor.RED + "Query failed: " + ex.getMessage()));
                return;
            }
            sync(() -> {
                Menu m = new Menu("activity", null, 0, null);
                Inventory inv = make(m, 6, this.guiTitleActivity);
                for (int i = 45; i < 54; i++) inv.setItem(i, filler());
                int slot = 0;
                for (Object[] r : rows) {
                    if (slot >= 45) break;
                    long online = (Long) r[2], active = (Long) r[3], idle = (Long) r[4];
                    int pct = online > 0 ? (int) (active * 100 / online) : 0;
                    inv.setItem(slot++, head(UUID.fromString((String) r[0]), "&f" + r[1], Arrays.asList(
                            "&7Last 7 days",
                            "&7Online: &f" + duration(online),
                            "&7Active: &a" + duration(active),
                            "&7Idle: &c" + duration(idle),
                            "&7Active share: " + (pct >= 60 ? "&a" : pct >= 30 ? "&e" : "&c") + pct + "%")));
                }
                inv.setItem(49, item(Material.BARRIER, "&cBack", null));
                viewer.openInventory(inv);
            });
        });
    }

    /* ------------------------------------------------------------------ */
    /*  GUI: most AFK                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * Staff ranked by how much time they have spent idle, worst first.
     *
     * <p>This deliberately does NOT filter on who is idle right now. The old
     * version listed whoever happened to be idling at the moment it was opened,
     * which meant the staff member who idles eight hours a day looked clean the
     * instant they moved, and someone who stepped away to answer the door
     * looked like the problem. What matters when reviewing staff is the total
     * across the window, so that is what is ranked. Current state is still
     * shown on each entry, as information rather than as a filter.
     *
     * <p>Offline staff are included for the same reason: leaving the server
     * should not erase the record.
     */
    private void openAfk(Player viewer) {
        async(() -> {
            List<Object[]> rows = new ArrayList<>();
            int days = Math.max(1, this.afkBoardDays);
            String from = LocalDate.now().minusDays(days - 1L).toString();
            try (PreparedStatement ps = this.db.prepareStatement(
                    "SELECT uuid, name, SUM(online_ms) o, SUM(active_ms) a, SUM(idle_ms) i,"
                    + " COUNT(*) d FROM presence WHERE day>=? GROUP BY uuid"
                    + " ORDER BY i DESC LIMIT 45")) {
                ps.setString(1, from);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Object[]{ rs.getString("uuid"), rs.getString("name"),
                                rs.getLong("o"), rs.getLong("a"), rs.getLong("i"),
                                rs.getInt("d") });
                    }
                }
            } catch (SQLException ex) {
                sync(() -> viewer.sendMessage(ChatColor.RED + "Query failed: " + ex.getMessage()));
                return;
            }
            sync(() -> {
                Menu m = new Menu("afk", null, 0, null);
                Inventory inv = make(m, 6, this.guiTitleAfk);
                for (int i = 45; i < 54; i++) inv.setItem(i, filler());
                long now = System.currentTimeMillis();
                int slot = 0;
                int rank = 1;
                for (Object[] r : rows) {
                    if (slot >= 45) break;
                    UUID id = UUID.fromString((String) r[0]);
                    long online = (Long) r[2], idle = (Long) r[4];
                    int days2 = (Integer) r[5];
                    if (idle <= 0L) continue;
                    int pct = online > 0 ? (int) (idle * 100 / online) : 0;

                    // Current state is reported, never used to rank or filter.
                    Player live = Bukkit.getPlayer(id);
                    String state;
                    if (live == null) {
                        state = "&8offline";
                    } else {
                        long idleFor = now - this.lastActivity.getOrDefault(id, now);
                        state = idleFor >= this.idleAfterMs
                                ? "&cidle now &7(" + duration(idleFor) + ")"
                                : "&aactive now";
                    }

                    inv.setItem(slot++, head(id, rankColour(rank) + "#" + rank + " &f" + r[1],
                            Arrays.asList(
                                    "&7Last &f" + days + " &7days",
                                    "&7Idle total: &c" + duration(idle),
                                    "&7Online total: &f" + duration(online),
                                    "&7Idle share: " + shareColour(pct) + pct + "%",
                                    "&7Days recorded: &f" + days2,
                                    " ",
                                    "&7Right now: " + state)));
                    rank++;
                }
                if (slot == 0) {
                    inv.setItem(22, item(Material.LIME_DYE, "&aNo idle time recorded",
                            Arrays.asList("&7Nothing in the last " + days + " days.",
                                    "&8Only holders of staffmonitor.tracked are recorded.")));
                }
                inv.setItem(48, item(Material.CLOCK, "&eRanked by total idle time",
                        Arrays.asList("&7Worst first, across &f" + days + " &7days.",
                                "&7Being active right now does not remove",
                                "&7anyone from this list.")));
                inv.setItem(49, item(Material.BARRIER, "&cBack", null));
                viewer.openInventory(inv);
            });
        });
    }

    private static String rankColour(int rank) {
        return switch (rank) {
            case 1 -> "&4";
            case 2 -> "&c";
            case 3 -> "&#EEBB01";
            default -> "&7";
        };
    }

    private static String shareColour(int pct) {
        return pct >= 60 ? "&4" : pct >= 40 ? "&c" : pct >= 20 ? "&e" : "&a";
    }

    /* ------------------------------------------------------------------ */
    /*  Clicks                                                            */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.NORMAL)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu m)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;

        switch (m.kind) {
            case "main" -> {
                if (slot == 48) { openTop(p); return; }
                if (slot == 49) { openActivity(p); return; }
                if (slot == 50) { openAfk(p); return; }
                if (slot < 45 && clicked.getType() == Material.PLAYER_HEAD) {
                    ItemMeta meta = clicked.getItemMeta();
                    if (meta instanceof SkullMeta sm && sm.getOwningPlayer() != null) {
                        openTimeline(p, sm.getOwningPlayer().getUniqueId(), 0, "ALL");
                    }
                }
            }
            case "timeline" -> {
                switch (slot) {
                    case 45 -> openTimeline(p, m.subject, Math.max(0, m.page - 1), m.filter);
                    case 53 -> openTimeline(p, m.subject, m.page + 1, m.filter);
                    case 46 -> openMain(p);
                    case 48 -> openTimeline(p, m.subject, 0, "PUNISH");
                    case 49 -> openTimeline(p, m.subject, 0, "TELEPORT");
                    case 50 -> openTimeline(p, m.subject, 0, "GIVE");
                    case 51 -> openTimeline(p, m.subject, 0, "VANISH");
                    case 52 -> openTimeline(p, m.subject, 0, "SUS");
                    default -> { }
                }
            }
            case "top", "activity", "afk" -> {
                if (clicked.getType() == Material.BARRIER) openMain(p);
            }
            default -> { }
        }
    }
}
