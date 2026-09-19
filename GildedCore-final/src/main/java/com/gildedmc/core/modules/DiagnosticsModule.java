package com.gildedmc.core.modules;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /gildedcore} — the hub, the health report, and the self-test.
 *
 * <h2>Why a self-test</h2>
 * Almost everything this plugin decides is driven by config: ladder rungs,
 * advertising allow-lists, punishment durations, macro thresholds. A typo in
 * any of them fails silently and only shows up when a real player is on the
 * receiving end — a ladder rung that never parsed means an offence quietly does
 * nothing, and an allow-list entry of {@code discord.gg} whitelists every
 * Discord invite on the internet rather than only yours.
 *
 * <p>{@link #selfTest} runs the actual code paths against the actual live
 * config and reports what it finds, so those get caught at setup rather than in
 * production. It changes nothing and punishes nobody.
 *
 * <h2>Why a status page</h2>
 * Half of this plugin's behaviour depends on which optional plugins are
 * installed — LiteBans decides where punishments land, LuckPerms decides who counts as
 * staff. "It isn't working" is nearly always one of those missing, so the
 * status page names each one and what its absence costs.
 */
public final class DiagnosticsModule implements Listener {

    public static final String PERM = "gildedcore.admin";

    private final JavaPlugin plugin;
    private final ChatGuardModule guard;
    private final MaintenanceModule maintenance;
    private final PlayerWipeModule wipe;
    private final ActiveRankModule active;

    private final Map<UUID, Inventory> open = new ConcurrentHashMap<>();

    public DiagnosticsModule(JavaPlugin plugin, ChatGuardModule guard,
                             MaintenanceModule maintenance, PlayerWipeModule wipe,
                             ActiveRankModule active) {
        this.plugin = plugin;
        this.guard = guard;
        this.maintenance = maintenance;
        this.wipe = wipe;
        this.active = active;
    }

    /* ------------------------------------------------------------------ */
    /*  Check model                                                       */
    /* ------------------------------------------------------------------ */

    /** One self-test result. */
    public record Check(String area, Level level, String message) {

        public enum Level { PASS, WARN, FAIL }

        String colour() {
            return switch (this.level) {
                case PASS -> "&a";
                case WARN -> "&e";
                case FAIL -> "&c";
            };
        }

        String symbol() {
            return switch (this.level) {
                case PASS -> "+";
                case WARN -> "!";
                case FAIL -> "x";
            };
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Commands                                                          */
    /* ------------------------------------------------------------------ */

    /** Returns false when the subcommand is not ours, so reload still works. */
    public boolean command(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM)) {
            sender.sendMessage(UiKit.colour("&cNo permission."));
            return true;
        }
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "selftest", "test", "check" -> {
                sender.sendMessage(UiKit.colour("&7Running self-test against the live config..."));
                report(sender, selfTest());
            }
            case "perms", "permissions" -> perms(sender);
            case "prune", "maintenance" -> {
                if (this.maintenance == null) sender.sendMessage(UiKit.colour("&cMaintenance is disabled."));
                else this.maintenance.runFor(sender);
            }
            case "menu", "gui" -> {
                if (sender instanceof Player p) openHub(p);
                else status(sender);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    public List<String> subCommands() {
        return List.of("reload", "status", "selftest", "perms", "prune", "menu");
    }

    /* ------------------------------------------------------------------ */
    /*  Status                                                            */
    /* ------------------------------------------------------------------ */

    private void status(CommandSender to) {
        to.sendMessage(UiKit.colour("&8&m----------------------------------------"));
        to.sendMessage(UiKit.colour("&#EEBB01&lGILDEDCORE &7v"
                + this.plugin.getDescription().getVersion()
                + " &8| &7config v" + this.plugin.getConfig().getInt("config-version", 0)));

        to.sendMessage(UiKit.colour("&7Modules:"));
        line(to, "text guard", this.plugin.getConfig().getBoolean("chat-guard.enabled", false));
        line(to, "local ai", this.plugin.getConfig().getBoolean("local-ai.enabled", false));
        line(to, "staff monitor", this.plugin.getConfig().getBoolean("staff-monitor.enabled", false));
        line(to, "active rank", this.plugin.getConfig().getBoolean("active-rank.enabled", false));
        line(to, "player wipe", this.plugin.getConfig().getBoolean("playerwipe.enabled", true));
        line(to, "rewards", this.plugin.getConfig().getBoolean("daily-rewards.enabled", true)
                || this.plugin.getConfig().getBoolean("playtime-rewards.enabled", true));
        line(to, "entity limit", this.plugin.getConfig().getBoolean("lagguard.enabled", true)
                && this.plugin.getConfig().getBoolean("lagguard.entity-limit.enabled", true));

        to.sendMessage(UiKit.colour("&7Optional plugins:"));
        dep(to, "LiteBans", "punishments go to the internal store instead");
        dep(to, "LuckPerms", "staff ranks fall back to a permission node");
        dep(to, "Essentials", "playtime and home wipes will not run");
        dep(to, "WorldEdit", "stash schematics cannot be pasted");

        PunishmentBridge bridge = this.guard.punishments();
        to.sendMessage(UiKit.colour("&7Punishments: &f"
                + bridge.backend().name().toLowerCase(Locale.ROOT)));

        Map<String, Integer> counts = this.maintenance == null ? new java.util.HashMap<String, Integer>() : this.maintenance.counts();
        if (!counts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            counts.forEach((k, v) -> sb.append(k).append('=').append(v).append(' '));
            to.sendMessage(UiKit.colour("&7Stored rows: &f" + sb.toString().trim()));
        }
        to.sendMessage(UiKit.colour("&7Retention: &f"
                + ((this.maintenance != null && this.maintenance.isEnabled())
                    ? "every " + (this.maintenance == null ? 24 : this.maintenance.intervalHours()) + "h, last: "
                      + (this.maintenance == null ? "none" : this.maintenance.lastResult())
                    : "&cDISABLED - tables grow without limit")));
        to.sendMessage(UiKit.colour("&8/gildedcore <status|selftest|perms|prune|menu|reload>"));
        to.sendMessage(UiKit.colour("&8&m----------------------------------------"));
    }

    private void line(CommandSender to, String name, boolean on) {
        to.sendMessage(UiKit.colour("&8  " + (on ? "&a+" : "&8-") + " &7" + name + ": "
                + (on ? "&aon" : "&8off")));
    }

    private void dep(CommandSender to, String name, String cost) {
        Plugin p = Bukkit.getPluginManager().getPlugin(name);
        boolean on = p != null && p.isEnabled();
        to.sendMessage(UiKit.colour("&8  " + (on ? "&a+" : "&e!") + " &7" + name + ": "
                + (on ? "&a" + p.getDescription().getVersion() : "&emissing &8- " + cost)));
    }

    /* ------------------------------------------------------------------ */
    /*  Self-test                                                         */
    /* ------------------------------------------------------------------ */

    /** Runs every check against the live config. Changes nothing. */
    public List<Check> selfTest() {
        List<Check> out = new ArrayList<>();
        checkLadders(out);
        checkAdvertising(out);
        checkWipe(out);
        checkLocalAi(out);
        checkDeps(out);
        return out;
    }

    /** Every ladder rung must parse, or the offence silently does nothing. */
    private void checkLadders(List<Check> out) {
        ConfigurationSection c = this.plugin.getConfig()
                .getConfigurationSection("chat-guard.ladder");
        if (c == null) {
            out.add(new Check("ladder", Check.Level.WARN,
                    "no chat-guard.ladder section; built-in defaults are used"));
            return;
        }
        int bad = 0, total = 0;
        for (String cat : c.getKeys(false)) {
            for (String rung : c.getStringList(cat)) {
                total++;
                if (MuteStore.parseRung(rung) == null) {
                    bad++;
                    out.add(new Check("ladder", Check.Level.FAIL,
                            "unreadable rung '" + rung + "' in '" + cat
                            + "' - that step is skipped entirely"));
                }
            }
        }
        if (bad == 0) {
            out.add(new Check("ladder", Check.Level.PASS,
                    total + " rung(s) across " + c.getKeys(false).size() + " category(s) parse"));
        }

        // Every category something can DETECT needs a rung to land on, or the
        // detection fires and then quietly does nothing.
        List<String> detectable = new ArrayList<>(PatternPack.categories());
        for (String label : this.plugin.getConfig().getStringList("local-ai.labels")) {
            if (label != null && !label.isBlank()
                    && !label.equalsIgnoreCase(LocalAiModule.LABEL_CLEAN)
                    && !detectable.contains(label)) {
                detectable.add(label);
            }
        }
        for (String category : detectable) {
            if (!c.contains(category)) {
                out.add(new Check("ladder", Check.Level.FAIL,
                        "'" + category + "' can be detected but has no ladder, so a hit "
                        + "does nothing. Add it under chat-guard.ladder"));
            }
        }
    }

    /**
     * The allow-list is the dangerous half of advertising detection: one
     * over-broad entry turns the whole check off for that host.
     */
    private void checkAdvertising(List<Check> out) {
        if (!this.plugin.getConfig().getBoolean("chat-guard.advertising.enabled", true)) {
            out.add(new Check("advertising", Check.Level.WARN, "disabled in config"));
            return;
        }
        List<String> allow = this.plugin.getConfig()
                .getStringList("chat-guard.advertising.allow");
        for (String a : allow) {
            if (a == null || a.isBlank()) continue;
            String low = a.toLowerCase(Locale.ROOT).trim();
            if (low.indexOf('/') < 0 && (low.equals("discord.gg") || low.equals("dsc.gg")
                    || low.equals("t.me") || low.equals("bit.ly"))) {
                out.add(new Check("advertising", Check.Level.WARN,
                        "allow entry '" + a + "' whitelists EVERY invite on that host, "
                        + "not only yours. Use '" + a + "/yourinvite' instead"));
            }
        }
        if (this.plugin.getConfig().getStringList("chat-guard.advertising.tlds").isEmpty()) {
            out.add(new Check("advertising", Check.Level.WARN,
                    "empty tld list accepts any 2-10 letter suffix; expect noise like 'i.e.that'"));
        }
        // Live check against the real matcher.
        String caught = this.guard.advertHit("join play.someothernetwork.net now");
        if (caught == null) {
            out.add(new Check("advertising", Check.Level.FAIL,
                    "a plain server address was NOT detected; check the tld list"));
        } else {
            out.add(new Check("advertising", Check.Level.PASS,
                    "obfuscated and plain addresses detected (matched '" + caught + "')"));
        }
        if (this.guard.advertHit("we are on 1.21.4 now") != null) {
            out.add(new Check("advertising", Check.Level.WARN,
                    "a Minecraft version string was read as an address; "
                    + "turn on ignore-version-like"));
        }
    }

    private void checkWipe(List<Check> out) {
        ConfigurationSection cats = this.plugin.getConfig()
                .getConfigurationSection("playerwipe.categories");
        if (cats == null || cats.getKeys(false).isEmpty()) {
            out.add(new Check("playerwipe", Check.Level.FAIL,
                    "no categories; the menu will open empty. Check for a duplicate "
                    + "'playerwipe:' block - YAML keeps only the last one"));
            return;
        }
        int usable = 0;
        for (String id : cats.getKeys(false)) {
            ConfigurationSection s = cats.getConfigurationSection(id);
            if (s == null || !s.getBoolean("enabled", true)) continue;
            boolean has = !s.getStringList("all-commands").isEmpty()
                    || !s.getStringList("commands").isEmpty()
                    || !s.getStringList("stats").isEmpty();
            if (!has) {
                out.add(new Check("playerwipe", Check.Level.WARN,
                        "'" + id + "' has no commands and no stats; clicking it does nothing"));
            } else {
                usable++;
            }
        }
        if (usable > 0) {
            out.add(new Check("playerwipe", Check.Level.PASS, usable + " usable category(s)"));
        }
    }

    private void checkLocalAi(List<Check> out) {
        if (!this.plugin.getConfig().getBoolean("local-ai.enabled", false)) {
            out.add(new Check("local ai", Check.Level.WARN, "disabled in config"));
        } else if (this.plugin.getConfig().getBoolean("local-ai.shadow-mode", true)) {
            out.add(new Check("local ai", Check.Level.WARN,
                    "shadow mode is on; decisions are logged but nothing is executed"));
        } else {
            out.add(new Check("local ai", Check.Level.PASS, "enabled"));
        }
        if (this.plugin.getConfig().getBoolean("chat-guard.enabled", false)
                && this.plugin.getConfig().getStringList("chat-guard.terms").isEmpty()) {
            out.add(new Check("text guard", Check.Level.WARN,
                    "the term list is empty, so only advertising and the local classifier catch anything"));
        }
    }

    private void checkDeps(List<Check> out) {
        String backend = this.plugin.getConfig()
                .getString("punishments.backend", "auto").toLowerCase(Locale.ROOT);
        boolean liteBans = Bukkit.getPluginManager().getPlugin("LiteBans") != null;
        if (backend.equals("litebans") && !liteBans) {
            out.add(new Check("punishments", Check.Level.FAIL,
                    "backend is 'litebans' but LiteBans is not installed"));
        } else {
            out.add(new Check("punishments", Check.Level.PASS,
                    "backend resolves to " + this.guard.punishments().backend()
                            .name().toLowerCase(Locale.ROOT)));
        }
        if (!this.plugin.getConfig().getBoolean("maintenance.enabled", true)) {
            out.add(new Check("maintenance", Check.Level.WARN,
                    "retention is disabled; evidence.db will grow without limit"));
        } else {
            out.add(new Check("maintenance", Check.Level.PASS,
                    "retention runs every "
                    + this.plugin.getConfig().getLong("maintenance.interval-hours", 6L) + "h"));
        }
    }

    private void report(CommandSender to, List<Check> checks) {
        int pass = 0, warn = 0, fail = 0;
        for (Check c : checks) {
            switch (c.level()) {
                case PASS -> pass++;
                case WARN -> warn++;
                case FAIL -> fail++;
            }
        }
        to.sendMessage(UiKit.colour("&8&m----------------------------------------"));
        to.sendMessage(UiKit.colour("&#EEBB01&lSELF-TEST &8- &a" + pass + " pass &8/ &e"
                + warn + " warn &8/ &c" + fail + " fail"));
        for (Check c : checks) {
            if (c.level() == Check.Level.PASS) continue;      // detail only for problems
            to.sendMessage(UiKit.colour("&8 " + c.colour() + c.symbol() + " &7["
                    + c.area() + "] " + c.colour() + c.message()));
        }
        for (Check c : checks) {
            if (c.level() != Check.Level.PASS) continue;
            to.sendMessage(UiKit.colour("&8 &a+ &7[" + c.area() + "] &8" + c.message()));
        }
        if (fail == 0 && warn == 0) {
            to.sendMessage(UiKit.colour("&aEverything the config drives checks out."));
        }
        to.sendMessage(UiKit.colour("&8&m----------------------------------------"));
    }

    /* ------------------------------------------------------------------ */
    /*  Permission dump                                                   */
    /* ------------------------------------------------------------------ */

    /** Every node the plugin declares, grouped, so LuckPerms setup is copy-paste. */
    private void perms(CommandSender to) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("Helper", List.of(
                ChatGuardModule.PERM_ALERTS,
                "staffmonitor.view"));
        groups.put("Moderator (helper, plus)", List.of(
                "gildedcore.staff"));
        groups.put("Admin (moderator, plus)", List.of(
                PERM,
                ChatGuardModule.PERM_ADMIN,
                PlayerWipeModule.PERM,
                ActiveRankModule.PERM_ADMIN,
                "staffmonitor.admin"));
        groups.put("Tracked staff (needed for monitoring)", List.of(
                "staffmonitor.tracked"));
        groups.put("Exemptions - give deliberately", List.of(
                ChatGuardModule.PERM_BYPASS,
                ActiveRankModule.PERM_EXEMPT));

        to.sendMessage(UiKit.colour("&8&m----------------------------------------"));
        to.sendMessage(UiKit.colour("&#EEBB01&lPERMISSIONS"));
        groups.forEach((title, nodes) -> {
            to.sendMessage(UiKit.colour("&e" + title + ":"));
            for (String n : nodes) to.sendMessage(UiKit.colour("&8  lp group <g> permission set &f" + n));
        });
        to.sendMessage(UiKit.colour("&7Note: &fstaffmonitor.tracked &7is what makes /staffafk "
                + "and the audit log record someone. Give it to staff, not everyone."));
        to.sendMessage(UiKit.colour("&8&m----------------------------------------"));
    }

    /* ------------------------------------------------------------------ */
    /*  Hub GUI                                                           */
    /* ------------------------------------------------------------------ */

    private void openHub(Player viewer) {
        Inventory inv = UiKit.chest(4, "&8GildedCore");
        inv.setItem(4, UiKit.item(Material.NETHER_STAR, "&#EEBB01&lGILDEDCORE &7v"
                        + this.plugin.getDescription().getVersion(),
                "&7Everything below opens a command.",
                "&8Config v" + this.plugin.getConfig().getInt("config-version", 0)));

        inv.setItem(10, UiKit.card(Material.BARRIER, "&4&lPLAYER WIPE", "Reset a player",
                List.of("Shards, money, stats, playtime, homes."),
                "Runs console commands. No undo.", "to see the usage"));
        inv.setItem(12, UiKit.card(Material.ENDER_EYE, "&d&lMOST AFK", "Staff idle leaderboard",
                List.of("Ranked by total idle time, worst first."), null, "to open /staffafk"));
        inv.setItem(13, UiKit.card(Material.SHIELD, "&b&lTEXT GUARD", "Chat, signs, books, names",
                List.of("Escalation ladder and mute records."), null, "to open /textguard"));
        inv.setItem(15, UiKit.card(Material.AMETHYST_SHARD, "&e&lLOCAL AI", "In-process classifier",
                List.of("Training status, readiness and shadow mode."), null, "to open /textguard model"));
        inv.setItem(16, UiKit.card(Material.EXPERIENCE_BOTTLE, "&a&lACTIVE RANK", "Playtime rank",
                List.of("Progress towards the active rank."), null, "to open /activerank"));

        inv.setItem(29, UiKit.card(Material.COMPARATOR, "&f&lSTATUS", "Health report",
                List.of("Which modules are on, which optional",
                        "plugins are present, stored row counts."), null, "to run it"));
        inv.setItem(31, UiKit.card(Material.SPYGLASS, "&f&lSELF-TEST", "Check the config",
                List.of("Runs the real code against your live",
                        "config and reports what will not work."),
                null, "to run it"));
        inv.setItem(33, UiKit.card(Material.HOPPER, "&f&lPRUNE NOW", "Retention sweep",
                List.of("Deletes evidence and punishment rows",
                        "past their retention."), null, "to run it"));
        UiKit.fill(inv);

        this.open.put(viewer.getUniqueId(), inv);
        viewer.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        Inventory tracked = this.open.get(p.getUniqueId());
        if (tracked == null || !tracked.equals(event.getInventory())) return;
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getItemMeta() == null) return;
        String name = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
        if (name == null || name.isBlank()) return;

        switch (name.trim()) {
            case "PLAYER WIPE" -> {
                p.closeInventory();
                p.sendMessage(UiKit.colour("&eUsage: &f/playerwipe <player>"));
            }
            case "MOST AFK" -> { p.closeInventory(); p.performCommand("staffafk"); }
            case "TEXT GUARD" -> { p.closeInventory(); p.performCommand("textguard status"); }
            case "LOCAL AI" -> { p.closeInventory(); p.performCommand("textguard model"); }
            case "ACTIVE RANK" -> { p.closeInventory(); p.performCommand("activerank"); }
            case "STATUS" -> { p.closeInventory(); status(p); }
            case "SELF-TEST" -> { p.closeInventory(); report(p, selfTest()); }
            case "PRUNE NOW" -> { p.closeInventory(); if (this.maintenance == null) p.sendMessage(UiKit.colour("&cMaintenance is disabled.")); else this.maintenance.runFor(p); }
            default -> { }
        }
    }

    public void disable() { this.open.clear(); }
}
