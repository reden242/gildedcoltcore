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

    /** True when the player has enough hours for this tier and it is unpaid. */
    public boolean playtimeTierReady(Player player, Reward reward) {
        return playtimeHours(player) >= reward.threshold
                && !playtimeTierClaimed(player, reward.id);
    }

    private int claimPlaytime(Player player, boolean tell) {
        if (!this.plugin.getConfig().getBoolean("playtime-rewards.enabled", true)) {
            if (tell) player.sendMessage(ChatColor.RED + "Playtime rewards are disabled.");
            return 0;
        }
        long hours = playtimeHours(player);
        String base = base(player) + ".playtime.claimed";
        int claimed = 0;
        for (Reward reward : playtimeRewards()) {
            if (hours < reward.threshold || this.data.getBoolean(base + "." + reward.id, false)) continue;
            execute(player, reward, 0, hours);
            this.data.set(base + "." + reward.id, true);
            claimed++;
            if (tell) player.sendMessage(ChatColor.GREEN + "Claimed playtime reward " + ChatColor.WHITE + reward.id
                    + ChatColor.GREEN + " (" + reward.threshold + "h).");
        }
        if (claimed > 0) save();
        else if (tell) player.sendMessage(ChatColor.YELLOW + "You have no playtime rewards ready to claim.");
        return claimed;
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
        for (Reward reward : playtimeRewards()) {
            if (hours >= reward.threshold && !this.data.getBoolean(base + ".playtime.claimed." + reward.id, false)) timeReady++;
        }
        player.sendMessage(UiKit.colour("&#EEBB01Rewards &8| &f" + hours + " hour"
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
        return configured("playtime-rewards.rewards", "hours");
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
