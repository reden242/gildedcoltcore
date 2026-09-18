package com.coltcore.core.modules;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Daily rewards chest GUI — a visual inventory menu for claiming
 * daily and playtime rewards.
 *
 * <p>Uses {@link UiKit} for consistent styling. The GUI shows:
 * daily reward status, playtime reward status, and a claim button.</p>
 */
public final class RewardsGui implements Listener {

    private static final int SIZE = 27;
    private final RewardsModule rewards;

    public RewardsGui(RewardsModule rewards) {
        this.rewards = rewards;
    }

    public void enable() {}

    public void disable() {}

    /**
     * Opens the daily rewards chest GUI for the player.
     */
    public void openGui(Player player) {
        Inventory inv = UiKit.chest(SIZE, "&6Daily Rewards");
        UiKit.border(inv, Material.BLACK_STAINED_GLASS_PANE);

        boolean dailyReady = dailyReady(player);
        int streak = rewards.getData().getInt(
                "players." + player.getUniqueId() + ".daily.streak", 0);
        ItemStack dailyItem = dailyReady
                ? UiKit.item(Material.CHEST, "&aDaily Ready",
                        "&7Day: &f" + streak,
                        "&eClick to claim",
                        "&7Rewards reset daily.")
                : UiKit.item(Material.BARRIER, "&cDaily Claimed",
                        "&7Day: &f" + streak,
                        "&7Already claimed today.",
                        "&7Come back tomorrow!");
        inv.setItem(10, dailyItem);

        long hours = player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) / (20L * 60L * 60L);
        int playtimeReady = playtimeReady(player, hours);
        ItemStack playtimeItem = playtimeReady > 0
                ? UiKit.item(Material.DIAMOND, "&a" + playtimeReady + " Playtime Ready",
                        "&7Hours: &f" + hours,
                        "&eClick to claim all",
                        "&7Playtime rewards unlock at thresholds.")
                : UiKit.item(Material.BARRIER, "&cNo Playtime Ready",
                        "&7Hours: &f" + hours,
                        "&7Keep playing!",
                        "&7Check /rewards status for details.");
        inv.setItem(12, playtimeItem);

        ItemStack claimItem = UiKit.item(Material.GOLD_NUGGET, "&e&lClaim All",
                "&7Daily + playtime rewards",
                "&ein one click!",
                "&aClick to claim everything ready.");
        inv.setItem(14, claimItem);

        ItemStack statusItem = UiKit.item(Material.PAPER, "&7Status",
                "&7Daily: " + (dailyReady ? "&aREADY" : "&cCLAIMED"),
                "&7Playtime: &f" + playtimeReady + " ready",
                "&7Hours: &f" + hours,
                "&e&lClick the items above to claim!");
        inv.setItem(16, statusItem);

        inv.setItem(26, UiKit.close());

        player.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null) return;
        if (!event.getView().getTitle().equals("Daily Rewards")) return;

        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;

        String displayName = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());

        if (displayName.contains("Daily Ready")) {
            rewards.command(player, new String[]{"daily"});
            player.closeInventory();
            openGui(player);
            return;
        }

        if (displayName.contains("Playtime Ready") || displayName.contains("Claim All")) {
            rewards.command(player, new String[]{"claimall"});
            player.closeInventory();
            openGui(player);
            return;
        }

        if (displayName.contains("Close")) {
            player.closeInventory();
        }
    }

    private boolean dailyReady(Player player) {
        String lastDate = rewards.getData().getString(
                "players." + player.getUniqueId() + ".daily.last-date", "");
        return !LocalDate.now().toString().equals(lastDate);
    }

    private int playtimeReady(Player player, long hours) {
        int count = 0;
        for (var reward : rewards.playtimeRewards()) {
            if (hours >= reward.threshold() && !rewards.getData().getBoolean(
                    "players." + player.getUniqueId() + ".playtime.claimed." + reward.id(), false)) {
                count++;
            }
        }
        return count;
    }
}
