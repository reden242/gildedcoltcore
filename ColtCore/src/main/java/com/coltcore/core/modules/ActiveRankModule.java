package com.coltcore.core.modules;

import com.coltcore.core.SchedulerCompat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Grants the "active" rank once a player has genuinely played long enough.
 *
 * <h2>Where the hours come from</h2>
 * {@link Statistic#PLAY_ONE_MINUTE} — despite the name, that statistic counts
 * <em>ticks</em> since the account first joined this server, and the server
 * maintains it itself. No dependency on EssentialsX or a playtime plugin, and
 * nothing for a player to reset.
 *
 * <h2>Granted once, recorded forever</h2>
 * Grants are written to {@code active-rank.yml} keyed by UUID. A player who is
 * later demoted is not re-granted on their next login, because that would
 * silently undo a staff decision. Use {@code /activerank regrant &lt;player&gt;}
 * to clear the record deliberately.
 *
 * <h2>When it checks</h2>
 * On join (after a short delay so other plugins finish their own join work) and
 * on a repeating sweep over online players, so someone who crosses the
 * threshold mid-session does not have to relog for it.
 */
public final class ActiveRankModule implements Listener {

    public static final String PERM_ADMIN = "coltcore.activerank.admin";
    /** Holders are skipped entirely — for staff and paid ranks you do not want overwritten. */
    public static final String PERM_EXEMPT = "coltcore.activerank.exempt";

    private static final long TICKS_PER_HOUR = 72_000L;   // 20 tps * 3600s

    private final JavaPlugin plugin;

    private boolean enabled;
    private double requiredHours = 10.0D;
    private String rank = "active";
    private String grantCommand = "lp user %player% parent add %rank%";
    private final List<String> extraCommands = new ArrayList<>();
    private boolean announce = true;
    private String announceMessage =
            "&8[&#00ff00Active&8] &e%player% &7hit &#00ff00%hours%h &7played and earned the &#00ff00ACTIVE &7rank!";
    private String playerMessage =
            "&aYou have played &f%hours%h &aand earned the &#00ff00ACTIVE &arank.";
    private long checkIntervalTicks = 6_000L;             // 5 minutes

    private File file;
    private FileConfiguration data;
    private final Set<String> granted = new LinkedHashSet<>();
    private SchedulerCompat.ManagedTask task;

    public ActiveRankModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /* ------------------------------------------------------------------ */
    /*  Lifecycle                                                         */
    /* ------------------------------------------------------------------ */

    public void enable() {
        readConfig();
        load();
        if (!this.enabled) return;
        startTask();
        this.plugin.getLogger().info("[ActiveRank] enabled - " + this.requiredHours
                + "h grants '" + this.rank + "', " + this.granted.size() + " already granted.");
    }

    public void reload() {
        boolean was = this.enabled;
        readConfig();
        if (this.enabled && !was) { load(); startTask(); }
        if (!this.enabled && was) stopTask();
        if (this.enabled && was) startTask();       // interval may have changed
    }

    public void disable() {
        stopTask();
        save();
    }

    private void startTask() {
        stopTask();
        this.task = SchedulerCompat.timer(this.plugin, this::sweep,
                this.checkIntervalTicks, this.checkIntervalTicks);
    }

    private void stopTask() {
        if (this.task != null) { this.task.cancel(); this.task = null; }
    }

    private void readConfig() {
        ConfigurationSection c = this.plugin.getConfig().getConfigurationSection("active-rank");
        if (c == null) { this.enabled = false; return; }
        this.enabled = c.getBoolean("enabled", false);
        this.requiredHours = Math.max(0.1D, c.getDouble("required-hours", 10.0D));
        this.rank = c.getString("rank", "active").trim();
        this.grantCommand = c.getString("grant-command", this.grantCommand);
        this.extraCommands.clear();
        for (String s : c.getStringList("extra-commands")) {
            if (s != null && !s.isBlank()) this.extraCommands.add(s.trim());
        }
        this.announce = c.getBoolean("announce", true);
        this.announceMessage = c.getString("announce-message", this.announceMessage);
        this.playerMessage = c.getString("player-message", this.playerMessage);
        this.checkIntervalTicks = Math.max(200L, c.getLong("check-interval-seconds", 300L) * 20L);
    }

    /* ------------------------------------------------------------------ */
    /*  Persistence                                                       */
    /* ------------------------------------------------------------------ */

    private void load() {
        this.file = new File(this.plugin.getDataFolder(), "active-rank.yml");
        this.data = YamlConfiguration.loadConfiguration(this.file);
        this.granted.clear();
        this.granted.addAll(this.data.getStringList("granted"));
    }

    private void save() {
        if (this.data == null || this.file == null) return;
        this.data.set("granted", new ArrayList<>(this.granted));
        try {
            this.data.save(this.file);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("[ActiveRank] could not save active-rank.yml: "
                    + ex.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Checking                                                          */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!this.enabled) return;
        SchedulerCompat.runLater(this.plugin, () -> {
            if (event.getPlayer().isOnline()) check(event.getPlayer(), false);
        }, 100L);
    }

    private void sweep() {
        if (!this.enabled) return;
        for (Player p : Bukkit.getOnlinePlayers()) check(p, false);
    }

    /** Hours this account has been on the server, from the server's own counter. */
    public double hours(Player p) {
        try {
            return p.getStatistic(Statistic.PLAY_ONE_MINUTE) / (double) TICKS_PER_HOUR;
        } catch (Exception ex) {
            return 0.0D;
        }
    }

    /**
     * Grants the rank if they are over the line and have not had it before.
     *
     * @param force skip the already-granted record, for the admin command
     * @return true when a grant actually ran
     */
    public boolean check(Player p, boolean force) {
        if (!this.enabled) return false;
        String id = p.getUniqueId().toString();
        if (!force && this.granted.contains(id)) return false;
        if (p.hasPermission(PERM_EXEMPT)) return false;
        double h = hours(p);
        if (h < this.requiredHours) return false;
        grant(p, h);
        return true;
    }

    private void grant(Player p, double hours) {
        String shown = String.format(Locale.ROOT, "%.1f", hours);
        this.granted.add(p.getUniqueId().toString());
        save();

        dispatch(this.grantCommand, p.getName(), shown);
        for (String cmd : this.extraCommands) dispatch(cmd, p.getName(), shown);

        if (!this.playerMessage.isBlank()) {
            p.sendMessage(colour(this.playerMessage
                    .replace("%player%", p.getName())
                    .replace("%hours%", shown)
                    .replace("%rank%", this.rank)));
        }
        if (this.announce && !this.announceMessage.isBlank()) {
            String line = colour(this.announceMessage
                    .replace("%player%", p.getName())
                    .replace("%hours%", shown)
                    .replace("%rank%", this.rank));
            for (Player o : Bukkit.getOnlinePlayers()) o.sendMessage(line);
        }
        this.plugin.getLogger().info("[ActiveRank] granted '" + this.rank + "' to "
                + p.getName() + " (" + shown + "h played).");
    }

    private void dispatch(String template, String player, String hours) {
        String cmd = template.replace("%player%", player)
                             .replace("%rank%", this.rank)
                             .replace("%hours%", hours)
                             .replaceFirst("^/", "");
        if (cmd.isBlank()) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    /* ------------------------------------------------------------------ */
    /*  Command                                                           */
    /* ------------------------------------------------------------------ */

    public boolean command(CommandSender sender, String[] args) {
        if (!this.enabled) {
            sender.sendMessage(colour("&cActive rank is disabled in config."));
            return true;
        }
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(colour("&e/activerank <player|check|grant|regrant|reload>"));
                return true;
            }
            progress(sender, p);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            if (!sender.hasPermission(PERM_ADMIN)) { sender.sendMessage(colour("&cNo permission.")); return true; }
            reload();
            sender.sendMessage(colour("&aActive rank reloaded."));
            return true;
        }
        if (sub.equals("grant") || sub.equals("regrant") || sub.equals("check")) {
            if (!sender.hasPermission(PERM_ADMIN)) { sender.sendMessage(colour("&cNo permission.")); return true; }
            if (args.length < 2) {
                sender.sendMessage(colour("&e/activerank " + sub + " <player>"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(colour("&cThat player is not online. Playtime is read from "
                        + "their live statistics, so they must be on."));
                return true;
            }
            switch (sub) {
                case "check" -> progress(sender, target);
                case "grant" -> {
                    if (check(target, true)) sender.sendMessage(colour("&aGranted."));
                    else sender.sendMessage(colour("&eNot granted - under "
                            + this.requiredHours + "h or exempt."));
                }
                case "regrant" -> {
                    this.granted.remove(target.getUniqueId().toString());
                    save();
                    sender.sendMessage(colour("&aCleared the grant record for &f"
                            + target.getName() + "&a; they can earn it again."));
                }
                default -> { }
            }
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(colour("&cThat player is not online."));
            return true;
        }
        if (!sender.hasPermission(PERM_ADMIN) && !(sender instanceof Player p
                && p.getUniqueId().equals(target.getUniqueId()))) {
            sender.sendMessage(colour("&cNo permission to look at other players."));
            return true;
        }
        progress(sender, target);
        return true;
    }

    private void progress(CommandSender to, Player of) {
        if (to instanceof Player viewer) {
            openGui(viewer, of);
            return;
        }
        double h = hours(of);
        boolean has = this.granted.contains(of.getUniqueId().toString());
        int pct = (int) Math.min(100.0D, (h / this.requiredHours) * 100.0D);
        to.sendMessage(UiKit.colour("&8&l&m----------------------------------------"));
        to.sendMessage(UiKit.colour("&#00ff00&lACTIVE RANK &7- &f" + of.getName()));
        to.sendMessage(UiKit.colour("&7Played: &f" + String.format(Locale.ROOT, "%.1f", h)
                + "h &8/ &f" + this.requiredHours + "h &8(&e" + pct + "%&8)"));
        to.sendMessage(UiKit.colour(has ? "&aRank already granted."
                : "&7Keep playing - the rank is granted automatically."));
        to.sendMessage(UiKit.colour("&8&l&m----------------------------------------"));
    }

    private void openGui(Player viewer, Player of) {
        double h = hours(of);
        boolean has = this.granted.contains(of.getUniqueId().toString());
        int pct = (int) Math.min(100.0D, (h / this.requiredHours) * 100.0D);
        String bar = buildBar(pct);
        Inventory inv = UiKit.chest(3, "&#00ff00&lACTIVE RANK");
        UiKit.fillWithPanes(inv, Material.GRAY_STAINED_GLASS_PANE);
        List<String> lore = new ArrayList<>();
        lore.add("&7Playtime needed: &f" + this.requiredHours + "h");
        lore.add("&7Your playtime: &f" + String.format(Locale.ROOT, "%.1f", h) + "h &8(&e" + pct + "%&8)");
        lore.add("&7Progress: " + bar);
        lore.add(" ");
        lore.add("&7Reward: &a" + this.rank + " &7rank");
        lore.add(has ? "&aAlready granted!" : "&eKeep playing to unlock!");
        ItemStack head = UiKit.head(of, "&#00ff00&lACTIVE RANK", lore);
        inv.setItem(13, head);
        viewer.openInventory(inv);
    }

    private String buildBar(int pct) {
        int filled = Math.max(0, Math.min(20, pct * 20 / 100));
        StringBuilder sb = new StringBuilder("&8[");
        for (int i = 0; i < 20; i++) {
            sb.append(i < filled ? "&a█" : "&8█");
        }
        sb.append("&8]");
        return sb.toString();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        if (title == null) return;
        String stripped = ChatColor.stripColor(UiKit.colour(title));
        if (!stripped.contains("ACTIVE RANK")) return;
        e.setCancelled(true);
        if (e.getCurrentItem() == null) return;
        // Close on any click
        // p.closeInventory();
    }

    private static String colour(String s) {
        return UiKit.colour(s);
    }
}
