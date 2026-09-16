/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.milkbowl.vault.economy.Economy
 *  org.bukkit.Bukkit
 *  org.bukkit.ChatColor
 *  org.bukkit.Material
 *  org.bukkit.NamespacedKey
 *  org.bukkit.OfflinePlayer
 *  org.bukkit.block.Block
 *  org.bukkit.block.BlockState
 *  org.bukkit.block.Chest
 *  org.bukkit.command.CommandSender
 *  org.bukkit.configuration.ConfigurationSection
 *  org.bukkit.configuration.file.YamlConfiguration
 *  org.bukkit.enchantments.Enchantment
 *  org.bukkit.entity.HumanEntity
 *  org.bukkit.entity.Player
 *  org.bukkit.event.EventHandler
 *  org.bukkit.event.Listener
 *  org.bukkit.event.block.Action
 *  org.bukkit.event.inventory.InventoryClickEvent
 *  org.bukkit.event.player.PlayerCommandPreprocessEvent
 *  org.bukkit.event.player.PlayerInteractEvent
 *  org.bukkit.inventory.Inventory
 *  org.bukkit.inventory.InventoryHolder
 *  org.bukkit.inventory.ItemStack
 *  org.bukkit.inventory.meta.ItemMeta
 *  org.bukkit.persistence.PersistentDataContainer
 *  org.bukkit.persistence.PersistentDataType
 *  org.bukkit.plugin.Plugin
 *  org.bukkit.plugin.RegisteredServiceProvider
 *  org.bukkit.plugin.java.JavaPlugin
 */
package com.coltcore.core.modules;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.CallSite;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class BillFordModule
implements Listener {
    private final JavaPlugin plugin;
    private final NamespacedKey itemKey;
    private File playersFile;
    private File configFile;
    private YamlConfiguration players;
    private YamlConfiguration config;
    private Economy economy;

    public BillFordModule(JavaPlugin plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey((Plugin)plugin, "billford_item");
    }

    public void enable() {
        this.playersFile = new File(this.plugin.getDataFolder(), "billford-players.yml");
        this.configFile = new File(this.plugin.getDataFolder(), "billford.yml");
        if (!this.configFile.exists()) {
            this.plugin.saveResource("billford.yml", false);
        }
        this.players = YamlConfiguration.loadConfiguration((File)this.playersFile);
        this.config = YamlConfiguration.loadConfiguration((File)this.configFile);
        RegisteredServiceProvider provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        this.economy = provider == null ? null : (Economy)provider.getProvider();
    }

    public void reload() {
        this.config = YamlConfiguration.loadConfiguration((File)this.configFile);
        this.players = YamlConfiguration.loadConfiguration((File)this.playersFile);
    }

    public boolean toggle(CommandSender sender) {
        if (!(sender instanceof Player)) {
            return true;
        }
        Player player = (Player)sender;
        if (!player.hasPermission("gildedbillford.use")) {
            player.sendMessage(this.color("&cNo permission."));
            return true;
        }
        boolean setMode = !this.players.getBoolean("players." + String.valueOf(player.getUniqueId()) + ".set-mode", false);
        this.players.set("players." + String.valueOf(player.getUniqueId()) + ".set-mode", (Object)setMode);
        this.savePlayers();
        if (setMode) {
            player.sendMessage(this.color("Bill Ford manual mode enabled!"));
            this.openSetTrades(player);
        } else {
            player.sendMessage(this.color("Bill Ford manual mode disabled!"));
            Bukkit.dispatchCommand((CommandSender)player, (String)this.config.getString("settings.random-command", "billford"));
        }
        return true;
    }

    public boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("gildedbillford.admin") && !sender.hasPermission("coltcore.admin")) {
            sender.sendMessage(this.color("&cNo permission."));
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("open")) {
            if (sender instanceof Player) {
                Player player = (Player)sender;
                this.openSetTrades(player);
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            this.reload();
            sender.sendMessage(this.color("&aBillFord module reloaded."));
            return true;
        }
        if (args[0].equalsIgnoreCase("setcost") && args.length == 5) {
            String trade = args[1].toLowerCase(Locale.ROOT);
            String index = args[2];
            Material material = Material.matchMaterial((String)args[3]);
            int amount = this.parseInt(args[4]);
            if (!this.config.isConfigurationSection("trades." + trade) || material == null || amount < 1) {
                sender.sendMessage(this.color("&cUsage: /billfordadmin setcost <trade> <slot> <material> <amount>"));
                return true;
            }
            this.config.set("trades." + trade + ".ingredients." + index + ".billford-id", null);
            this.config.set("trades." + trade + ".ingredients." + index + ".material", (Object)material.name());
            this.config.set("trades." + trade + ".ingredients." + index + ".amount", (Object)amount);
            this.saveBillFordConfig();
            sender.sendMessage(this.color("&aUpdated recipe cost."));
            return true;
        }
        if (args[0].equalsIgnoreCase("setname") && args.length >= 3) {
            String trade = args[1].toLowerCase(Locale.ROOT);
            if (!this.config.isConfigurationSection("trades." + trade)) {
                sender.sendMessage(this.color("&cUnknown trade."));
                return true;
            }
            this.config.set("trades." + trade + ".name", (Object)String.join((CharSequence)" ", Arrays.copyOfRange(args, 2, args.length)));
            this.saveBillFordConfig();
            sender.sendMessage(this.color("&aUpdated trade name."));
            return true;
        }
        sender.sendMessage(this.color("&cUsage: /billfordadmin <open|reload|setcost|setname>"));
        return true;
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().toLowerCase(Locale.ROOT);
        if (!message.equals("/billford") && !message.startsWith("/billford ")) {
            return;
        }
        Player player = event.getPlayer();
        if (!this.players.getBoolean("players." + String.valueOf(player.getUniqueId()) + ".set-mode", false)) {
            return;
        }
        event.setCancelled(true);
        this.openSetTrades(player);
    }

    @EventHandler(ignoreCancelled=true)
    public void onMultitoolUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!this.isNamed(item, "&dMultitool")) {
            return;
        }
        Block block = event.getClickedBlock();
        BlockState blockState = block.getState();
        if (blockState instanceof Chest) {
            Chest chest = (Chest)blockState;
            event.setCancelled(true);
            this.sellChest(player, chest);
        } else if (this.isLog(block.getType())) {
            event.setCancelled(true);
            this.chopTree(player, block, item);
        } else if (this.isDrillable(block.getType())) {
            event.setCancelled(true);
            this.drill3x3(player, block, item);
        }
    }

    private void openSetTrades(Player player) {
        Inventory inventory = Bukkit.createInventory((InventoryHolder)new BillFordHolder(), (int)27, (String)this.color(this.config.getString("settings.title", "&8BillFord Set Trades")));
        ConfigurationSection trades = this.config.getConfigurationSection("trades");
        if (trades != null) {
            for (String id : trades.getKeys(false)) {
                ConfigurationSection section = trades.getConfigurationSection(id);
                if (section == null) continue;
                inventory.setItem(section.getInt("slot"), this.previewItem(id, section));
            }
        }
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Player player;
        block11: {
            block10: {
                if (!(event.getInventory().getHolder() instanceof BillFordHolder)) {
                    return;
                }
                event.setCancelled(true);
                HumanEntity humanEntity = event.getWhoClicked();
                if (!(humanEntity instanceof Player)) break block10;
                player = (Player)humanEntity;
                if (event.getClickedInventory() != null) break block11;
            }
            return;
        }
        String tradeId = this.readBillFordId(event.getCurrentItem());
        if (tradeId == null) {
            return;
        }
        ConfigurationSection section = this.config.getConfigurationSection("trades." + tradeId);
        if (section == null) {
            return;
        }
        ItemStack result = this.resultItem(tradeId, section);
        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(this.color(this.config.getString("settings.inventory-full-message", "&cYour inventory is full.")));
            return;
        }
        if (!this.hasIngredients(player, section)) {
            player.sendMessage(this.color(this.config.getString("settings.no-items-message", "&cYou do not have the required recipe items.")));
            return;
        }
        this.takeIngredients(player, section);
        String giveCommand = section.getString("give-command", "");
        if (giveCommand == null || giveCommand.isBlank()) {
            player.getInventory().addItem(new ItemStack[]{result});
        } else {
            Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)giveCommand.replace("%player%", player.getName()).replace("%PLAYER%", player.getName()));
        }
        String itemName = this.stripColor(result.getItemMeta().getDisplayName());
        this.boxMessage(player, "&#00ff00&lBill Ford", this.config.getString("settings.bought-message", "&fTraded for &e%item%&f.").replace("%item%", itemName));
    }

    private ItemStack previewItem(String id, ConfigurationSection section) {
        ItemStack item = this.resultItem(id, section);
        ItemMeta meta = item.getItemMeta();
        ArrayList<String> lore = meta.hasLore() ? new ArrayList<String>(meta.getLore()) : new ArrayList();
        lore.add(this.color("&8----------------"));
        lore.add(this.color("&eRecipe:"));
        ConfigurationSection ingredients = section.getConfigurationSection("ingredients");
        if (ingredients != null) {
            for (String key : ingredients.getKeys(false)) {
                ConfigurationSection ingredient = ingredients.getConfigurationSection(key);
                if (ingredient == null) continue;
                lore.add(this.color("&7- " + ingredient.getInt("amount", 1) + "x " + this.ingredientName(ingredient)));
            }
        }
        lore.add(this.color("&aClick to trade"));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack resultItem(String id, ConfigurationSection section) {
        Material material = Material.matchMaterial((String)section.getString("material", "STONE"));
        ItemStack item = new ItemStack(material == null ? Material.STONE : material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(this.color(section.getString("name", id)));
        meta.setLore(section.getStringList("lore").stream().map(this::color).toList());
        meta.getPersistentDataContainer().set(this.itemKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        ConfigurationSection enchants = section.getConfigurationSection("enchants");
        if (enchants != null) {
            for (String key : enchants.getKeys(false)) {
                Enchantment enchantment = Enchantment.getByKey((NamespacedKey)NamespacedKey.minecraft((String)key.toLowerCase(Locale.ROOT)));
                if (enchantment == null) continue;
                item.addUnsafeEnchantment(enchantment, enchants.getInt(key));
            }
        }
        return item;
    }

    private boolean hasIngredients(Player player, ConfigurationSection trade) {
        ConfigurationSection ingredients = trade.getConfigurationSection("ingredients");
        if (ingredients == null) {
            return true;
        }
        for (String key : ingredients.getKeys(false)) {
            ConfigurationSection ingredient = ingredients.getConfigurationSection(key);
            if (ingredient != null && this.countIngredient(player, ingredient) >= ingredient.getInt("amount", 1)) continue;
            return false;
        }
        return true;
    }

    private int countIngredient(Player player, ConfigurationSection ingredient) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (!this.matchesIngredient(item, ingredient)) continue;
            count += item.getAmount();
        }
        return count;
    }

    private void takeIngredients(Player player, ConfigurationSection trade) {
        ConfigurationSection ingredients = trade.getConfigurationSection("ingredients");
        if (ingredients == null) {
            return;
        }
        block0: for (String key : ingredients.getKeys(false)) {
            ConfigurationSection ingredient = ingredients.getConfigurationSection(key);
            int remaining = ingredient.getInt("amount", 1);
            for (ItemStack item : player.getInventory().getContents()) {
                if (remaining <= 0) continue block0;
                if (!this.matchesIngredient(item, ingredient)) continue;
                int take = Math.min(remaining, item.getAmount());
                item.setAmount(item.getAmount() - take);
                remaining -= take;
            }
        }
    }

    private boolean matchesIngredient(ItemStack item, ConfigurationSection ingredient) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        String billfordId = ingredient.getString("billford-id");
        if (billfordId != null) {
            return billfordId.equals(this.readBillFordId(item));
        }
        Material material = Material.matchMaterial((String)ingredient.getString("material", ""));
        if (material == null || item.getType() != material) {
            return false;
        }
        String displayName = ingredient.getString("display-name", "");
        return displayName == null || displayName.isBlank() || item.hasItemMeta() && item.getItemMeta().hasDisplayName() && this.stripColor(item.getItemMeta().getDisplayName()).equalsIgnoreCase(this.stripColor(this.color(displayName)));
    }

    private String ingredientName(ConfigurationSection ingredient) {
        String billfordId = ingredient.getString("billford-id");
        if (billfordId != null) {
            return billfordId.replace('_', ' ');
        }
        String displayName = ingredient.getString("display-name", "");
        if (displayName != null && !displayName.isBlank()) {
            return this.stripColor(this.color(displayName));
        }
        return ingredient.getString("material", "UNKNOWN").replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private String readBillFordId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        return (String)data.get(this.itemKey, PersistentDataType.STRING);
    }

    private void drill3x3(Player player, Block center, ItemStack tool) {
        int broken = 0;
        for (int x = -1; x <= 1; ++x) {
            for (int y = -1; y <= 1; ++y) {
                for (int z = -1; z <= 1; ++z) {
                    Block block = center.getWorld().getBlockAt(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (!this.isDrillable(block.getType())) continue;
                    block.breakNaturally(tool);
                    ++broken;
                }
            }
        }
        this.boxMessage(player, "&#00ff00&lBill Ford", "&fMultitool drilled &e" + broken + " &fblocks.");
    }

    private void chopTree(Player player, Block start, ItemStack tool) {
        ArrayDeque<Block> queue = new ArrayDeque<Block>();
        HashSet<CallSite> seen = new HashSet<CallSite>();
        queue.add(start);
        int broken = 0;
        while (!queue.isEmpty() && broken < 96) {
            Block block = (Block)queue.poll();
            String key = block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
            if (!seen.add((CallSite)((Object)key)) || !this.isLog(block.getType())) continue;
            block.breakNaturally(tool);
            ++broken;
            for (int x = -1; x <= 1; ++x) {
                for (int y = -1; y <= 1; ++y) {
                    for (int z = -1; z <= 1; ++z) {
                        if (x == 0 && y == 0 && z == 0) continue;
                        queue.add(block.getWorld().getBlockAt(block.getX() + x, block.getY() + y, block.getZ() + z));
                    }
                }
            }
        }
        this.boxMessage(player, "&#00ff00&lBill Ford", "&fMultitool chopped &e" + broken + " &flogs.");
    }

    private void sellChest(Player player, Chest chest) {
        if (this.economy == null) {
            player.sendMessage(this.color("&cEconomy is not ready."));
            return;
        }
        double total = 0.0;
        Inventory inventory = chest.getBlockInventory();
        for (int slot = 0; slot < inventory.getSize(); ++slot) {
            double unit;
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir() || (unit = this.config.getDouble("multitool-sell-values." + item.getType().name(), 0.0)) <= 0.0) continue;
            total += unit * (double)item.getAmount();
            inventory.setItem(slot, null);
        }
        if (total <= 0.0) {
            player.sendMessage(this.color("&cNo sellable items found in that chest."));
            return;
        }
        this.economy.depositPlayer((OfflinePlayer)player, total);
        this.boxMessage(player, "&#00ff00&lBill Ford", "&fMultitool sold chest contents for &a$" + Math.round(total) + "&f.");
    }

    private boolean isNamed(ItemStack item, String expected) {
        return item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName() && this.stripColor(item.getItemMeta().getDisplayName()).equalsIgnoreCase(this.stripColor(this.color(expected)));
    }

    private boolean isLog(Material material) {
        return material.name().endsWith("_LOG") || material.name().endsWith("_STEM");
    }

    private boolean isDrillable(Material material) {
        if (material.isAir() || !material.isBlock()) {
            return false;
        }
        String name = material.name();
        return name.contains("STONE") || name.contains("DEEPSLATE") || name.contains("ORE") || name.equals("NETHERRACK") || name.equals("BASALT") || name.equals("BLACKSTONE") || name.equals("OBSIDIAN") || name.equals("END_STONE");
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException ex) {
            return -1;
        }
    }

    private void savePlayers() {
        try {
            this.players.save(this.playersFile);
        }
        catch (IOException ex) {
            this.plugin.getLogger().warning("Could not save BillFord player data: " + ex.getMessage());
        }
    }

    private void saveBillFordConfig() {
        try {
            this.config.save(this.configFile);
        }
        catch (IOException ex) {
            this.plugin.getLogger().warning("Could not save billford.yml: " + ex.getMessage());
        }
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes((char)'&', (String)(message == null ? "" : message));
    }

    /**
     * Sends a boxed message:
     * ------------------------------
     * {summaryLine}
     * {contentLine}
     * ------------------------------
     */
    private void boxMessage(CommandSender recipient, String summaryLine, String contentLine) {
        recipient.sendMessage(this.color("&8&m----------------------------------------"));
        recipient.sendMessage(this.color(summaryLine));
        recipient.sendMessage(this.color(contentLine));
        recipient.sendMessage(this.color("&8&m----------------------------------------"));
    }

    private String stripColor(String message) {
        return ChatColor.stripColor((String)(message == null ? "" : message));
    }

    private static final class BillFordHolder
    implements InventoryHolder {
        private BillFordHolder() {
        }

        public Inventory getInventory() {
            return null;
        }
    }
}
