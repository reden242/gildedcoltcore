package com.gildedmc.core.modules;

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
import java.util.ArrayList;
import java.util.List;

/**
 * Rewards click GUI — two options in a three-row chest: daily streak on the
 * left, hourly playtime on the right. Left-click claims that category.
 */
public final class RewardsGui implements Listener {

    private static final int SIZE = 27;
    private static final String TITLE = "REWARDS";
    private static final int SLOT_DAILY = 11;
    private static final int SLOT_HOURLY = 15;

    private final RewardsModule rewards;

    public RewardsGui(RewardsModule rewards) {
        this.rewards = rewards;
    }

    public void enable() {}

    public void disable() {}

    /**
     * Opens the rewards menu for the player.
     */
    public void openGui(Player player) {
        Inventory inv = UiKit.chest(SIZE / 9, UiKit.gradient(TITLE, "#ffcf4d", "#ff8a3d"));
        UiKit.framed(inv, Material.BLACK_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE);

        boolean dailyReady = dailyReady(player);
        int streak = rewards.getData().getInt(
                "players." + player.getUniqueId() + ".daily.streak", 0);
        int showingDay = dailyReady ? streak + 1 : Math.max(1, streak);
        RewardsModule.Reward today = rewards.dailyRewardFor(showingDay);
        List<String> dailyLore = new ArrayList<>();
        dailyLore.add("&7Streak: &f" + streak + " day" + (streak == 1 ? "" : "s"));
        if (today != null) {
            dailyLore.add("&7Day " + showingDay + ": &f" + today.id().replace('-', ' '));
        }
        dailyLore.add(" ");
        if (dailyReady) {
            dailyLore.add("&e" + UiKit.ARROW + " &e&l&nCLICK &r&eto claim");
            dailyLore.add("&8Resets daily - miss a day, lose the streak.");
            inv.setItem(SLOT_DAILY, UiKit.glow(UiKit.item(Material.CHEST,
                    "&a&lDaily Reward", dailyLore)));
        } else {
            dailyLore.add("&7Already claimed. Come back tomorrow!");
            inv.setItem(SLOT_DAILY, UiKit.item(Material.ENDER_CHEST,
                    "&cDaily Claimed", dailyLore));
        }

        long hours = player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) / (20L * 60L * 60L);
        int ready = 0;
        long nextAt = -1L;
        for (var reward : rewards.playtimeRewards()) {
            boolean claimed = rewards.getData().getBoolean(
                    "players." + player.getUniqueId() + ".playtime.claimed." + reward.id(), false);
            if (claimed) continue;
            if (hours >= reward.threshold()) {
                ready++;
            } else if (nextAt < 0 || reward.threshold() < nextAt) {
                nextAt = reward.threshold();
            }
        }
        if (ready > 0) {
            inv.setItem(SLOT_HOURLY, UiKit.glow(UiKit.item(Material.DIAMOND,
                    "&a&l" + ready + " Hourly Ready",
                    "&7Played: &f" + hours + "h",
                    " ",
                    "&e" + UiKit.ARROW + " &e&l&nCLICK &r&eto claim all")));
        } else if (nextAt >= 0) {
            inv.setItem(SLOT_HOURLY, UiKit.item(Material.CLOCK, "&eNext Hourly Reward",
                    UiKit.progressBar((double) hours / nextAt, 16, "&e", "&8",
                            hours + "h", nextAt + "h"),
                    " ",
                    "&7Keep playing to unlock it."));
        } else {
            inv.setItem(SLOT_HOURLY, UiKit.item(Material.BARRIER, "&7Hourly Complete",
                    "&7Played: &f" + hours + "h",
                    " ",
                    "&7Every hourly reward claimed. Nice."));
        }

        UiKit.checkerFill(inv, Material.BLACK_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE);
        inv.setItem(SIZE - 1, UiKit.close());

        player.openInventory(inv);
        UiKit.sound(player, org.bukkit.Sound.BLOCK_CHEST_OPEN, 1.2F);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null) return;
        if (!ChatColor.stripColor(event.getView().getTitle()).equals(TITLE)) return;

        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        if (clicked.getItemMeta() == null
                || clicked.getItemMeta().getDisplayName() == null) return;

        String displayName = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
        int slot = event.getSlot();

        if (slot == SLOT_DAILY && displayName.contains("Daily Reward")) {
            UiKit.sound(player, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0F);
            rewards.command(player, new String[]{"daily"});
            player.closeInventory();
            openGui(player);
            return;
        }

        if (slot == SLOT_HOURLY && displayName.contains("Hourly")) {
            UiKit.sound(player, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0F);
            rewards.command(player, new String[]{"playtime"});
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
}
