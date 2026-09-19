/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.ChatColor
 *  org.bukkit.Location
 *  org.bukkit.Material
 *  org.bukkit.Statistic
 *  org.bukkit.World
 *  org.bukkit.block.Block
 *  org.bukkit.block.BlockState
 *  org.bukkit.block.CreatureSpawner
 *  org.bukkit.command.Command
 *  org.bukkit.command.CommandExecutor
 *  org.bukkit.command.CommandSender
 *  org.bukkit.command.PluginCommand
 *  org.bukkit.command.TabCompleter
 *  org.bukkit.entity.EntityType
 *  org.bukkit.entity.HumanEntity
 *  org.bukkit.entity.Player
 *  org.bukkit.event.EventHandler
 *  org.bukkit.event.EventPriority
 *  org.bukkit.event.Listener
 *  org.bukkit.event.inventory.InventoryClickEvent
 *  org.bukkit.event.inventory.InventoryMoveItemEvent
 *  org.bukkit.event.inventory.InventoryType
 *  org.bukkit.event.player.PlayerJoinEvent
 *  org.bukkit.inventory.Inventory
 *  org.bukkit.inventory.InventoryHolder
 *  org.bukkit.inventory.ItemStack
 *  org.bukkit.inventory.meta.ItemMeta
 *  org.bukkit.plugin.Plugin
 *  org.bukkit.plugin.java.JavaPlugin
 */
package com.gildedmc.core.modules;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import com.gildedmc.core.GildedCorePlugin;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class IntegratedCoreModuleX {
    private static JavaPlugin plugin;
    /** Shared banner rule used by join/leave and broadcast banners. */
    static final String RULE = "\u00a78\u00a7m----------------------------------------";

    /**
     * The player's LuckPerms rank prefix, trailing-space-normalised, or a plain
     * grey fallback when LuckPerms is absent or the player has no prefix set.
     *
     * GildedCorePlugin#prefix(Player) already does the LuckPerms lookup with a
     * group-colour fallback, so this just adapts it for banner use.
     */
    static String rankPrefix(Player player) {
        try {
            if (plugin instanceof com.gildedmc.core.GildedCorePlugin core) {
                String prefix = core.prefix(player);
                if (prefix != null && !prefix.isBlank()) {
                    String out = IntegratedCoreModuleX.cc(prefix);
                    return out.endsWith(" ") ? out : out + " ";
                }
            }
        } catch (Throwable ignored) {
            // fall through to the plain fallback
        }
        return "\u00a77";
    }

    private static final Map<String, String> joinMessages;
    private static final File JOIN_FILE;

    private IntegratedCoreModuleX() {
    }

    public static void register(JavaPlugin javaPlugin) {
        plugin = javaPlugin;
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        IntegratedCoreModuleX.loadJoinMessages();
        Bukkit.getPluginManager().registerEvents((Listener)new JoinMessage(), (Plugin)plugin);
        PlayerWipe playerWipe = new PlayerWipe();
        Bukkit.getPluginManager().registerEvents((Listener)playerWipe, (Plugin)plugin);
        IntegratedCoreModuleX.set("playerwipe", playerWipe);
        IntegratedCoreModuleX.set("joinmessage", new JoinMessage());
        IntegratedCoreModuleX.set("ping", new PingCommand());
        plugin.getLogger().info("Integrated GildedCore extras loaded: playerwipe, joinmessage, ping.");
    }

    private static void set(String string, Object object) {
        try {
            PluginCommand pluginCommand = plugin.getCommand(string);
            if (pluginCommand != null) {
                if (object instanceof CommandExecutor) {
                    pluginCommand.setExecutor((CommandExecutor)object);
                }
                if (object instanceof TabCompleter) {
                    pluginCommand.setTabCompleter((TabCompleter)object);
                }
            }
        }
        catch (Throwable throwable) {
            plugin.getLogger().warning("Could not bind command /" + string + ": " + throwable.getMessage());
        }
    }

    private static boolean owner(CommandSender commandSender) {
        if (!(commandSender instanceof Player)) {
            commandSender.sendMessage("\u00a7cOnly staff can use this command in-game.");
            return false;
        }
        // Was a hardcoded username. Names are spoofable on offline-mode and
        // misconfigured-proxy servers, so the permission node is the gate.
        if (!((Player) commandSender).hasPermission("gildedcore.admin")) {
            commandSender.sendMessage("\u00a7cOnly admins can use this.");
            return false;
        }
        return true;
    }

    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static String cc(String string) {
        if (string == null) return "";
        Matcher m = HEX.matcher(string);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String hex = m.group(1);
            StringBuilder rep = new StringBuilder("\u00a7x");
            for (char c : hex.toCharArray()) rep.append('\u00a7').append(c);
            m.appendReplacement(sb, rep.toString());
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    private static ItemStack icon(Material material, String string, String ... stringArray) {
        ItemStack itemStack = new ItemStack(material);
        try {
            ItemMeta itemMeta = itemStack.getItemMeta();
            if (itemMeta != null) {
                itemMeta.setDisplayName(IntegratedCoreModuleX.cc(string));
                itemMeta.setLore(Arrays.asList(stringArray));
                itemStack.setItemMeta(itemMeta);
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        return itemStack;
    }

    private static String strip(String string) {
        return ChatColor.stripColor((String)(string == null ? "" : string));
    }

    private static File joinFile() {
        return new File(plugin.getDataFolder(), "joinmessages.properties");
    }

    private static void loadJoinMessages() {
        block10: {
            try {
                File file = IntegratedCoreModuleX.joinFile();
                if (!file.exists()) {
                    return;
                }
                Properties properties = new Properties();
                try (FileInputStream fileInputStream = new FileInputStream(file);){
                    properties.load(fileInputStream);
                }
                for (String string : properties.stringPropertyNames()) {
                    joinMessages.put(string, properties.getProperty(string, ""));
                }
            }
            catch (Throwable throwable) {
                if (plugin == null) break block10;
                plugin.getLogger().warning("Failed to load join messages: " + throwable.getMessage());
            }
        }
    }

    private static void saveJoinMessages() {
        try {
            Properties properties = new Properties();
            properties.putAll(joinMessages);
            try (FileOutputStream fileOutputStream = new FileOutputStream(IntegratedCoreModuleX.joinFile());){
                properties.store(fileOutputStream, "GildedCore join messages");
            }
        }
        catch (Throwable throwable) {
            plugin.getLogger().warning("Failed to save join messages: " + throwable.getMessage());
        }
    }

    public static String joinMessage(Player player) {
        return joinMessages.getOrDefault(player.getUniqueId().toString(), "")
                .replace("%PLAYER%", player.getName())
                .replace("%player%", player.getName());
    }

    static {
        joinMessages = new ConcurrentHashMap<String, String>();
        JOIN_FILE = null;
    }

    public static final class JoinMessage
    implements CommandExecutor,
    Listener,
    TabCompleter {
        public boolean onCommand(CommandSender commandSender, Command command, String string, String[] stringArray) {
            if (!GildedCorePlugin.authorise(commandSender, "joinmessage", plugin)) return true;
            if (!(commandSender instanceof Player)) {
                commandSender.sendMessage("\u00a7cPlayers only.");
                return true;
            }
            Player player = (Player)commandSender;
            if (stringArray.length == 0) {
                player.sendMessage("\u00a7e/joinmessage set <message>");
                player.sendMessage("\u00a7e/joinmessage off");
                player.sendMessage("\u00a7e/joinmessage preview");
                return true;
            }
            String string2 = stringArray[0].toLowerCase(Locale.ROOT);
            if (string2.equals("off") || string2.equals("reset")) {
                joinMessages.remove(player.getUniqueId().toString());
                IntegratedCoreModuleX.saveJoinMessages();
                player.sendMessage("\u00a7aJoin message disabled.");
                return true;
            }
            if (string2.equals("preview")) {
                player.sendMessage(this.formatJoin(player));
                return true;
            }
            if (string2.equals("set")) {
                if (stringArray.length < 2) {
                    player.sendMessage("\u00a7cUsage: /joinmessage set <message>");
                    return true;
                }
                String string3 = String.join((CharSequence)" ", Arrays.copyOfRange(stringArray, 1, stringArray.length));
                if (string3.length() > 96) {
                    string3 = string3.substring(0, 96);
                }
                joinMessages.put(player.getUniqueId().toString(), IntegratedCoreModuleX.cc(string3));
                IntegratedCoreModuleX.saveJoinMessages();
                player.sendMessage("\u00a7aJoin message set: \u00a7f" + IntegratedCoreModuleX.cc(string3));
                return true;
            }
            player.sendMessage("\u00a7cUsage: /joinmessage set <message>|off|preview");
            return true;
        }

        @EventHandler
        public void onJoin(PlayerJoinEvent playerJoinEvent) {
            Player player = playerJoinEvent.getPlayer();
            // Vanished players must not trigger vanilla, formatted or custom
            // join messages. The main plugin also suppresses rank alerts.
            playerJoinEvent.setJoinMessage(VanishSupport.isVanished(player) ? null : this.formatJoin(player));
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent e) {
            Player player = e.getPlayer();
            e.quitMessage(null);
            if (!VanishSupport.isVanished(player)) {
                Bukkit.broadcastMessage(IntegratedCoreModuleX.cc("&c[-] &f" + player.getName()));
            }
        }

        private String formatJoin(Player player) {
            if (VanishSupport.isVanished(player)) return "";
            String string = IntegratedCoreModuleX.joinMessage(player);
            String string2 = IntegratedCoreModuleX.RULE + "\n" + IntegratedCoreModuleX.rankPrefix(player)
                    + "\u00a7f" + player.getName() + " \u00a77joined the server";
            if (string != null && !string.isEmpty()) {
                string2 = string2 + "\n\u00a77" + string;
            }
            return string2 + "\n" + IntegratedCoreModuleX.RULE;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                String input = args[0].toLowerCase(Locale.ROOT);
                return List.of("set", "off", "preview").stream()
                        .filter(value -> value.startsWith(input)).toList();
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("set")) return List.of("%PLAYER%");
            return Collections.emptyList();
        }
    }

    public static final class PlayerWipe
    implements CommandExecutor,
    TabCompleter,
    Listener {
        private static final String TITLE_PREFIX = "\u00a78\u1d18\u029f\u1d00\u028f\u1d07\u0280\u1d21\u026a\u1d18\u1d07: \u00a7f";
        private static final String CONFIRM_TITLE_PREFIX = "\u00a78Confirm Wipe: \u00a7f";
        private static final int CONFIRM_SLOT = 11;
        private static final int CANCEL_SLOT = 15;
        private final Map<String, String> guiTargets = new ConcurrentHashMap<String, String>();
        private final Map<UUID, Map<Integer, String>> guiSlots = new ConcurrentHashMap<UUID, Map<Integer, String>>();
        private final Map<UUID, PendingWipe> pendingConfirms = new ConcurrentHashMap<UUID, PendingWipe>();

        private static final class PendingWipe {
            private final String targetName;
            private final String wipeType;

            private PendingWipe(String targetName, String wipeType) {
                this.targetName = targetName;
                this.wipeType = wipeType;
            }
        }

        public boolean onCommand(CommandSender commandSender, Command command, String string, String[] stringArray) {
            if (!GildedCorePlugin.authorise(commandSender, "playerwipe", plugin)) return true;
            if (!IntegratedCoreModuleX.owner(commandSender)) {
                return true;
            }
            if (stringArray.length < 1) {
                commandSender.sendMessage("\u00a7cUsage: /playerwipe <player>");
                return true;
            }
            Player player = (Player)commandSender;
            String string2 = stringArray[0];
            this.open(player, string2);
            return true;
        }

        private ConfigurationSection categoriesSection() {
            ConfigurationSection section = plugin.getConfig().getConfigurationSection("playerwipe.categories");
            return section != null ? section : new YamlConfiguration();
        }

        private void open(Player player, String string) {
            Inventory inventory = Bukkit.createInventory(null, (int)45, (String)(TITLE_PREFIX + string));
            ItemStack filler = IntegratedCoreModuleX.icon(Material.BLACK_STAINED_GLASS_PANE, " ", new String[0]);
            for (int i = 0; i < inventory.getSize(); ++i) {
                inventory.setItem(i, filler);
            }
            Map<Integer, String> slots = new ConcurrentHashMap<Integer, String>();
            ConfigurationSection categories = this.categoriesSection();
            for (String categoryId : categories.getKeys(false)) {
                ConfigurationSection cat = categories.getConfigurationSection(categoryId);
                if (cat == null || !cat.getBoolean("enabled", true)) continue;
                int slot = cat.getInt("slot", -1);
                if (slot < 0 || slot >= inventory.getSize()) continue;
                Material icon = this.materialOrDefault(cat.getString("icon", "PAPER"), Material.PAPER);
                String name = cat.getString("display-name", "&f" + categoryId);
                inventory.setItem(slot, IntegratedCoreModuleX.icon(icon, name, this.lore(cat)));
                slots.put(slot, categoryId);
            }
            ConfigurationSection full = plugin.getConfig().getConfigurationSection("playerwipe.full-wipe");
            if (full != null && full.getBoolean("enabled", true)) {
                int slot = full.getInt("slot", 31);
                if (slot >= 0 && slot < inventory.getSize()) {
                    Material icon = this.materialOrDefault(full.getString("icon", "BARRIER"), Material.BARRIER);
                    String name = full.getString("display-name", "&4&lFULL WIPE");
                    inventory.setItem(slot, IntegratedCoreModuleX.icon(icon, name, this.lore(full)));
                    slots.put(slot, "full");
                }
            }
            this.guiSlots.put(player.getUniqueId(), slots);
            this.guiTargets.put(player.getUniqueId().toString(), string);
            player.openInventory(inventory);
        }

        private void openConfirm(Player player, String targetName, String wipeType) {
            Inventory inventory = Bukkit.createInventory(null, (int)27, (String)(CONFIRM_TITLE_PREFIX + targetName));
            ItemStack filler = IntegratedCoreModuleX.icon(Material.BLACK_STAINED_GLASS_PANE, " ", new String[0]);
            for (int i = 0; i < inventory.getSize(); ++i) {
                inventory.setItem(i, filler);
            }
            String label = this.wipeLabel(wipeType);
            inventory.setItem(CONFIRM_SLOT, IntegratedCoreModuleX.icon(Material.LIME_WOOL, "&aConfirm", "&7Run &f" + label + " &7wipe on &f" + targetName + "&7.", "&7Click to confirm."));
            inventory.setItem(CANCEL_SLOT, IntegratedCoreModuleX.icon(Material.RED_WOOL, "&cCancel", "&7Cancel this wipe."));
            this.pendingConfirms.put(player.getUniqueId(), new PendingWipe(targetName, wipeType));
            player.openInventory(inventory);
        }

        private String wipeLabel(String wipeType) {
            if ("full".equals(wipeType)) {
                ConfigurationSection full = plugin.getConfig().getConfigurationSection("playerwipe.full-wipe");
                return IntegratedCoreModuleX.strip(IntegratedCoreModuleX.cc(full == null ? "&4RESET ALL" : full.getString("display-name", "&4RESET ALL")));
            }
            ConfigurationSection cat = this.categoriesSection().getConfigurationSection(wipeType);
            return IntegratedCoreModuleX.strip(IntegratedCoreModuleX.cc(cat == null ? wipeType : cat.getString("display-name", wipeType)));
        }

        private Material materialOrDefault(String name, Material fallback) {
            try {
                return Material.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                return fallback;
            }
        }

        private String[] lore(ConfigurationSection section) {
            List<String> list = section.getStringList("lore");
            if (!list.isEmpty()) {
                return list.toArray(new String[0]);
            }
            String single = section.getString("lore", "");
            return single.isEmpty() ? new String[0] : new String[]{single};
        }

        @EventHandler
        public void click(InventoryClickEvent inventoryClickEvent) {
            HumanEntity humanEntity = inventoryClickEvent.getWhoClicked();
            if (!(humanEntity instanceof Player)) {
                return;
            }
            String title = inventoryClickEvent.getView() == null ? "" : inventoryClickEvent.getView().getTitle();
            if (title.startsWith(CONFIRM_TITLE_PREFIX)) {
                inventoryClickEvent.setCancelled(true);
                Player player = (Player)humanEntity;
                if (!player.hasPermission("gildedcore.admin")) {
                    return;
                }
                PendingWipe pending = this.pendingConfirms.remove(player.getUniqueId());
                player.closeInventory();
                if (pending == null) {
                    return;
                }
                int slot = inventoryClickEvent.getRawSlot();
                if (slot == CONFIRM_SLOT) {
                    this.confirmAndApply(player, pending.targetName, pending.wipeType);
                } else if (slot == CANCEL_SLOT) {
                    player.sendMessage("\u00a7cCancelled " + this.wipeLabel(pending.wipeType) + " wipe for " + pending.targetName + ".");
                }
                return;
            }
            if (!title.startsWith(TITLE_PREFIX)) {
                return;
            }
            inventoryClickEvent.setCancelled(true);
            Player player = (Player)humanEntity;
            if (!player.hasPermission("gildedcore.admin")) {
                return;
            }
            String targetName = this.guiTargets.get(player.getUniqueId().toString());
            if (targetName == null) {
                targetName = title.substring(TITLE_PREFIX.length());
            }
            Map<Integer, String> slots = this.guiSlots.get(player.getUniqueId());
            String wipeType = slots == null ? null : slots.get(inventoryClickEvent.getRawSlot());
            if (wipeType != null) {
                this.openConfirm(player, targetName, wipeType);
            }
        }

        private void confirmAndApply(Player player, String targetName, String wipeType) {
            this.apply(player, targetName, wipeType);
            player.sendMessage("\u00a7aApplied/queued " + wipeType + " wipe for " + targetName + ".");
        }

        private void apply(CommandSender commandSender, String targetName, String wipeType) {
            if ("full".equals(wipeType)) {
                List<String> order = plugin.getConfig().getStringList("playerwipe.full-wipe.categories");
                if (order.isEmpty()) {
                    order = new ArrayList<String>(this.categoriesSection().getKeys(false));
                }
                for (String categoryId : order) {
                    this.apply(commandSender, targetName, categoryId);
                }
                return;
            }
            ConfigurationSection cat = this.categoriesSection().getConfigurationSection(wipeType);
            if (cat == null) {
                return;
            }
            Player player = Bukkit.getPlayerExact(targetName);
            if (player == null) {
                this.queue(targetName, wipeType);
                return;
            }
            if (cat.getBoolean("clear-online-inventory", false)) {
                player.getInventory().clear();
                player.updateInventory();
            }
            if (cat.getBoolean("clear-online-enderchest", false)) {
                player.getEnderChest().clear();
            }
            if (cat.getBoolean("reset-online-stats", false)) {
                this.resetStats(player);
            }
            for (String rawCommand : cat.getStringList("commands")) {
                this.dispatch(CommandTemplate.expand(rawCommand, targetName));
            }
        }

        private void dispatch(String string) {
            try {
                Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)string);
            }
            catch (Throwable throwable) {
                plugin.getLogger().fine("Command failed/unknown: /" + string);
            }
        }

        private void resetStats(Player player) {
            try {
                for (Statistic statistic : Statistic.values()) {
                    try {
                        player.setStatistic(statistic, 0);
                    }
                    catch (Throwable throwable) {
                        // empty catch block
                    }
                }
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            try {
                for (Material material : Material.values()) {
                    try {
                        player.setStatistic(Statistic.MINE_BLOCK, material, 0);
                    }
                    catch (Throwable throwable) {
                        // empty catch block
                    }
                    try {
                        player.setStatistic(Statistic.USE_ITEM, material, 0);
                    }
                    catch (Throwable throwable) {
                        // empty catch block
                    }
                }
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }

        @EventHandler
        public void onJoin(PlayerJoinEvent playerJoinEvent) {
            String string = playerJoinEvent.getPlayer().getName();
            List<String> list = this.drain(string);
            if (list.isEmpty()) {
                return;
            }
            com.gildedmc.core.SchedulerCompat.runLater(plugin, () -> {
                for (String string2 : list) {
                    this.apply((CommandSender)Bukkit.getConsoleSender(), string, string2);
                }
            }, 100L);
        }

        private File qfile() {
            return new File(plugin.getDataFolder(), "playerwipe-queue.txt");
        }

        private synchronized void queue(String string, String string2) {
            try (FileOutputStream fileOutputStream = new FileOutputStream(this.qfile(), true);){
                fileOutputStream.write((string.toLowerCase(Locale.ROOT) + "|" + string2 + "\n").getBytes(StandardCharsets.UTF_8));
            }
            catch (Exception exception) {
                plugin.getLogger().warning("Queue write failed: " + exception.getMessage());
            }
        }

        private synchronized List<String> drain(String string) {
            ArrayList<String> arrayList = new ArrayList<String>();
            File file = this.qfile();
            if (!file.exists()) {
                return arrayList;
            }
            ArrayList<String> arrayList2 = new ArrayList<String>();
            try {
                List<String> list = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                for (String string2 : list) {
                    String[] stringArray = string2.split("\\|", 2);
                    if (stringArray.length == 2 && stringArray[0].equalsIgnoreCase(string)) {
                        arrayList.add(stringArray[1]);
                        continue;
                    }
                    arrayList2.add(string2);
                }
                Files.write(file.toPath(), arrayList2, StandardCharsets.UTF_8, new OpenOption[0]);
            }
            catch (Exception exception) {
                // empty catch block
            }
            return arrayList;
        }

        public List<String> onTabComplete(CommandSender commandSender, Command command, String string, String[] stringArray) {
            if (stringArray.length != 1) {
                return Collections.emptyList();
            }
            String string2 = stringArray[0].toLowerCase(Locale.ROOT);
            ArrayList<String> arrayList = new ArrayList<String>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!player.getName().toLowerCase(Locale.ROOT).startsWith(string2)) continue;
                arrayList.add(player.getName());
            }
            return arrayList;
        }
    }

    public static final class CrafterBulkHopper
    implements Listener {
        @EventHandler(ignoreCancelled=true, priority=EventPriority.HIGHEST)
        public void move(InventoryMoveItemEvent inventoryMoveItemEvent) {
            Inventory inventory = inventoryMoveItemEvent.getSource();
            Inventory inventory2 = inventoryMoveItemEvent.getDestination();
            if (inventory == null || inventory2 == null) {
                return;
            }
            if (!this.isHopper(inventory) || !this.isCrafter(inventory2)) {
                return;
            }
            inventoryMoveItemEvent.setCancelled(true);
            this.move16(inventory, inventory2);
        }

        private boolean isHopper(Inventory inventory) {
            try {
                return inventory.getType() == InventoryType.HOPPER || this.name(inventory).contains("hopper");
            }
            catch (Throwable throwable) {
                return this.name(inventory).contains("hopper");
            }
        }

        private boolean isCrafter(Inventory inventory) {
            try {
                return inventory.getType().name().equalsIgnoreCase("CRAFTER") || this.name(inventory).contains("crafter");
            }
            catch (Throwable throwable) {
                return this.name(inventory).contains("crafter");
            }
        }

        private String name(Inventory inventory) {
            Object object = "";
            try {
                object = (String)object + String.valueOf(inventory.getClass().getName());
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            try {
                object = (String)object + " " + String.valueOf(inventory.getHolder().getClass().getName());
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            return ((String)object).toLowerCase(Locale.ROOT);
        }

        private void move16(Inventory inventory, Inventory inventory2) {
            int n = 16;
            for (int i = 0; i < inventory.getSize() && n > 0; ++i) {
                ItemStack itemStack = inventory.getItem(i);
                if (itemStack == null || itemStack.getType() == Material.AIR || itemStack.getAmount() <= 0) continue;
                int n2 = Math.min(n, itemStack.getAmount());
                ItemStack itemStack2 = itemStack.clone();
                itemStack2.setAmount(n2);
                int n3 = this.addToCrafter(inventory2, itemStack2);
                int n4 = n2 - n3;
                if (n4 <= 0) continue;
                itemStack.setAmount(itemStack.getAmount() - n4);
                if (itemStack.getAmount() <= 0) {
                    inventory.setItem(i, null);
                } else {
                    inventory.setItem(i, itemStack);
                }
                n -= n4;
            }
        }

        private int addToCrafter(Inventory inventory, ItemStack itemStack) {
            int n = itemStack.getAmount();
            for (int i = 0; i < inventory.getSize() && n > 0; ++i) {
                ItemStack itemStack2;
                if (this.disabled(inventory, i) || (itemStack2 = inventory.getItem(i)) != null && itemStack2.getType() != Material.AIR) continue;
                ItemStack itemStack3 = itemStack.clone();
                itemStack3.setAmount(Math.min(n, itemStack.getMaxStackSize()));
                inventory.setItem(i, itemStack3);
                n -= itemStack3.getAmount();
            }
            return n;
        }

        private boolean disabled(Inventory inventory, int n) {
            try {
                InventoryHolder inventoryHolder = inventory.getHolder();
                if (inventoryHolder == null) {
                    return false;
                }
                Method method = inventoryHolder.getClass().getMethod("isSlotDisabled", Integer.TYPE);
                Object object = method.invoke((Object)inventoryHolder, n);
                return Boolean.TRUE.equals(object);
            }
            catch (Throwable throwable) {
                return false;
            }
        }
    }

    public static final class PingCommand
    implements CommandExecutor,
    TabCompleter {
        public boolean onCommand(CommandSender commandSender, Command command, String string, String[] stringArray) {
            if (!GildedCorePlugin.authorise(commandSender, "ping", plugin)) return true;
            Player player = null;
            if (stringArray.length > 0) {
                player = Bukkit.getPlayerExact((String)stringArray[0]);
            } else if (commandSender instanceof Player) {
                player = (Player)commandSender;
            }
            if (player == null) {
                commandSender.sendMessage("\u00a7cPlayer not found.");
                return true;
            }
            int n = 0;
            try {
                n = player.getPing();
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            commandSender.sendMessage("\u00a78\u00a7m------------------------------");
            commandSender.sendMessage("\u00a7bPing \u00a77checked by \u00a7f" + commandSender.getName());
            commandSender.sendMessage("\u00a7f" + player.getName() + "\u00a77: \u00a7a" + n + "ms");
            commandSender.sendMessage("\u00a78\u00a7m------------------------------");
            return true;
        }

        public List<String> onTabComplete(CommandSender commandSender, Command command, String string, String[] stringArray) {
            if (stringArray.length != 1) {
                return Collections.emptyList();
            }
            ArrayList<String> arrayList = new ArrayList<String>();
            String string2 = stringArray[0].toLowerCase(Locale.ROOT);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!player.getName().toLowerCase(Locale.ROOT).startsWith(string2)) continue;
                arrayList.add(player.getName());
            }
            return arrayList;
        }
    }
}
