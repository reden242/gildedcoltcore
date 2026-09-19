package com.coltcore.core.modules;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

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
public final class RewardsModule {
    public static final String PERM_USE = "coltcore.rewards";
    public static final String PERM_ADMIN = "coltcore.rewards.admin";

    private final JavaPlugin plugin;
    private File dataFile;
    private FileConfiguration data;
    /** Anti-botting gate; null until the plugin wires it. */
    private AntibotGuard antibot;

    public RewardsModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        load();
    }

    public void disable() {
        save();
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
        LocalDate today = today();
        String base = base(player);
        if (today.toString().equals(this.data.getString(base + ".daily.last-date", ""))) {
            if (tell) player.sendMessage(ChatColor.YELLOW + "You already claimed today's daily reward.");
            return 0;
        }
        int oldStreak = this.data.getInt(base + ".daily.streak", 0);
        LocalDate yesterday = today.minusDays(1);
        String last = this.data.getString(base + ".daily.last-date", "");
        int streak = yesterday.toString().equals(last) ? oldStreak + 1 : 1;
        Reward reward = dailyReward(streak);
        if (reward == null) {
            if (tell) player.sendMessage(ChatColor.RED + "No daily reward is configured.");
            return 0;
        }
        execute(player, reward, streak, playtimeHours(player));
        this.data.set(base + ".daily.last-date", today.toString());
        this.data.set(base + ".daily.streak", streak);
        save();
        if (tell) player.sendMessage(ChatColor.GREEN + "Claimed daily reward " + ChatColor.WHITE + reward.id
                + ChatColor.GREEN + " (day " + streak + ").");
        return 1;
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
        boolean dailyReady = !today.toString().equals(this.data.getString(base + ".daily.last-date", ""));
        int timeReady = 0;
        for (Reward reward : playtimeRewards()) {
            if (hours >= reward.threshold && !this.data.getBoolean(base + ".playtime.claimed." + reward.id, false)) timeReady++;
        }
        player.sendMessage(UiKit.colour("&#00ff00Rewards &8| &f" + hours + " hour"
                + (hours == 1 ? "" : "s") + " played"));
        player.sendMessage(ChatColor.GRAY + "Daily: " + (dailyReady ? ChatColor.GREEN + "ready" : ChatColor.YELLOW + "already claimed"));
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

    private long playtimeHours(Player player) {
        return player.getStatistic(Statistic.PLAY_ONE_MINUTE) / (20L * 60L * 60L);
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
