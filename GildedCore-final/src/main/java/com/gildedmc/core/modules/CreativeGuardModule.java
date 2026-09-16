/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.ChatColor
 *  org.bukkit.GameMode
 *  org.bukkit.Material
 *  org.bukkit.entity.HumanEntity
 *  org.bukkit.entity.Player
 *  org.bukkit.event.EventHandler
 *  org.bukkit.event.EventPriority
 *  org.bukkit.event.Listener
 *  org.bukkit.event.block.BlockPlaceEvent
 *  org.bukkit.event.inventory.InventoryCreativeEvent
 *  org.bukkit.event.player.PlayerDropItemEvent
 *  org.bukkit.inventory.ItemStack
 *  org.bukkit.plugin.Plugin
 *  org.bukkit.plugin.java.JavaPlugin
 */
package com.gildedmc.core.modules;

import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class CreativeGuardModule
implements Listener {
    private final JavaPlugin plugin;

    public CreativeGuardModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onCreativeInventory(InventoryCreativeEvent event) {
        Player player;
        HumanEntity humanEntity = event.getWhoClicked();
        if (!(humanEntity instanceof Player) || this.allowed(player = (Player)humanEntity)) {
            return;
        }
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        if (!this.isRealItem(cursor) && !this.isRealItem(current)) {
            return;
        }
        event.setCancelled(true);
        event.setCursor(new ItemStack(Material.AIR));
        event.setCurrentItem(new ItemStack(Material.AIR));
        player.setItemOnCursor(new ItemStack(Material.AIR));
        if (event.getSlot() >= 0 && event.getSlot() < player.getInventory().getSize()) {
            player.getInventory().setItem(event.getSlot(), null);
        }
        com.gildedmc.core.SchedulerCompat.run(this.plugin, () -> {
            player.setItemOnCursor(new ItemStack(Material.AIR));
            player.updateInventory();
        });
        this.warn(player, "spawning", this.isRealItem(cursor) ? cursor : current);
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onCreativeDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE || this.allowed(player)) {
            return;
        }
        ItemStack item = event.getItemDrop().getItemStack();
        event.setCancelled(true);
        event.getItemDrop().remove();
        this.warn(player, "dropping", item);
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onCreativePlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE || this.allowed(player)) {
            return;
        }
        event.setCancelled(true);
        this.warn(player, "placing", event.getItemInHand());
    }

    private boolean allowed(Player player) {
        String permission = this.plugin.getConfig().getString("creative-guard.bypass-permission", "gildedcreativeguard.bypass");
        if (permission != null && !permission.isBlank() && player.hasPermission(permission)) {
            return true;
        }
        for (String name : this.plugin.getConfig().getStringList("creative-guard.owner-names")) {
            if (!player.getName().equalsIgnoreCase(name)) continue;
            return true;
        }
        return false;
    }

    private boolean isRealItem(ItemStack item) {
        return item != null && item.getType() != Material.AIR && item.getAmount() > 0;
    }

    private void warn(Player player, String action, ItemStack item) {
        player.sendMessage(this.color(this.plugin.getConfig().getString("creative-guard.messages.blocked", "&cCreative item spawning is owner-only.")));
        String itemName = item == null ? "unknown" : item.getType().name().toLowerCase(Locale.ROOT);
        String summaryLine = this.color("&#EEBB01&lGilded Core");
        String contentLine = this.color(this.plugin.getConfig().getString("creative-guard.messages.notify", "&fBlocked &e%player% &ffrom %action% creative item &e%item%&f.").replace("%player%", player.getName()).replace("%action%", action).replace("%item%", itemName));
        String permission = this.plugin.getConfig().getString("creative-guard.notify-permission", "gildedcreativeguard.notify");
        String border = this.color("&8&m----------------------------------------");
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.hasPermission(permission)) continue;
            online.sendMessage(border);
            online.sendMessage(summaryLine);
            online.sendMessage(contentLine);
            online.sendMessage(border);
        }
        this.plugin.getLogger().warning(ChatColor.stripColor((String)contentLine));
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes((char)'&', (String)(message == null ? "" : message));
    }
}
