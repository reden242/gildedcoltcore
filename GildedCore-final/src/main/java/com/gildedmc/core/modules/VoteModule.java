package com.gildedmc.core.modules;

import java.io.File;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.gildedmc.core.SchedulerCompat;

/**
 * Vote handling: Votifier intake, per-vote console-command rewards, offline
 * queueing, monthly/lifetime tracking, vote links and server-wide vote
 * parties.
 *
 * <p>Reward model follows MythicVote's proven shape: rewards are console
 * commands with placeholders, so money (Essentials eco), shards, items and
 * crate keys are all just commands. No economy, crate or shard API is touched
 * directly, which means no compile or runtime dependency on any of them.
 *
 * <p>Threading: vote events fire async. Everything that touches the counts
 * file, online players or console commands is deferred to the main thread;
 * the async entry point only captures strings and schedules.
 */
public final class VoteModule implements Listener {

    public static final String PERM_USE = "gildedcore.vote";
    public static final String PERM_ADMIN = "gildedcore.vote.admin";

    private final JavaPlugin plugin;
    private File dataFile;
    private FileConfiguration data;

    /** Guard so the monthly reset runs once even under concurrent votes. */
    private final Object monthLock = new Object();

    public VoteModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        this.dataFile = new File(this.plugin.getDataFolder(), "votes.yml");
        this.data = YamlConfiguration.loadConfiguration(this.dataFile);
        checkMonthlyReset();
    }

    public void disable() {
        save();
    }

    public void reload() {
        save();
        this.data = YamlConfiguration.loadConfiguration(this.dataFile);
        checkMonthlyReset();
    }

    private void save() {
        try {
            if (this.data != null && this.dataFile != null) {
                this.data.save(this.dataFile);
            }
        } catch (Exception e) {
            this.plugin.getLogger().warning("[Vote] Could not save votes.yml: " + e.getMessage());
        }
    }

    public boolean isEnabled() {
        return this.plugin.getConfig().getBoolean("vote.enabled", true);
    }

    /* ------------------------------------------------------------------ */
    /*  Intake (async-safe entry)                                           */
    /* ------------------------------------------------------------------ */

    /** Called by the Votifier bridge on whatever thread the vote arrives on. */
    public void handleVote(String username, String service) {
        if (username == null || username.isEmpty()) return;
        final String user = username;
        final String svc = service == null ? "" : service;
        SchedulerCompat.run(this.plugin, () -> handleVoteSync(user, svc));
    }

    private void handleVoteSync(String username, String service) {
        if (!isEnabled()) return;
        checkMonthlyReset();
        OfflinePlayer offline = Bukkit.getOfflinePlayer(username);
        UUID id = offline.getUniqueId();
        String path = "players." + id;

        // Server-wide party counter moves on receipt, so offline votes count.
        int partyTotal = this.data.getInt("party.total", 0) + 1;
        this.data.set("party.total", partyTotal);

        Player online = offline.isOnline() ? offline.getPlayer() : null;
        if (online != null) {
            rewardPlayer(online, service, false);
        } else if (this.plugin.getConfig().getBoolean("vote.offline-rewards", true)) {
            int pending = this.data.getInt(path + ".pending", 0) + 1;
            this.data.set(path + ".pending", pending);
            this.plugin.getLogger().info("[Vote] Queued vote for offline player "
                    + username + " (" + pending + " pending).");
        } else {
            this.plugin.getLogger().info("[Vote] Vote for offline player "
                    + username + " ignored (offline rewards disabled).");
        }

        int goal = Math.max(1, this.plugin.getConfig().getInt("vote.party-goal", 150));
        if (partyTotal >= goal) {
            this.data.set("party.total", partyTotal - goal);
            fireParty(goal);
        }
        save();
    }

    /* ------------------------------------------------------------------ */
    /*  Rewards                                                             */
    /* ------------------------------------------------------------------ */

    private void rewardPlayer(Player player, String service, boolean offlineClaim) {
        UUID id = player.getUniqueId();
        String path = "players." + id;
        int monthly = this.data.getInt(path + ".monthly", 0) + 1;
        int lifetime = this.data.getInt(path + ".lifetime", 0) + 1;
        this.data.set(path + ".monthly", monthly);
        this.data.set(path + ".lifetime", lifetime);
        this.data.set(path + ".name", player.getName());

        List<String> commands = this.plugin.getConfig().getStringList("vote.rewards-per-vote");
        if (commands.isEmpty()) {
            commands = defaultRewards();
        }
        for (String command : commands) {
            dispatch(withPlaceholders(command, player, service, monthly, lifetime));
        }

        if (!offlineClaim) {
            String broadcast = this.plugin.getConfig().getString("vote.broadcast", "");
            if (broadcast != null && !broadcast.isEmpty()) {
                Bukkit.broadcastMessage(color(withPlaceholders(broadcast, player, service, monthly, lifetime)));
            }
            String thanks = this.plugin.getConfig().getString("vote.thanks",
                    "&aThank you for voting! Your rewards have been given.");
            if (thanks != null && !thanks.isEmpty()) {
                player.sendMessage(color(withPlaceholders(thanks, player, service, monthly, lifetime)));
            }
        }
        save();
    }

    private List<String> defaultRewards() {
        List<String> out = new ArrayList<>();
        out.add("eco give %player% 10000");
        out.add("shard give %player% 15");
        return out;
    }

    private void dispatch(String command) {
        if (command == null || command.isEmpty()) return;
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (Exception e) {
            this.plugin.getLogger().warning("[Vote] Reward command failed: " + command + " (" + e.getMessage() + ")");
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Offline queue                                                       */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String path = "players." + player.getUniqueId();
        int pending = this.data.getInt(path + ".pending", 0);
        if (pending <= 0) return;
        this.data.set(path + ".pending", 0);
        String service = "Offline Vote";
        for (int i = 0; i < pending; i++) {
            rewardPlayer(player, service, true);
        }
        String msg = this.plugin.getConfig().getString("vote.offline-claimed",
                "&aYou had &e%amount% &avote reward(s) waiting!");
        if (msg != null && !msg.isEmpty()) {
            player.sendMessage(color(msg.replace("%amount%", String.valueOf(pending))
                    .replace("%player%", player.getName())));
        }
        save();
    }

    /* ------------------------------------------------------------------ */
    /*  Vote party                                                          */
    /* ------------------------------------------------------------------ */

    private void fireParty(int goal) {
        String broadcast = this.plugin.getConfig().getString("vote.party-broadcast",
                "&6&lVOTE PARTY! &7%goal% votes reached - rewards for everyone online!");
        if (broadcast != null && !broadcast.isEmpty()) {
            Bukkit.broadcastMessage(color(broadcast.replace("%goal%", String.valueOf(goal))));
        }
        String crate = this.plugin.getConfig().getString("vote.party-key-crate", "amethyst");
        int amount = Math.max(1, this.plugin.getConfig().getInt("vote.party-key-amount", 1));
        String template = this.plugin.getConfig().getString("rewards.key-command", "crate give %player% %crate% %amount%");
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            String command = template.replace("%player%", player.getName())
                    .replace("%crate%", crate)
                    .replace("%amount%", String.valueOf(amount));
            dispatch(command);
            count++;
        }
        this.plugin.getLogger().info("[Vote] Party fired for " + count + " online players (KeyAll " + crate + " x" + amount + ").");
    }

    /* ------------------------------------------------------------------ */
    /*  /vote links + stats                                                 */
    /* ------------------------------------------------------------------ */

    public void sendVoteLinks(Player player) {
        String header = this.plugin.getConfig().getString("vote.header", "&6&lVote &8» &7Vote daily on every site for rewards!");
        if (header != null && !header.isEmpty()) {
            player.sendMessage(color(header));
        }
        for (String link : voteLinks()) {
            player.sendMessage(color(link));
        }
        UUID id = player.getUniqueId();
        String path = "players." + id;
        int monthly = this.data.getInt(path + ".monthly", 0);
        int lifetime = this.data.getInt(path + ".lifetime", 0);
        int partyTotal = this.data.getInt("party.total", 0);
        int goal = Math.max(1, this.plugin.getConfig().getInt("vote.party-goal", 150));
        String footer = this.plugin.getConfig().getString("vote.footer",
                "&7Your votes: &e%monthly% monthly &8/ &6%lifetime% lifetime &8| &dParty: &f%party%/%goal%");
        if (footer != null && !footer.isEmpty()) {
            player.sendMessage(color(footer.replace("%monthly%", String.valueOf(monthly))
                    .replace("%lifetime%", String.valueOf(lifetime))
                    .replace("%party%", String.valueOf(partyTotal))
                    .replace("%goal%", String.valueOf(goal))
                    .replace("%player%", player.getName())));
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Shared accessors for the vote GUI                                 */
    /* ------------------------------------------------------------------ */

    /** Configured vote sites in display order, falling back to the five built-ins. */
    public List<String> voteLinks() {
        List<String> links = this.plugin.getConfig().getStringList("vote.links");
        return links.isEmpty() ? defaultLinks() : links;
    }

    /** Votes needed for the server-wide party reward. */
    public int partyGoal() {
        return Math.max(1, this.plugin.getConfig().getInt("vote.party-goal", 150));
    }

    /** Console commands paid once per vote. */
    public List<String> rewardCommands() {
        List<String> out = this.plugin.getConfig().getStringList("vote.rewards-per-vote");
        if (out.isEmpty()) {
            out = List.of("eco give %player% 10000", "shard give %player% 15");
        }
        return out;
    }

    private List<String> defaultLinks() {
        List<String> out = new ArrayList<>();
        out.add("&bMinecraft-MP &7- &fhttps://minecraft-mp.com/server/364740/vote/");
        out.add("&bMinecraft-Server-List &7- &fhttps://minecraft-server-list.com/server/523774/");
        out.add("&bTopG &7- &fhttps://topg.org/minecraft-servers/server-686615");
        out.add("&bMinecraftServers.org &7- &fhttps://minecraftservers.org/server/693977");
        out.add("&bMinecraft-ServerList &7- &fhttps://minecraft-serverlist.com/server/6666/vote");
        return out;
    }

    /* ------------------------------------------------------------------ */
    /*  Monthly reset + placeholders                                        */
    /* ------------------------------------------------------------------ */

    private void checkMonthlyReset() {
        synchronized (this.monthLock) {
            String now = YearMonth.now().toString();
            String stored = this.data.getString("last-month", "");
            if (now.equals(stored)) return;
            this.data.set("last-month", now);
            if (this.data.isConfigurationSection("players")) {
                for (String key : this.data.getConfigurationSection("players").getKeys(false)) {
                    this.data.set("players." + key + ".monthly", 0);
                }
            }
            this.plugin.getLogger().info("[Vote] Monthly votes reset for " + now + ".");
            save();
        }
    }

    private String withPlaceholders(String text, Player player, String service, int monthly, int lifetime) {
        if (text == null) return "";
        int partyTotal = this.data.getInt("party.total", 0);
        int goal = Math.max(1, this.plugin.getConfig().getInt("vote.party-goal", 150));
        return text.replace("%player%", player.getName())
                .replace("%service%", service == null ? "" : service)
                .replace("%monthly_votes%", String.valueOf(monthly))
                .replace("%monthly%", String.valueOf(monthly))
                .replace("%lifetime_votes%", String.valueOf(lifetime))
                .replace("%lifetime%", String.valueOf(lifetime))
                .replace("%party%", String.valueOf(partyTotal))
                .replace("%goal%", String.valueOf(goal));
    }

    private String color(String text) {
        if (text == null) return "";
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', text);
    }

    public int getMonthly(UUID id) {
        return this.data.getInt("players." + id + ".monthly", 0);
    }

    public int getLifetime(UUID id) {
        return this.data.getInt("players." + id + ".lifetime", 0);
    }

    public int getPending(UUID id) {
        return this.data.getInt("players." + id + ".pending", 0);
    }

    public int getPartyTotal() {
        return this.data.getInt("party.total", 0);
    }
}