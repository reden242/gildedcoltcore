package com.coltcore.core.modules;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Anti-botting for the rewards ladders, in two halves.
 *
 * <h2>1. Active-play gate</h2>
 * A claim needs {@code anti-bot.active-minutes-required} minutes of
 * genuinely active play (default 30). Minutes accrue from a once-a-minute
 * task, and only for players who moved, interacted, broke or placed a
 * block, chatted or ran a command in the preceding window. Standing still
 * with an autoclicker earns nothing: clicks without movement still count
 * as activity, but pure idling never accrues.
 *
 * <h2>2. Alt-farming bans</h2>
 * Every successful claim records (uuid, ip, time) in a bounded log. When
 * more than {@code anti-bot.max-accounts-per-ip} distinct accounts claim
 * from one IP inside 24 hours, the IP and every account on it are banned
 * from all rewards <b>forever</b> — alts rotate names, they rarely rotate
 * addresses. Bans live in {@code antibot.yml} and are lifted only by an
 * explicit {@code /rewards pardon <player|ip>}.
 */
public final class AntibotGuard implements Listener {

    /** Rolling window for the alt-farming count. */
    static final long FARM_WINDOW_MS = 86_400_000L;
    /** Cap on the claim log; oldest entries fall off. */
    static final int MAX_LOG = 500;

    private final JavaPlugin plugin;
    private File dataFile;
    private FileConfiguration data;

    /** Last genuine activity per player. */
    private final Map<UUID, Long> lastActive = new HashMap<>();
    /** Accrued active minutes per player (persisted). */
    private final Map<UUID, Long> activeMinutes = new HashMap<>();

    private record Claim(String uuid, String ip, long time) { }
    private final Deque<Claim> claimLog = new ArrayDeque<>();

    private int taskId = -1;

    public AntibotGuard(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        load();
        this.taskId = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 1200L, 1200L).getTaskId();
    }

    public void disable() {
        if (this.taskId >= 0) {
            Bukkit.getScheduler().cancelTask(this.taskId);
            this.taskId = -1;
        }
        save();
        this.lastActive.clear();
        this.activeMinutes.clear();
    }

    public void reload() {
        load();
    }

    private void load() {
        if (!this.plugin.getDataFolder().exists()) this.plugin.getDataFolder().mkdirs();
        this.dataFile = new File(this.plugin.getDataFolder(), "antibot.yml");
        this.data = YamlConfiguration.loadConfiguration(this.dataFile);
        this.activeMinutes.clear();
        if (this.data.isConfigurationSection("active-minutes")) {
            for (String key : this.data.getConfigurationSection("active-minutes").getKeys(false)) {
                try {
                    this.activeMinutes.put(UUID.fromString(key),
                            this.data.getLong("active-minutes." + key, 0L));
                } catch (IllegalArgumentException ignored) { }
            }
        }
        this.claimLog.clear();
        for (String line : this.data.getStringList("claim-log")) {
            String[] parts = line.split("\\|", 3);
            if (parts.length != 3) continue;
            try {
                this.claimLog.addLast(new Claim(parts[0], parts[1], Long.parseLong(parts[2])));
            } catch (NumberFormatException ignored) { }
        }
        while (this.claimLog.size() > MAX_LOG) this.claimLog.removeFirst();
    }

    private void save() {
        if (this.data == null || this.dataFile == null) return;
        try {
            for (Map.Entry<UUID, Long> e : this.activeMinutes.entrySet()) {
                this.data.set("active-minutes." + e.getKey(), e.getValue());
            }
            List<String> log = new java.util.ArrayList<>();
            for (Claim c : this.claimLog) log.add(c.uuid() + "|" + c.ip() + "|" + c.time());
            this.data.set("claim-log", log);
            this.data.save(this.dataFile);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("Could not save antibot.yml: " + ex.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Activity tracking                                                 */
    /* ------------------------------------------------------------------ */

    private void touch(Player player) {
        if (player == null) return;
        this.lastActive.put(player.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        this.lastActive.remove(id);
        save();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // Block-change only: head-spinning in place is not playing.
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        touch(event.getPlayer());
    }

    /** Once a minute: credit everyone active in the preceding two minutes. */
    private void tick() {
        try {
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (Player player : Bukkit.getOnlinePlayers()) {
                Long last = this.lastActive.get(player.getUniqueId());
                if (last != null && now - last < 120_000L) {
                    UUID id = player.getUniqueId();
                    this.activeMinutes.put(id, this.activeMinutes.getOrDefault(id, 0L) + 1L);
                    changed = true;
                }
            }
            if (changed) save();
        } catch (Throwable ignored) {
            // Bookkeeping must never break the server thread.
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Claim gate                                                        */
    /* ------------------------------------------------------------------ */

    /** Active minutes accrued (persisted across restarts). */
    public long activeMinutes(Player player) {
        Long minutes = this.activeMinutes.get(player.getUniqueId());
        return minutes == null ? 0L : minutes;
    }

    /** Minutes of active play required before any claim. */
    public int requiredMinutes() {
        return Math.max(0, this.plugin.getConfig().getInt("anti-bot.active-minutes-required", 30));
    }

    /** Null when the player may claim, otherwise the denial message. */
    public String denyReason(Player player) {
        if (isBanned(player)) {
            return "&cYour account or connection is banned from rewards.";
        }
        long have = activeMinutes(player);
        int need = requiredMinutes();
        if (have < need) {
            return "&cPlay actively for &f" + (need - have)
                    + " &cmore minute(s) to claim rewards. Idling does not count.";
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /*  Bans                                                              */
    /* ------------------------------------------------------------------ */

    /** Player's IP, or "" when unavailable. */
    public static String ipOf(Player player) {
        try {
            if (player.getAddress() != null && player.getAddress().getAddress() != null) {
                return player.getAddress().getAddress().getHostAddress();
            }
        } catch (Throwable ignored) { }
        return "";
    }

    public boolean isUuidBanned(UUID id) {
        return this.data.getStringList("banned-uuids").contains(id.toString());
    }

    public boolean isIpBanned(String ip) {
        return !ip.isEmpty() && this.data.getStringList("banned-ips").contains(ip);
    }

    /** True when either the account or its connection is banned. */
    public boolean isBanned(Player player) {
        return isUuidBanned(player.getUniqueId()) || isIpBanned(ipOf(player));
    }

    /** Ban an account from all rewards forever. Returns false when already banned. */
    public boolean banUuid(UUID id) {
        List<String> banned = this.data.getStringList("banned-uuids");
        if (banned.contains(id.toString())) return false;
        banned.add(id.toString());
        this.data.set("banned-uuids", banned);
        save();
        return true;
    }

    /** Ban a connection from all rewards forever. Returns false when already banned. */
    public boolean banIp(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        List<String> banned = this.data.getStringList("banned-ips");
        if (banned.contains(ip)) return false;
        banned.add(ip);
        this.data.set("banned-ips", banned);
        save();
        return true;
    }

    /**
     * Lifts a ban by player name (resolves online player or offline UUID),
     * raw UUID, or IP. Returns what was lifted, or null.
     */
    public String pardon(String target) {
        if (target == null || target.isBlank()) return null;
        String t = target.trim();
        List<String> uuids = this.data.getStringList("banned-uuids");
        List<String> ips = this.data.getStringList("banned-ips");
        if (ips.remove(t)) {
            this.data.set("banned-ips", ips);
            save();
            return "IP " + t;
        }
        if (uuids.remove(t)) {
            this.data.set("banned-uuids", uuids);
            save();
            return "account " + t;
        }
        try {
            Player online = Bukkit.getPlayerExact(t);
            UUID id = online != null ? online.getUniqueId()
                    : Bukkit.getOfflinePlayer(t).getUniqueId();
            if (uuids.remove(id.toString())) {
                this.data.set("banned-uuids", uuids);
                save();
                return "account " + t;
            }
            if (online != null && ips.remove(ipOf(online))) {
                this.data.set("banned-ips", ips);
                save();
                return "IP of " + t;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /*  Alt-farming detection                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Records a successful claim and flags alt-farming: more than the
     * configured distinct accounts claiming from one IP inside 24 hours
     * bans the IP and every account on it, forever.
     */
    public void recordClaim(Player player) {
        String ip = ipOf(player);
        String uuid = player.getUniqueId().toString();
        long now = System.currentTimeMillis();
        synchronized (this.claimLog) {
            this.claimLog.addLast(new Claim(uuid, ip, now));
            while (this.claimLog.size() > MAX_LOG) this.claimLog.removeFirst();
        }
        save();
        if (ip.isEmpty()) return;
        int maxAccounts = Math.max(1, this.plugin.getConfig().getInt("anti-bot.max-accounts-per-ip", 3));
        Set<String> accounts = new HashSet<>();
        synchronized (this.claimLog) {
            for (Claim c : this.claimLog) {
                if (now - c.time() < FARM_WINDOW_MS && ip.equals(c.ip())) {
                    accounts.add(c.uuid());
                }
            }
        }
        if (accounts.size() > maxAccounts && banIp(ip)) {
            for (String account : accounts) {
                try {
                    banUuid(UUID.fromString(account));
                } catch (IllegalArgumentException ignored) { }
            }
            this.plugin.getLogger().warning("[AntiBot] farming ring: " + accounts.size()
                    + " accounts claiming from " + ip + " - IP and accounts banned from rewards.");
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("coltcore.rewards.admin")) {
                    staff.sendMessage(UiKit.colour("&c[AntiBot] &7Farming ring banned: &f"
                            + accounts.size() + " accounts &7on &f" + ip));
                }
            }
        }
    }
}
