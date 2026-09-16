package com.gildedmc.core.modules;

import com.gildedmc.core.SchedulerCompat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /playerwipe} — reset a player's shards, money, stats, playtime or homes.
 *
 * <h2>Why this replaces the old one</h2>
 * The previous menu rendered nothing but filler panes. The cause was in
 * config.yml, not in code: {@code playerwipe:} appeared twice, and YAML keeps
 * the last occurrence, which had no {@code categories} block. The GUI dutifully
 * drew zero categories. That is fixed, and the menu is rebuilt here so that it
 * is a real thing rather than an inner class hidden inside a decompiled bundle.
 *
 * <h2>Partial wipes</h2>
 * Shards and money are rarely all-or-nothing — you usually want to claw back a
 * duped amount, not zero someone. Those categories offer preset amounts and a
 * custom amount typed in chat, alongside the full reset. Stats are listed one
 * by one so a single counter can be corrected without wiping the rest.
 *
 * <h2>Every destructive click is confirmed</h2>
 * There is no path from opening the menu to running a command without passing
 * a confirm screen that names the player, the category and the amount.
 */
public final class PlayerWipeModule implements Listener {

    public static final String PERM = "gildedcore.playerwipe";

    private final JavaPlugin plugin;

    private boolean enabled = true;
    private final Set<String> allowedUsernames = ConcurrentHashMap.newKeySet();
    private String guiTitle = "&8Wipe &8| &f%player%";
    private String statCommand = "statsmgr set %player% %stat% %value%";
    /** Mirror every wipe to Discord. Optional; needs DiscordSRV installed. */
    private boolean discordEnabled = true;
    private String discordChannel = "staff";
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final List<String> fullOrder = new ArrayList<>();
    private Category fullWipe;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, String> amountPrompts = new ConcurrentHashMap<>();

    public PlayerWipeModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /* ------------------------------------------------------------------ */
    /*  Config model                                                      */
    /* ------------------------------------------------------------------ */

    private static final class Category {
        String id;
        int slot = -1;
        Material icon = Material.PAPER;
        String display = "&fCategory";
        String description = "";
        final List<String> information = new ArrayList<>();
        String warning = "";
        boolean amounts;
        final List<Integer> presets = new ArrayList<>();
        final List<String> allCommands = new ArrayList<>();
        final List<String> takeCommands = new ArrayList<>();
        final List<String> stats = new ArrayList<>();
    }

    private static final class Session {
        Inventory inventory;
        String stage;      // main | amount | stats | confirm
        String target;
        String category;
        String amount;     // "all", a number, or a stat name
        String label;
    }

    /* ------------------------------------------------------------------ */
    /*  Lifecycle                                                         */
    /* ------------------------------------------------------------------ */

    public void enable() { readConfig(); }

    public void reload() { readConfig(); }

    public void disable() {
        this.sessions.clear();
        this.amountPrompts.clear();
    }

    private void readConfig() {
        ConfigurationSection c = this.plugin.getConfig().getConfigurationSection("playerwipe");
        this.categories.clear();
        this.fullOrder.clear();
        this.fullWipe = null;
        if (c == null) { this.enabled = false; return; }
        this.enabled = c.getBoolean("enabled", true);
        this.allowedUsernames.clear();
        for (String name : c.getStringList("allowed-usernames")) {
            String normalized = normalizeName(name);
            if (!normalized.isBlank()) this.allowedUsernames.add(normalized);
        }
        String legacyOwner = normalizeName(c.getString("owner-name", ""));
        if (!legacyOwner.isBlank()) this.allowedUsernames.add(legacyOwner);
        this.guiTitle = c.getString("gui-title", this.guiTitle);
        this.statCommand = c.getString("stat-command", this.statCommand);
        ConfigurationSection dl = c.getConfigurationSection("discord-log");
        if (dl != null) {
            this.discordEnabled = dl.getBoolean("enabled", true);
            this.discordChannel = dl.getString("channel", "staff");
        }

        ConfigurationSection cats = c.getConfigurationSection("categories");
        if (cats != null) for (String id : cats.getKeys(false)) {
            ConfigurationSection s = cats.getConfigurationSection(id);
            if (s == null || !s.getBoolean("enabled", true)) continue;
            this.categories.put(id.toLowerCase(Locale.ROOT), category(id, s));
        }
        ConfigurationSection full = c.getConfigurationSection("full-wipe");
        if (full != null && full.getBoolean("enabled", true)) {
            this.fullWipe = category("full", full);
            this.fullOrder.addAll(full.getStringList("categories"));
            if (this.fullOrder.isEmpty()) this.fullOrder.addAll(this.categories.keySet());
        }
        if (this.enabled && this.categories.isEmpty()) {
            this.plugin.getLogger().warning("[PlayerWipe] no categories configured - the menu "
                    + "would open empty. Check that config.yml has exactly one 'playerwipe:' block.");
        }
    }

    private Category category(String id, ConfigurationSection s) {
        Category cat = new Category();
        cat.id = id.toLowerCase(Locale.ROOT);
        cat.slot = s.getInt("slot", -1);
        cat.icon = UiKit.material(s.getString("icon"), Material.PAPER);
        cat.display = s.getString("display-name", "&f" + id);
        cat.description = s.getString("description", "");
        cat.information.addAll(s.getStringList("information"));
        if (cat.information.isEmpty()) {
            String lore = s.getString("lore", "");
            if (!lore.isBlank()) cat.information.add(ChatColor.stripColor(UiKit.colour(lore)));
            for (String l : s.getStringList("lore")) {
                cat.information.add(ChatColor.stripColor(UiKit.colour(l)));
            }
        }
        cat.warning = s.getString("warning", "This cannot be undone without a backup.");
        cat.amounts = s.getBoolean("amounts", false);
        for (int n : s.getIntegerList("amount-presets")) cat.presets.add(n);
        cat.allCommands.addAll(s.getStringList("all-commands"));
        // "commands" is the old key. Read it so an existing config keeps working.
        cat.allCommands.addAll(s.getStringList("commands"));
        cat.takeCommands.addAll(s.getStringList("take-commands"));
        cat.stats.addAll(s.getStringList("stats"));
        return cat;
    }

    /* ------------------------------------------------------------------ */
    /*  Command                                                           */
    /* ------------------------------------------------------------------ */

    public boolean command(CommandSender sender, String[] args) {
        if (!this.enabled) {
            sender.sendMessage(UiKit.colour("&cPlayer wipe is disabled in config."));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(UiKit.colour("&cThis menu is for players."));
            return true;
        }
        if (!allowed(p)) {
            p.sendMessage(UiKit.colour("&cNo permission."));
            return true;
        }
        if (args.length == 0) {
            p.sendMessage(UiKit.colour("&eUsage: &f/playerwipe <player>"));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            reload();
            p.sendMessage(UiKit.colour("&aPlayer wipe reloaded, &f"
                    + this.categories.size() + " &acategory(s)."));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            p.sendMessage(UiKit.colour("&c" + args[0] + " has never joined this server."));
            return true;
        }
        openMain(p, target.getName() == null ? args[0] : target.getName());
        return true;
    }

    /** Permission node or a configured username. Legacy owner-name stays additive. */
    private boolean allowed(Player p) {
        return p.hasPermission(PERM) || this.allowedUsernames.contains(normalizeName(p.getName()));
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /* ------------------------------------------------------------------ */
    /*  Menus                                                             */
    /* ------------------------------------------------------------------ */

    private void openMain(Player viewer, String target) {
        Inventory inv = UiKit.chest(5, this.guiTitle.replace("%player%", target));
        int auto = 10;
        for (Category cat : this.categories.values()) {
            int slot = cat.slot >= 0 && cat.slot < inv.getSize() ? cat.slot : auto++;
            if (slot >= inv.getSize()) break;
            List<String> info = new ArrayList<>(cat.information);
            if (cat.amounts) info.add("Choose a full reset or an exact amount.");
            if (!cat.stats.isEmpty()) info.add(cat.stats.size() + " individual counters.");
            inv.setItem(slot, UiKit.card(cat.icon, cat.display, cat.description, info,
                    cat.warning, "to choose"));
        }
        if (this.fullWipe != null) {
            int slot = this.fullWipe.slot >= 0 && this.fullWipe.slot < inv.getSize()
                    ? this.fullWipe.slot : inv.getSize() - 5;
            List<String> info = new ArrayList<>(this.fullWipe.information);
            info.add("Runs: " + String.join(", ", this.fullOrder));
            inv.setItem(slot, UiKit.card(this.fullWipe.icon, this.fullWipe.display,
                    this.fullWipe.description, info,
                    "Everything above, in one click. No undo.", "to choose"));
        }
        inv.setItem(inv.getSize() - 1, UiKit.close());
        inv.setItem(4, UiKit.head(Bukkit.getOfflinePlayer(target), "&f" + target,
                List.of("&7The account these wipes apply to.")));
        UiKit.fill(inv);

        Session s = new Session();
        s.inventory = inv;
        s.stage = "main";
        s.target = target;
        this.sessions.put(viewer.getUniqueId(), s);
        viewer.openInventory(inv);
    }

    private void openAmounts(Player viewer, Session s, Category cat) {
        Inventory inv = UiKit.chest(3, "&8" + UiKit.strip(cat.display) + " &8| &f" + s.target);
        inv.setItem(10, UiKit.card(Material.TNT, "&c&lRESET ALL", "Set to zero",
                List.of("Wipes the whole balance."), "No undo.", "to select"));
        int slot = 12;
        for (int preset : cat.presets) {
            if (slot > 15) break;
            inv.setItem(slot++, UiKit.card(Material.GOLD_NUGGET, "&e&lTAKE " + preset,
                    "Remove exactly " + preset,
                    List.of("Leaves the rest of the balance alone."), null, "to select"));
        }
        if (!cat.takeCommands.isEmpty()) {
            inv.setItem(16, UiKit.card(Material.WRITABLE_BOOK, "&d&lCUSTOM AMOUNT",
                    "Type a number in chat",
                    List.of("Whole numbers only."), null, "to type an amount"));
        }
        inv.setItem(inv.getSize() - 1, UiKit.back("the wipe menu"));
        UiKit.fill(inv);
        s.inventory = inv;
        s.stage = "amount";
        s.category = cat.id;
        viewer.openInventory(inv);
    }

    private void openStats(Player viewer, Session s, Category cat) {
        int rows = Math.min(6, Math.max(3, 2 + (cat.stats.size() + 8) / 9));
        Inventory inv = UiKit.chest(rows, "&8Stats &8| &f" + s.target);
        int slot = 9;
        for (String stat : cat.stats) {
            if (slot >= inv.getSize() - 9) break;
            inv.setItem(slot++, UiKit.card(Material.PAPER, "&b" + stat, "Reset this counter",
                    List.of("Runs: " + this.statCommand
                            .replace("%player%", s.target)
                            .replace("%stat%", stat)
                            .replace("%value%", "0")),
                    null, "to select"));
        }
        inv.setItem(inv.getSize() - 5, UiKit.card(Material.TNT, "&c&lALL STATS",
                "Reset every counter", List.of(cat.stats.size() + " counters set to 0."),
                "No undo.", "to select"));
        inv.setItem(inv.getSize() - 1, UiKit.back("the wipe menu"));
        UiKit.fill(inv);
        s.inventory = inv;
        s.stage = "stats";
        s.category = cat.id;
        viewer.openInventory(inv);
    }

    private void openConfirm(Player viewer, Session s) {
        Inventory inv = UiKit.chest(3, "&8Confirm wipe &8| &f" + s.target);
        inv.setItem(11, UiKit.item(Material.LIME_WOOL, "&a&lCONFIRM",
                "&7Target: &f" + s.target,
                "&7Action: &f" + s.label,
                " ",
                "&cThis runs console commands immediately.",
                " ",
                "&e" + UiKit.ARROW + "&e&l&n CLICK &r&eto run it"));
        inv.setItem(15, UiKit.item(Material.RED_WOOL, "&c&lCANCEL", "&7Change nothing."));
        inv.setItem(13, UiKit.head(Bukkit.getOfflinePlayer(s.target), "&f" + s.target,
                List.of("&7Nothing has run yet.")));
        UiKit.fill(inv);
        s.inventory = inv;
        s.stage = "confirm";
        viewer.openInventory(inv);
    }

    /* ------------------------------------------------------------------ */
    /*  Clicks                                                            */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        Session s = this.sessions.get(viewer.getUniqueId());
        if (s == null || s.inventory == null || !s.inventory.equals(event.getInventory())) return;
        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        String name = clicked.getItemMeta() == null ? ""
                : ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
        if (name == null || name.isBlank()) return;

        if (clicked.getType() == Material.BARRIER && name.contains("Close")) {
            viewer.closeInventory();
            this.sessions.remove(viewer.getUniqueId());
            return;
        }
        if (clicked.getType() == Material.ARROW) { openMain(viewer, s.target); return; }

        switch (s.stage) {
            case "main" -> {
                if (this.fullWipe != null && UiKit.strip(this.fullWipe.display).equalsIgnoreCase(name)) {
                    s.category = "full";
                    s.amount = "all";
                    s.label = "FULL WIPE (" + String.join(", ", this.fullOrder) + ")";
                    openConfirm(viewer, s);
                    return;
                }
                Category cat = byDisplay(name);
                if (cat == null) return;
                if (!cat.stats.isEmpty()) { openStats(viewer, s, cat); return; }
                if (cat.amounts && !cat.takeCommands.isEmpty()) { openAmounts(viewer, s, cat); return; }
                s.category = cat.id;
                s.amount = "all";
                s.label = UiKit.strip(cat.display) + " - reset all";
                openConfirm(viewer, s);
            }
            case "amount" -> {
                Category cat = this.categories.get(s.category);
                if (cat == null) return;
                if (clicked.getType() == Material.TNT) {
                    s.amount = "all";
                    s.label = UiKit.strip(cat.display) + " - reset all";
                    openConfirm(viewer, s);
                } else if (clicked.getType() == Material.WRITABLE_BOOK) {
                    viewer.closeInventory();
                    this.amountPrompts.put(viewer.getUniqueId(), s.category);
                    viewer.sendMessage(UiKit.colour("&eType the amount to remove, or &fcancel&e."));
                } else if (clicked.getType() == Material.GOLD_NUGGET) {
                    String digits = name.replaceAll("[^0-9]", "");
                    if (digits.isEmpty()) return;
                    s.amount = digits;
                    s.label = UiKit.strip(cat.display) + " - take " + digits;
                    openConfirm(viewer, s);
                }
            }
            case "stats" -> {
                Category cat = this.categories.get(s.category);
                if (cat == null) return;
                if (clicked.getType() == Material.TNT) {
                    s.amount = "all";
                    s.label = "Reset every stat (" + cat.stats.size() + ")";
                } else {
                    if (!cat.stats.contains(name)) return;
                    s.amount = name;
                    s.label = "Reset stat " + name;
                }
                openConfirm(viewer, s);
            }
            case "confirm" -> {
                if (clicked.getType() == Material.LIME_WOOL) {
                    viewer.closeInventory();
                    run(viewer, s);
                    this.sessions.remove(viewer.getUniqueId());
                } else if (clicked.getType() == Material.RED_WOOL) {
                    openMain(viewer, s.target);
                }
            }
            default -> { }
        }
    }

    private Category byDisplay(String strippedName) {
        for (Category c : this.categories.values()) {
            if (UiKit.strip(c.display).equalsIgnoreCase(strippedName)) return c;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAmountPrompt(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        String categoryId = this.amountPrompts.remove(p.getUniqueId());
        if (categoryId == null) return;
        event.setCancelled(true);
        String answer = event.getMessage().trim();
        SchedulerCompat.run(this.plugin, () -> {
            if (answer.equalsIgnoreCase("cancel")) {
                p.sendMessage(UiKit.colour("&7Cancelled."));
                return;
            }
            String digits = answer.replaceAll("[^0-9]", "");
            if (digits.isEmpty()) {
                p.sendMessage(UiKit.colour("&cThat is not a number. Nothing was run."));
                return;
            }
            Session s = this.sessions.get(p.getUniqueId());
            if (s == null) { p.sendMessage(UiKit.colour("&cThat menu expired.")); return; }
            Category cat = this.categories.get(categoryId);
            s.category = categoryId;
            s.amount = digits;
            s.label = (cat == null ? categoryId : UiKit.strip(cat.display)) + " - take " + digits;
            openConfirm(p, s);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.sessions.remove(event.getPlayer().getUniqueId());
        this.amountPrompts.remove(event.getPlayer().getUniqueId());
    }

    /* ------------------------------------------------------------------ */
    /*  Running                                                           */
    /* ------------------------------------------------------------------ */

    private void run(Player staff, Session s) {
        List<String> commands = new ArrayList<>();
        if ("full".equals(s.category)) {
            for (String id : this.fullOrder) {
                Category cat = this.categories.get(id.toLowerCase(Locale.ROOT));
                if (cat != null) commands.addAll(commandsFor(cat, "all", s.target));
            }
            commands.addAll(expand(this.fullWipe == null ? List.of() : this.fullWipe.allCommands,
                    s.target, "0", null));
        } else {
            Category cat = this.categories.get(s.category);
            if (cat == null) { staff.sendMessage(UiKit.colour("&cThat category vanished.")); return; }
            commands.addAll(commandsFor(cat, s.amount, s.target));
        }

        // The ender chest has no vanilla command. /clear empties the main
        // inventory and does not touch it, so a config line alone would have
        // silently done nothing while reporting success.
        int enderCleared = 0;
        boolean wantsEnder = "full".equals(s.category) || "enderchest".equals(s.category);
        if (wantsEnder) enderCleared = clearEnderChest(s.target);

        if (commands.isEmpty() && !wantsEnder) {
            staff.sendMessage(UiKit.colour("&eNothing to run - that category has no commands "
                    + "configured."));
            return;
        }
        for (String cmd : commands) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);

        staff.sendMessage(UiKit.colour("&a&lWIPE COMPLETE &7- &f" + s.target));
        staff.sendMessage(UiKit.colour("&7" + s.label));
        if (wantsEnder) {
            staff.sendMessage(UiKit.colour(enderCleared >= 0
                    ? "&8  ender chest: " + enderCleared + " stack(s) removed"
                    : "&8  ender chest: player has never joined, nothing to clear"));
        }
        for (String cmd : commands) staff.sendMessage(UiKit.colour("&8  /" + cmd));
        this.plugin.getLogger().info("[PlayerWipe] " + staff.getName() + " ran '" + s.label
                + "' on " + s.target + " (" + commands.size() + " command(s)"
                + (wantsEnder ? ", " + enderCleared + " ender stack(s)" : "") + ").");
        discordLog(staff.getName(), s.target, s.label, commands, enderCleared, wantsEnder);
        UiKit.sound(staff, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.4F);
    }

    /**
     * Empties the ender chest, online or off.
     *
     * @return stacks removed, or -1 when the account has no data to load
     */
    private int clearEnderChest(String target) {
        Player online = Bukkit.getPlayerExact(target);
        if (online != null) {
            int n = 0;
            for (ItemStack it : online.getEnderChest().getContents()) {
                if (it != null && it.getType() != Material.AIR) n++;
            }
            online.getEnderChest().clear();
            return n;
        }
        // Offline: Bukkit exposes the ender chest on OfflinePlayer only from
        // 1.20.4 onward, so this is reflective. On an older server the wipe
        // reports that it could not be done rather than pretending it was.
        OfflinePlayer off = Bukkit.getOfflinePlayer(target);
        if (!off.hasPlayedBefore()) return -1;
        try {
            Object inv = OfflinePlayer.class.getMethod("getEnderChest").invoke(off);
            org.bukkit.inventory.Inventory ender = (org.bukkit.inventory.Inventory) inv;
            int n = 0;
            for (ItemStack it : ender.getContents()) {
                if (it != null && it.getType() != Material.AIR) n++;
            }
            ender.clear();
            return n;
        } catch (Throwable ex) {
            this.plugin.getLogger().warning("[PlayerWipe] could not open " + target
                    + "'s offline ender chest (" + ex.getClass().getSimpleName()
                    + "). They must be online for that part.");
            return -1;
        }
    }

    /**
     * Mirrors the wipe to Discord through DiscordSRV.
     *
     * <p>Reflective and entirely optional — DiscordSRV is not a dependency, and
     * with it absent this does nothing at all. A wipe is destructive and
     * irreversible, so it belongs in a channel staff cannot quietly clear,
     * which is the point of logging it off-server.
     */
    private void discordLog(String staff, String target, String label,
                            List<String> commands, int enderCleared, boolean wantsEnder) {
        if (!this.discordEnabled || this.discordChannel.isBlank()) return;
        StringBuilder body = new StringBuilder();
        body.append("**").append(staff).append("** wiped **").append(target).append("**\n");
        body.append("Category: `").append(label).append("`\n");
        if (wantsEnder) {
            body.append("Ender chest: ")
                .append(enderCleared >= 0 ? enderCleared + " stack(s)" : "unavailable")
                .append('\n');
        }
        // The commands themselves are deliberately NOT listed. They name the
        // console syntax, the stat keys and the vault plugin behind the wipe, and
        // the Discord channel is read by more people than can run them. The count
        // is the part a reader needs; the syntax is not.
        body.append(commands.size()).append(" action(s) applied\n");
        final String message = body.toString();
        SchedulerCompat.runAsync(this.plugin, () -> {
            try {
                Class<?> srv = Class.forName("github.scarsz.discordsrv.DiscordSRV");
                Object instance = srv.getMethod("getPlugin").invoke(null);
                Object channel = srv.getMethod("getDestinationTextChannelForGameChannelName",
                        String.class).invoke(instance, this.discordChannel);
                if (channel == null) {
                    this.plugin.getLogger().warning("[PlayerWipe] DiscordSRV has no channel "
                            + "mapped to '" + this.discordChannel
                            + "'. Add it under channels in DiscordSRV's config.");
                    return;
                }
                // Found by shape, not by signature. DiscordUtil.sendMessage has
                // several overloads and the channel parameter type has moved
                // package twice across JDA versions; matching on "two params,
                // second is a String, first accepts what we hold" survives that.
                Class<?> util = Class.forName("github.scarsz.discordsrv.util.DiscordUtil");
                for (java.lang.reflect.Method m : util.getMethods()) {
                    if (!m.getName().equals("sendMessage")) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != 2 || p[1] != String.class) continue;
                    if (!p[0].isInstance(channel)) continue;
                    m.invoke(null, channel, message);
                    return;
                }
                this.plugin.getLogger().warning("[PlayerWipe] DiscordSRV is installed but no "
                        + "usable DiscordUtil.sendMessage(channel, String) was found; "
                        + "wipe not mirrored.");
            } catch (ClassNotFoundException ex) {
                // DiscordSRV not installed. Nothing worth saying.
            } catch (Throwable ex) {
                this.plugin.getLogger().warning("[PlayerWipe] DiscordSRV log failed: "
                        + ex.getClass().getSimpleName() + " " + ex.getMessage());
            }
        });
    }

    /** Resolves one category plus one choice into console commands. */
    private List<String> commandsFor(Category cat, String choice, String target) {
        if (!cat.stats.isEmpty()) {
            List<String> out = new ArrayList<>();
            List<String> stats = "all".equals(choice) ? cat.stats : List.of(choice);
            for (String stat : stats) {
                out.add(this.statCommand
                        .replace("%player%", target)
                        .replace("%stat%", stat)
                        .replace("%value%", "0")
                        .replaceFirst("^/", ""));
            }
            return out;
        }
        if ("all".equals(choice) || cat.takeCommands.isEmpty()) {
            return expand(cat.allCommands, target, "0", null);
        }
        return expand(cat.takeCommands, target, choice, null);
    }

    private static List<String> expand(List<String> templates, String player,
                                       String amount, String stat) {
        List<String> out = new ArrayList<>();
        for (String t : templates) {
            if (t == null || t.isBlank()) continue;
            out.add(t.replace("%player%", player)
                     .replace("%PLAYER%", player)
                     .replace("%amount%", amount)
                     .replace("%stat%", stat == null ? "" : stat)
                     .replaceFirst("^/", ""));
        }
        return out;
    }

    /** Tab completion source for the second argument. */
    public List<String> categoryNames() {
        return new ArrayList<>(this.categories.keySet());
    }
}
