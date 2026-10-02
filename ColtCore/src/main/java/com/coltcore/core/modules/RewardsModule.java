package com.coltcore.core.modules;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import com.coltcore.core.SchedulerCompat;

/**
 * Config-driven daily and cumulative-playtime rewards.
 *
 * <p>Daily claims are stored by date and use a consecutive-day streak. A
 * missed day resets the streak to one. Each configured daily entry has a
 * {@code day} number; the highest entry at or below the streak is selected,
 * allowing day-one, day-three and day-seven rewards without code changes.
 * Playtime entries are one-time rewards selected by their {@code hours}
 * threshold. {@code /rewards claimall} grants the current daily reward and all
 * currently eligible, previously unclaimed playtime rewards in one action.
 */
public final class RewardsModule implements Listener {
    public static final String PERM_USE = "coltcore.rewards";
    public static final String PERM_ADMIN = "coltcore.rewards.admin";

    private final JavaPlugin plugin;
    private File dataFile;
    private FileConfiguration data;
    /** Anti-botting gate; null until the plugin wires it. */
    private AntibotGuard antibot;
    /** When each tracked player's current online session began, in epoch millis. */
    private final Map<UUID, Long> sessionStart = new ConcurrentHashMap<>();
    /** The date bucket each open session is accumulating into. */
    private final Map<UUID, String> sessionDate = new ConcurrentHashMap<>();
    private SchedulerCompat.ManagedTask presenceTask;

    public RewardsModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        load();
        this.presenceTask = SchedulerCompat.timer(this.plugin, this::flushAll, 20L * 60L, 20L * 60L);
    }

    public void disable() {
        if (this.presenceTask != null) { this.presenceTask.cancel(); this.presenceTask = null; }
        flushAll();
        save();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (SyntheticPlayerLoader.isSynthetic(event.getPlayer())) return;
        this.sessionStart.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        this.sessionDate.put(event.getPlayer().getUniqueId(), today().toString());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (SyntheticPlayerLoader.isSynthetic(event.getPlayer())) return;
        flush(event.getPlayer().getUniqueId());
    }

    /**
     * A day only counts once the player has actually been here for the configured
     * minimum, so leaving after a few seconds never advances the streak and a
     * missed day can never be back-claimed for time that was never spent.
     */
    public long minimumOnlineSeconds() {
        return Math.max(0L, this.plugin.getConfig().getLong("daily-rewards.minimum-online-minutes", 10L)) * 60L;
    }

    private long maxBackClaimDays() {
        return Math.max(1L, this.plugin.getConfig().getLong("daily-rewards.max-back-claim-days", 31L));
    }

    /** Adds elapsed online time to today's bucket and the lifetime playtime total. */
    private void flush(UUID id) {
        Long started = this.sessionStart.remove(id);
        String date = this.sessionDate.remove(id);
        if (started == null || date == null || this.data == null) return;
        long elapsed = (System.currentTimeMillis() - started) / 1000L;
        if (elapsed <= 0) return;
        String key = "players." + id + ".daily.seconds." + date;
        this.data.set(key, this.data.getLong(key, 0L) + elapsed);
        String total = "players." + id + ".playtime.total-seconds";
        this.data.set(total, this.data.getLong(total, 0L) + elapsed);
        // Re-arm the session so periodic flushes don't erase live time.
        // onQuit also lands here, but a quitting player is already offline,
        // so this only re-arms players who are actually still here.
        Player online = Bukkit.getPlayer(id);
        if (online != null && online.isOnline()) {
            this.sessionStart.put(id, System.currentTimeMillis());
            this.sessionDate.put(id, today().toString());
        }
    }

    private void flushAll() {
        if (this.data == null) return;
        for (UUID id : new ArrayList<>(this.sessionStart.keySet())) flush(id);
        save();
    }

    /** Online seconds banked for a date, including the live session if it is today. */
    public long onlineSeconds(UUID id, LocalDate date) {
        if (this.data == null) return 0L;
        long banked = this.data.getLong("players." + id + ".daily.seconds." + date, 0L);
        if (today().equals(date)) {
            Long started = this.sessionStart.get(id);
            if (started != null) banked += (System.currentTimeMillis() - started) / 1000L;
        }
        return banked;
    }

    public boolean dailyEligible(Player player) {
        return onlineSeconds(player.getUniqueId(), today()) >= minimumOnlineSeconds();
    }

    public boolean dailyClaimedToday(Player player) {
        if (this.data == null) return false;
        return this.data.getBoolean("players." + player.getUniqueId()
                + ".daily.claims." + today(), false);
    }

    /** Every past-or-today date that earned a day but has not been claimed. */
    public List<LocalDate> unclaimedEligibleDates(Player player) {
        List<LocalDate> out = new ArrayList<>();
        if (this.data == null) return out;
        String root = "players." + player.getUniqueId() + ".daily.seconds.";
        ConfigurationSection section = this.data.getConfigurationSection(root);
        if (section == null) return out;
        LocalDate today = today();
        long need = minimumOnlineSeconds();
        for (String key : section.getKeys(false)) {
            LocalDate date;
            try {
                date = LocalDate.parse(key);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (date.isAfter(today)) continue;
            if (section.getLong(key, 0L) < need) continue;
            if (this.data.getBoolean("players." + player.getUniqueId() + ".daily.claims." + key, false)) continue;
            out.add(date);
        }
        out.sort(Comparator.naturalOrder());
        long cap = maxBackClaimDays();
        if (out.size() > cap) out = new ArrayList<>(out.subList(out.size() - (int) cap, out.size()));
        return out;
    }

    public int streak(Player player) {
        return this.data == null ? 0 : this.data.getInt(base(player) + ".daily.streak", 0);
    }

    public void reload() {
        load();
        if (this.antibot != null) {
            try {
                this.antibot.reload();
            } catch (Throwable ignored) { }
        }
    }

    /** Wires the anti-botting gate from the main class. */
    public void setAntibot(AntibotGuard guard) { this.antibot = guard; }

    /** Denial message when the player may not claim, or null. */
    private String antibotDeny(Player player) {
        if (this.antibot == null) return null;
        try {
            return this.antibot.denyReason(player);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void load() {
        if (!this.plugin.getDataFolder().exists()) this.plugin.getDataFolder().mkdirs();
        this.dataFile = new File(this.plugin.getDataFolder(), "rewards.yml");
        this.data = YamlConfiguration.loadConfiguration(this.dataFile);
    }

    private void save() {
        if (this.data == null || this.dataFile == null) return;
        try {
            this.data.save(this.dataFile);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("Could not save rewards.yml: " + ex.getMessage());
        }
    }

    public boolean command(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(PERM_ADMIN) && !sender.isOp()) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to reload rewards. (" + PERM_ADMIN + ")");
                return true;
            }
            reload();
            sender.sendMessage(ChatColor.GREEN + "Rewards configuration reloaded.");
            return true;
        }
        if (args.length > 1 && args[0].equalsIgnoreCase("resetplaytime")) {
            if (!sender.hasPermission(PERM_ADMIN) && !sender.isOp()) {
                sender.sendMessage(ChatColor.RED + "You do not have permission. (" + PERM_ADMIN + ")");
                return true;
            }
            UUID target = parseUuid(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Unknown player: " + args[1]);
                return true;
            }
            resetPlaytime(target);
            sender.sendMessage(ChatColor.GREEN + "Reset tracked playtime and playtime reward claims for "
                    + args[1] + ".");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can claim rewards.");
            return true;
        }
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "daily" -> {
                String denied = antibotDeny(player);
                if (denied != null) {
                    player.sendMessage(UiKit.colour(denied));
                    yield true;
                }
                int claimed = claimDaily(player, true);
                if (claimed > 0 && this.antibot != null) this.antibot.recordClaim(player);
                yield true;
            }
            case "playtime", "time" -> {
                String denied = antibotDeny(player);
                if (denied != null) {
                    player.sendMessage(UiKit.colour(denied));
                    yield true;
                }
                int claimed = claimPlaytime(player, true);
                if (claimed > 0 && this.antibot != null) this.antibot.recordClaim(player);
                yield true;
            }
            case "claimall", "all" -> {
                String denied = antibotDeny(player);
                if (denied != null) {
                    player.sendMessage(UiKit.colour(denied));
                    yield true;
                }
                int total = claimDaily(player, false) + claimPlaytime(player, false);
                if (total == 0) player.sendMessage(ChatColor.YELLOW + "You have no rewards ready to claim.");
                else player.sendMessage(ChatColor.GREEN + "Claimed " + total + " reward" + (total == 1 ? "" : "s") + ".");
                if (total > 0 && this.antibot != null) this.antibot.recordClaim(player);
                yield true;
            }
            case "ban" -> {
                yield antibotBan(sender, args);
            }
            case "pardon", "unban" -> {
                yield antibotPardon(sender, args);
            }
            case "status", "help" -> {
                status(player);
                yield true;
            }
            default -> {
                player.sendMessage(ChatColor.YELLOW + "/rewards <daily|playtime|claimall|status>");
                yield true;
            }
        };
    }

    private boolean antibotBan(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_ADMIN) && !sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to ban from rewards. (" + PERM_ADMIN + ")");
            return true;
        }
        if (this.antibot == null) {
            sender.sendMessage(ChatColor.RED + "Anti-botting is not wired up.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "/rewards ban <player> - bans the account and its IP forever.");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        java.util.UUID id = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(args[1]).getUniqueId();
        boolean uuidNew = this.antibot.banUuid(id);
        boolean ipNew = target != null && this.antibot.banIp(AntibotGuard.ipOf(target));
        sender.sendMessage(UiKit.colour("&cBanned &f" + args[1] + " &cfrom rewards forever"
                + (uuidNew ? "" : " &8(already banned)")
                + (target != null ? (ipNew ? " &7+ IP &f" + AntibotGuard.ipOf(target) : " &8(IP already banned)") : "")));
        return true;
    }

    private boolean antibotPardon(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_ADMIN) && !sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to pardon rewards bans. (" + PERM_ADMIN + ")");
            return true;
        }
        if (this.antibot == null) {
            sender.sendMessage(ChatColor.RED + "Anti-botting is not wired up.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "/rewards pardon <player|uuid|ip> - lifts a rewards ban.");
            return true;
        }
        String lifted = this.antibot.pardon(args[1]);
        if (lifted == null) sender.sendMessage(ChatColor.YELLOW + "No rewards ban found for '" + args[1] + "'.");
        else sender.sendMessage(UiKit.colour("&aLifted rewards ban: &f" + lifted + "&a."));
        return true;
    }

    private int claimDaily(Player player, boolean tell) {
        if (!this.plugin.getConfig().getBoolean("daily-rewards.enabled", true)) {
            if (tell) player.sendMessage(ChatColor.RED + "Daily rewards are disabled.");
            return 0;
        }
        long need = minimumOnlineSeconds();
        List<LocalDate> dates = unclaimedEligibleDates(player);
        if (dates.isEmpty()) {
            if (tell) {
                long have = onlineSeconds(player.getUniqueId(), today());
                if (have < need) {
                    long remaining = need - have;
                    player.sendMessage(ChatColor.YELLOW + "You need " + (need / 60L)
                            + " minutes online to count a day. "
                            + Math.max(1L, (remaining + 59L) / 60L) + " more to go.");
                } else {
                    player.sendMessage(ChatColor.YELLOW + "You have no daily rewards ready to claim.");
                }
            }
            return 0;
        }

        // Only the newest eligible date advances the streak; older back-claims are
        // paid at their own day so nobody can farm a long streak by waiting.
        LocalDate newest = dates.get(dates.size() - 1);
        LocalDate previous = newest.minusDays(1);
        int claimed = 0;
        for (LocalDate date : dates) {
            int day;
            if (date.equals(newest)) {
                String lastClaimed = newestClaimedDate(player);
                int old = this.data.getInt(base(player) + ".daily.streak", 0);
                day = previous.toString().equals(lastClaimed) ? old + 1 : 1;
            } else {
                day = Math.max(1, streakForDate(player, date));
            }
            Reward reward = dailyReward(day);
            if (reward == null) continue;
            execute(player, reward, day, playtimeHours(player));
            this.data.set(base(player) + ".daily.claims." + date, true);
            this.data.set(base(player) + ".daily.streak", day);
            this.data.set(base(player) + ".daily.last-date", date.toString());
            claimed++;
            if (tell && date.equals(newest)) {
                player.sendMessage(ChatColor.GREEN + "Claimed daily reward " + ChatColor.WHITE + reward.id
                        + ChatColor.GREEN + " (day " + day + ").");
            }
        }
        if (claimed > 0) {
            save();
            if (tell && claimed > 1) {
                player.sendMessage(ChatColor.GREEN + "Also back-claimed " + (claimed - 1)
                        + " earlier day(s) you had earned.");
            }
        }
        return claimed;
    }

    /** Most recent date with a claim record, or empty. */
    private String newestClaimedDate(Player player) {
        String root = base(player) + ".daily.claims.";
        ConfigurationSection section = this.data.getConfigurationSection(root);
        if (section == null) return "";
        String best = "";
        for (String key : section.getKeys(false)) {
            if (!section.getBoolean(key, false)) continue;
            if (key.compareTo(best) > 0) best = key;
        }
        return best;
    }

    /** Streak a back-claimed date would have carried, counting only earned days. */
    private int streakForDate(Player player, LocalDate date) {
        String prefix = base(player) + ".daily.seconds.";
        ConfigurationSection section = this.data.getConfigurationSection(prefix);
        if (section == null) return 1;
        long need = minimumOnlineSeconds();
        int streak = 0;
        for (LocalDate cursor = date; ; cursor = cursor.minusDays(1)) {
            long seconds = section.getLong(cursor.toString(), 0L);
            boolean claimed = this.data.getBoolean(base(player) + ".daily.claims." + cursor, false);
            if (seconds < need && !claimed) break;
            streak++;
            if (cursor.equals(LocalDate.of(1970, 1, 1))) break;
        }
        return streak;
    }

    /**
     * True when this specific playtime tier has already been paid out.
     *
     * <p>Reads the same {@code .playtime.claimed.<id>} key that
     * {@link #claimPlaytime} writes, so the GUI can grey out a tier the moment
     * it is claimed instead of inferring it from the hours total.
     */
    public boolean playtimeTierClaimed(Player player, String rewardId) {
        return this.data.getBoolean(base(player) + ".playtime.claimed." + rewardId, false);
    }

    /** True when the player has enough minutes for this tier and it is unpaid. */
    public boolean playtimeTierReady(Player player, Reward reward) {
        return playtimeMinutes(player) >= reward.threshold
                && !playtimeTierClaimed(player, reward.id);
    }

    private int claimPlaytime(Player player, boolean tell) {
        if (!this.plugin.getConfig().getBoolean("playtime-rewards.enabled", true)) {
            if (tell) player.sendMessage(ChatColor.RED + "Playtime rewards are disabled.");
            return 0;
        }
        long minutes = playtimeMinutes(player);
        String base = base(player) + ".playtime.claimed";
        int claimed = 0;
        for (Reward reward : playtimeRewards()) {
            if (minutes < reward.threshold || this.data.getBoolean(base + "." + reward.id, false)) continue;
            execute(player, reward, 0, playtimeHours(player));
            this.data.set(base + "." + reward.id, true);
            claimed++;
            if (tell) player.sendMessage(ChatColor.GREEN + "Claimed playtime reward " + ChatColor.WHITE + reward.id
                    + ChatColor.GREEN + " (" + formatMinutes(reward.threshold) + ").");
        }
        if (claimed > 0) save();
        else if (tell) player.sendMessage(ChatColor.YELLOW + "You have no playtime rewards ready to claim.");
        return claimed;
    }

    /**
     * Claims one playtime tier from the GUI. Returns true when paid.
     * Uses the same keys as {@link #claimPlaytime} so text and GUI claims
     * can never double-pay.
     */
    public boolean claimPlaytimeTier(Player player, String rewardId) {
        if (!this.plugin.getConfig().getBoolean("playtime-rewards.enabled", true)) {
            return false;
        }
        Reward found = null;
        for (Reward reward : playtimeRewards()) {
            if (reward.id.equals(rewardId)) {
                found = reward;
                break;
            }
        }
        if (found == null || !playtimeTierReady(player, found)) {
            return false;
        }
        execute(player, found, 0, playtimeHours(player));
        this.data.set(base(player) + ".playtime.claimed." + found.id, true);
        save();
        return true;
    }

    /**
     * Claims today's daily reward from the GUI (single day, no back-claim
     * sweep). Returns true when paid. Mirrors the newest-date branch of
     * {@link #claimDaily} so text and GUI claims share one streak.
     */
    public boolean claimCurrentDay(Player player) {
        if (!this.plugin.getConfig().getBoolean("daily-rewards.enabled", true)) {
            return false;
        }
        LocalDate today = today();
        long need = minimumOnlineSeconds();
        long have = onlineSeconds(player.getUniqueId(), today);
        if (have < need || dailyClaimedToday(player)) {
            return false;
        }
        String lastClaimed = newestClaimedDate(player);
        int old = this.data.getInt(base(player) + ".daily.streak", 0);
        int day = today.minusDays(1).toString().equals(lastClaimed) ? old + 1 : 1;
        Reward reward = dailyReward(Math.max(1, day));
        if (reward == null) {
            return false;
        }
        execute(player, reward, day, playtimeHours(player));
        this.data.set(base(player) + ".daily.claims." + today, true);
        this.data.set(base(player) + ".daily.streak", day);
        this.data.set(base(player) + ".daily.last-date", today.toString());
        save();
        return true;
    }

    /* ================================================================== */
    /*  V2 model: next-day pointer, pending queue, completion flags, all    */
    /*  driven by online seconds already tracked in rewards.yml (join/quit */
    /*  + 60s flush). No separate AFK tracker: AntibotGuard remains the    */
    /*  anti-farming gate at claim time. One-time migration maps the       */
    /*  legacy streak onto next_day so nobody loses progress.               */
    /* ================================================================== */

    private static final int MAX_DAILY_DAY = 30;
    private static final int MAX_PLAYTIME_HOUR = 200;
    private static final long CLAIM_LOCK_MILLIS = 2000L;

    private final Map<UUID, Long> claimLock = new ConcurrentHashMap<>();

    private String v2base(Player player) {
        return base(player);
    }

    private String v2base(UUID id) {
        return "players." + id;
    }

    /** Online seconds today (rewards.yml bucket + live session). */
    public long activeToday(UUID id) {
        return onlineSeconds(id, today());
    }

    /** Lifetime online seconds (rewards.yml total + live session). */
    public long activeTotal(UUID id) {
        if (this.data == null) return 0L;
        long seconds = this.data.getLong("players." + id + ".playtime.total-seconds", 0L);
        Long started = this.sessionStart.get(id);
        if (started != null) seconds += (System.currentTimeMillis() - started) / 1000L;
        return seconds;
    }

    /** Whole online hours for milestones. */
    public long activeHours(Player player) {
        return activeTotal(player.getUniqueId()) / 3600L;
    }

    /** Seconds of online play needed for a day to qualify (config, default 600). */
    public long dailyQualifySeconds() {
        return minimumOnlineSeconds();
    }

    private void migrateV2(Player player) {
        String root = v2base(player) + ".daily.v2migrated";
        if (this.data.getBoolean(root, false)) return;
        int legacyStreak = this.data.getInt(v2base(player) + ".daily.streak", 0);
        if (legacyStreak > 0) {
            int next = Math.min(MAX_DAILY_DAY, legacyStreak + 1);
            this.data.set(v2base(player) + ".daily.next_day", next);
            String lastDate = this.data.getString(v2base(player) + ".daily.last-date", "");
            // Carry the legacy date forward only when it is still inside the
            // 3-day safety window. A weeks-old date imported as-is makes the
            // first /dailyrewards after an upgrade fire a reset the player
            // never earned; stale progress keeps the streak but not the clock.
            if (!lastDate.isEmpty()) {
                try {
                    long gap = today().toEpochDay() - LocalDate.parse(lastDate).toEpochDay();
                    if (gap > 0 && gap < 4) {
                        this.data.set(v2base(player) + ".daily.last_qualified_date", lastDate);
                    }
                } catch (Exception ignored) {
                    // Unparseable legacy value: keep streak, drop the clock.
                }
            }
        }
        this.data.set(root, true);
        save();
    }

    public int dailyNextDay(Player player) {
        migrateV2(player);
        int next = this.data.getInt(v2base(player) + ".daily.next_day", 0);
        if (next < 1 || next > MAX_DAILY_DAY) {
            next = 1;
            this.data.set(v2base(player) + ".daily.next_day", next);
            save();
        }
        return next;
    }

    public int dailyPending(Player player) {
        migrateV2(player);
        return Math.max(0, this.data.getInt(v2base(player) + ".daily.pending", 0));
    }

    public boolean dailyCompleted(Player player) {
        return this.data.getBoolean(v2base(player) + ".daily.completed", false);
    }

    public boolean playtimeCompleted(Player player) {
        return this.data.getBoolean(v2base(player) + ".playtime.completed", false);
    }

    public LocalDate dailyLastQualified(Player player) {
        migrateV2(player);
        String s = this.data.getString(v2base(player) + ".daily.last_qualified_date", "");
        if (s == null || s.isEmpty()) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Full missed days excluding today (still in progress) when today is not
     * yet qualified, or excluding nothing when it is. Drives the safety
     * counter: safe while missed &lt; 3.
     */
    public int dailyMissedDays(Player player) {
        LocalDate last = dailyLastQualified(player);
        if (last == null) return 0;
        LocalDate today = today();
        String todayStr = today.toString();
        String lastStr = last.toString();
        if (lastStr.equals(todayStr)) return 0;
        long gap = today.toEpochDay() - last.toEpochDay();
        if (gap <= 0) return 0;
        // Today still open and unqualified: don't count it as missed yet.
        if (!todayStr.equals(this.data.getString(v2base(player) + ".daily.last_qualified_date", ""))) {
            return (int) Math.max(0L, gap - 1L);
        }
        return (int) Math.max(0L, gap - 1L);
    }

    /** Days of safety left before a reset (3 - missed, floor 0). */
    public int dailySafeDaysLeft(Player player) {
        return Math.max(0, 3 - dailyMissedDays(player));
    }

    /**
     * Lazy reset check. Runs on join, GUI open and qualification. A gap of 4+
     * days since the last qualification means 3+ consecutive missed days:
     * next_day returns to 1, pending clears, and the player is told.
     * Returns true when a reset fired.
     */
    public boolean checkDailyReset(Player player) {
        if (dailyCompleted(player)) return false;
        LocalDate last = dailyLastQualified(player);
        if (last == null) return false;
        LocalDate today = today();
        long gap = today.toEpochDay() - last.toEpochDay();
        if (gap < 4) return false;
        int wasDay = dailyNextDay(player);
        int wasPending = dailyPending(player);
        this.data.set(v2base(player) + ".daily.next_day", 1);
        this.data.set(v2base(player) + ".daily.pending", 0);
        save();
        // Silent when there was nothing to lose: a fresh profile sitting on
        // Day 1 with no pending claims has no streak that "reset".
        if (wasDay > 1 || wasPending > 0) {
            player.sendMessage(ChatColor.RED + "Your streak reset to Day 1 after 3 missed days.");
        }
        return true;
    }

    /**
     * Marks today qualified when dailyQualifySeconds()+ online seconds are
     * banked. Called lazily (join, GUI open) and by the periodic qualifier.
     * Each call adds at most one pending day and refreshes
     * last_qualified_date. Returns true on a new qualification.
     */
    public boolean qualifyDaily(Player player) {
        if (dailyCompleted(player)) return false;
        checkDailyReset(player);
        LocalDate today = today();
        String todayStr = today.toString();
        String lastStr = this.data.getString(v2base(player) + ".daily.last_qualified_date", "");
        if (todayStr.equals(lastStr)) return false;
        if (activeToday(player.getUniqueId()) < dailyQualifySeconds()) return false;
        int pending = dailyPending(player);
        this.data.set(v2base(player) + ".daily.pending", pending + 1);
        this.data.set(v2base(player) + ".daily.last_qualified_date", todayStr);
        save();
        int next = dailyNextDay(player);
        long mins = Math.max(1L, dailyQualifySeconds() / 60L);
        player.sendMessage(ChatColor.GREEN + "You've reached " + mins + " minutes online. Day "
                + next + " is ready to claim.");
        return true;
    }

    /** Reward configured for exactly this day number, highest at-or-below fallback. */
    public Reward dailyRewardForDay(int day) {
        Reward selected = null;
        for (Reward reward : configured("daily-rewards.rewards", "day")) {
            if (reward.threshold() <= day && (selected == null || reward.threshold() > selected.threshold())) {
                selected = reward;
            }
        }
        return selected;
    }

    /**
     * Claim button logic: pays daily_next_day from one pending claim, then
     * advances. Inventory-full protection keeps the claim pending when a
     * physical item has nowhere to go. Day 30 sets completed + broadcasts.
     */
    public boolean claimDailyNext(Player player) {
        if (!acquireClaimLock(player)) return false;
        try {
            return claimDailyNextInner(player);
        } finally {
            releaseClaimLock(player);
        }
    }

    private boolean claimDailyNextInner(Player player) {
        if (dailyCompleted(player)) return false;
        checkDailyReset(player);
        int pending = dailyPending(player);
        if (pending <= 0) return false;
        int day = dailyNextDay(player);
        Reward reward = dailyRewardForDay(day);
        if (reward == null) return false;
        if (!hasRoomFor(player, reward)) {
            player.sendMessage(ChatColor.RED + "Make room in your inventory first - claim kept pending.");
            return false;
        }
        execute(player, reward, day, playtimeHours(player));
        this.data.set(v2base(player) + ".daily.pending", pending - 1);
        if (day >= MAX_DAILY_DAY) {
            this.data.set(v2base(player) + ".daily.completed", true);
            this.data.set(v2base(player) + ".daily.pending", 0);
            save();
            String broadcast = this.plugin.getConfig().getString("daily-rewards.complete-broadcast",
                    "&6&l%player% &7completed the &e30-day rewards&7!");
            if (broadcast != null && !broadcast.isEmpty()) {
                Bukkit.broadcastMessage(UiKit.colour(broadcast.replace("%player%", player.getName())));
            }
        } else {
            this.data.set(v2base(player) + ".daily.next_day", day + 1);
            save();
        }
        return true;
    }

    /** True when any reward command would place a physical item. */
    private boolean hasRoomFor(Player player, Reward reward) {
        boolean needsSlot = false;
        for (String command : reward.commands()) {
            String lower = command.trim().toLowerCase(Locale.ROOT);
            if (lower.startsWith("give ") && !lower.startsWith("crate give")
                    && !lower.startsWith("shard give") && !lower.startsWith("eco give")) {
                needsSlot = true;
                break;
            }
        }
        if (!needsSlot) return true;
        try {
            return player.getInventory().firstEmpty() != -1;
        } catch (Exception e) {
            return true;
        }
    }

    private boolean acquireClaimLock(Player player) {
        long now = System.currentTimeMillis();
        Long until = this.claimLock.get(player.getUniqueId());
        if (until != null && until > now) return false;
        this.claimLock.put(player.getUniqueId(), now + CLAIM_LOCK_MILLIS);
        return true;
    }

    private void releaseClaimLock(Player player) {
        // Keep the expiry briefly so a double-click inside the window still
        // collapses; the timestamp, not removal, is the lock.
    }

    /** Claimed milestone hours, ascending. */
    public List<Integer> playtimeClaimedHours(Player player) {
        List<Integer> out = new ArrayList<>();
        String root = v2base(player) + ".playtime.claimed-hours";
        for (int h : this.data.getIntegerList(root)) {
            if (h > 0) out.add(h);
        }
        java.util.Collections.sort(out);
        return out;
    }

    public boolean playtimeHourClaimed(Player player, int hour) {
        return playtimeClaimedHours(player).contains(hour);
    }

    /** Playtime reward configured for exactly this hour, or null. */
    public Reward playtimeRewardForHour(int hour) {
        for (Reward reward : playtimeRewards()) {
            if (reward.threshold() == (long) hour * 60L) {
                return reward;
            }
        }
        return null;
    }

    /** All configured playtime milestone hours, ascending. */
    public List<Integer> playtimeMilestoneHours() {
        List<Integer> out = new ArrayList<>();
        for (Reward reward : playtimeRewards()) {
            long minutes = reward.threshold();
            if (minutes % 60L == 0L) {
                out.add((int) (minutes / 60L));
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    public boolean playtimeHourReady(Player player, int hour) {
        if (playtimeCompleted(player)) return false;
        if (playtimeHourClaimed(player, hour)) return false;
        return activeHours(player) >= hour;
    }

    /**
     * Claims one playtime milestone by hour number. Hour 200 sets completed
     * + broadcasts. Returns true when paid.
     */
    public boolean claimPlaytimeHour(Player player, int hour) {
        if (!acquireClaimLock(player)) return false;
        try {
            return claimPlaytimeHourUnlocked(player, hour);
        } finally {
            releaseClaimLock(player);
        }
    }

    /** Core claim without the lock; callers hold it (or hold it for a batch). */
    private boolean claimPlaytimeHourUnlocked(Player player, int hour) {
        if (playtimeCompleted(player) || playtimeHourClaimed(player, hour)) return false;
        if (activeHours(player) < hour) return false;
        Reward reward = playtimeRewardForHour(hour);
        if (reward == null) return false;
        if (!hasRoomFor(player, reward)) {
            player.sendMessage(ChatColor.RED + "Make room in your inventory first.");
            return false;
        }
        execute(player, reward, 0, playtimeHours(player));
        List<Integer> claimed = playtimeClaimedHours(player);
        if (!claimed.contains(hour)) claimed.add(hour);
        java.util.Collections.sort(claimed);
        this.data.set(v2base(player) + ".playtime.claimed-hours", claimed);
        if (hour >= MAX_PLAYTIME_HOUR) {
            this.data.set(v2base(player) + ".playtime.completed", true);
            save();
            String broadcast = this.plugin.getConfig().getString("playtime-rewards.complete-broadcast",
                    "&6&l%player% &7completed the &e200-hour rewards&7!");
            if (broadcast != null && !broadcast.isEmpty()) {
                Bukkit.broadcastMessage(UiKit.colour(broadcast.replace("%player%", player.getName())));
            }
        } else {
            save();
        }
        return true;
    }

    /** Claims every reached, unclaimed milestone in ascending hour order. */
    public int claimAllPlaytime(Player player) {
        if (!acquireClaimLock(player)) return 0;
        int paid = 0;
        try {
            for (int hour : playtimeMilestoneHours()) {
                if (playtimeCompleted(player)) break;
                if (claimPlaytimeHourUnlocked(player, hour)) paid++;
            }
        } finally {
            releaseClaimLock(player);
        }
        return paid;
    }

    private void status(Player player) {
        long hours = playtimeHours(player);
        String base = base(player);
        LocalDate today = today();
        long need = minimumOnlineSeconds();
        long have = onlineSeconds(player.getUniqueId(), today);
        boolean eligible = have >= need;
        boolean claimedToday = dailyClaimedToday(player);
        List<LocalDate> pending = unclaimedEligibleDates(player);
        int timeReady = 0;
        long mins = playtimeMinutes(player);
        for (Reward reward : playtimeRewards()) {
            if (mins >= reward.threshold && !this.data.getBoolean(base + ".playtime.claimed." + reward.id, false)) timeReady++;
        }
        player.sendMessage(UiKit.colour("&#00ff00Rewards &8| &f" + hours + " hour"
                + (hours == 1 ? "" : "s") + " played"));
        player.sendMessage(ChatColor.GRAY + "Today online: " + ChatColor.WHITE + (have / 60L) + "m"
                + ChatColor.GRAY + " (need " + (need / 60L) + "m)");
        if (claimedToday) {
            player.sendMessage(ChatColor.GRAY + "Daily: " + ChatColor.YELLOW + "claimed today"
                    + ChatColor.GRAY + ", streak " + ChatColor.WHITE + streak(player));
        } else if (eligible) {
            player.sendMessage(ChatColor.GRAY + "Daily: " + ChatColor.GREEN + "ready");
        } else {
            player.sendMessage(ChatColor.GRAY + "Daily: " + ChatColor.YELLOW + "not enough time today");
        }
        if (!pending.isEmpty() && pending.size() > (claimedToday ? 0 : 1)) {
            player.sendMessage(ChatColor.GRAY + "Back-claimable days: " + ChatColor.WHITE + pending.size());
        }
        player.sendMessage(ChatColor.GRAY + "Playtime: " + (timeReady == 0 ? ChatColor.YELLOW + "none ready" : ChatColor.GREEN + String.valueOf(timeReady) + " ready"));
        player.sendMessage(ChatColor.GRAY + "Use " + ChatColor.WHITE + "/rewards claimall" + ChatColor.GRAY + " to claim everything available.");
    }

    private Reward dailyReward(int streak) {
        Reward selected = null;
        for (Reward reward : configured("daily-rewards.rewards", "day")) {
            if (reward.threshold <= streak && (selected == null || reward.threshold > selected.threshold)) selected = reward;
        }
        return selected;
    }

    /** The daily entry the GUI shows for a streak, or null when unconfigured. */
    public Reward dailyRewardFor(int streak) {
        return dailyReward(Math.max(1, streak));
    }

    /** Every configured daily entry, ascending by day. Drives the paged GUI. */
    public List<Reward> dailyRewards() {
        return configured("daily-rewards.rewards", "day");
    }

    public List<Reward> playtimeRewards() {
        ConfigurationSection section = this.plugin.getConfig().getConfigurationSection("playtime-rewards.rewards");
        List<Reward> out = new ArrayList<>();
        if (section == null) return out;
        for (String id : section.getKeys(false)) {
            ConfigurationSection item = section.getConfigurationSection(id);
            if (item == null || !item.getBoolean("enabled", true)) continue;
            long minutes = item.getLong("hours", 0L) * 60L + item.getLong("minutes", 0L);
            if (minutes <= 0L) minutes = 60L;
            List<String> commands = item.getStringList("commands");
            if (!commands.isEmpty()) out.add(new Reward(id, minutes, commands));
        }
        out.sort(Comparator.comparingLong(Reward::threshold));
        return out;
    }

    /** Tracked lifetime playtime in whole minutes. Thresholds are minutes. */
    public long playtimeMinutes(Player player) {
        return playtimeSeconds(player) / 60L;
    }

    /** "30m", "1h", "1h 20m" for GUI progress lore. */
    public static String formatMinutes(long minutes) {
        if (minutes < 60L) return minutes + "m";
        long h = minutes / 60L;
        long m = minutes % 60L;
        return m == 0L ? h + "h" : h + "h " + m + "m";
    }

    private List<Reward> configured(String path, String thresholdKey) {
        ConfigurationSection section = this.plugin.getConfig().getConfigurationSection(path);
        List<Reward> out = new ArrayList<>();
        if (section == null) return out;
        for (String id : section.getKeys(false)) {
            ConfigurationSection item = section.getConfigurationSection(id);
            if (item == null || !item.getBoolean("enabled", true)) continue;
            long threshold = Math.max(1L, item.getLong(thresholdKey, 1L));
            List<String> commands = item.getStringList("commands");
            if (!commands.isEmpty()) out.add(new Reward(id, threshold, commands));
        }
        out.sort(Comparator.comparingLong(Reward::threshold));
        return out;
    }

    private void execute(Player player, Reward reward, int streak, long hours) {
        for (String command : reward.commands) {
            String expanded = CommandTemplate.expand(command, player.getName())
                    .replace("%uuid%", player.getUniqueId().toString())
                    .replace("%reward%", reward.id)
                    .replace("%streak%", String.valueOf(streak))
                    .replace("%hours%", String.valueOf(hours));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), expanded);
        }
    }

    /**
     * Tracked lifetime playtime in hours.
     *
     * <p>Deliberately not {@code Statistic.PLAY_ONE_MINUTE}: that counter is owned
     * by the server and only moves while a player is online, but nothing in this
     * plugin can reset it, so a "playtime wipe" run through PlayerWipe could
     * never clear the very number the rewards read. Accumulating our own total
     * means the wipe, the rewards and the GUI all agree on one store.
     */
    public long playtimeHours(Player player) {
        if (this.data == null) return 0L;
        long seconds = this.data.getLong("players." + player.getUniqueId() + ".playtime.total-seconds", 0L);
        Long started = this.sessionStart.get(player.getUniqueId());
        if (started != null) seconds += (System.currentTimeMillis() - started) / 1000L;
        return seconds / 3600L;
    }

    /** Raw tracked seconds, for display. */
    public long playtimeSeconds(Player player) {
        if (this.data == null) return 0L;
        long seconds = this.data.getLong("players." + player.getUniqueId() + ".playtime.total-seconds", 0L);
        Long started = this.sessionStart.get(player.getUniqueId());
        if (started != null) seconds += (System.currentTimeMillis() - started) / 1000L;
        return seconds;
    }

    /** Zeroes tracked playtime. Called by the wipe path. */
    public void resetPlaytime(UUID id) {
        this.flush(id);
        if (this.data == null) return;
        this.data.set("players." + id + ".playtime.total-seconds", 0L);
        ConfigurationSection claimed = this.data.getConfigurationSection("players." + id + ".playtime.claimed");
        if (claimed != null) {
            for (String key : claimed.getKeys(false)) this.data.set("players." + id + ".playtime.claimed." + key, false);
        }
        save();
    }

    private UUID parseUuid(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
        }
        if (value.matches("(?i)^[0-9a-f]{32}$")) {
            try {
                return UUID.fromString(value.replaceFirst(
                        "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                        "$1-$2-$3-$4-$5"));
            } catch (IllegalArgumentException ignored) {
            }
        }
        org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(value);
        return op != null && op.hasPlayedBefore() ? op.getUniqueId() : null;
    }

    private LocalDate today() {
        String configured = this.plugin.getConfig().getString("daily-rewards.timezone", "UTC");
        try {
            return LocalDate.now(ZoneId.of(configured));
        } catch (Exception ignored) {
            return LocalDate.now(ZoneId.of("UTC"));
        }
    }

    public FileConfiguration getData() { return this.data; }
    public JavaPlugin getPlugin() { return this.plugin; }

    private String base(Player player) {
        return "players." + player.getUniqueId();
    }

    public record Reward(String id, long threshold, List<String> commands) {
    }
}
