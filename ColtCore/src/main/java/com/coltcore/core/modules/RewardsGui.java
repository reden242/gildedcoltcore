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
        Inventory inv = UiKit.chest(SIZE, UiKit.gradient("DAILY REWARDS", "#ffcf4d", "#ff8a3d"));
        UiKit.framed(inv, Material.BLACK_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE);

        boolean dailyReady = dailyReady(player);
        int streak = rewards.getData().getInt(
                "players." + player.getUniqueId() + ".daily.streak", 0);
        ItemStack dailyItem = dailyReady
                ? UiKit.glow(UiKit.item(Material.CHEST, "&a&lDaily Ready",
                        "&7Streak: &f" + streak + " day" + (streak == 1 ? "" : "s"),
                        " ",
                        "&e" + UiKit.ARROW + " &e&l&nCLICK &r&eto claim",
                        "&8Resets daily - miss a day, lose the streak."))
                : UiKit.item(Material.ENDER_CHEST, "&cDaily Claimed",
                        "&7Streak: &f" + streak + " day" + (streak == 1 ? "" : "s"),
                        " ",
                        "&7Already claimed. Come back tomorrow!");
        inv.setItem(11, dailyItem);

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
        ItemStack playtimeItem;
        if (ready > 0) {
            playtimeItem = UiKit.glow(UiKit.item(Material.DIAMOND, "&a&l" + ready + " Playtime Ready",
                    "&7Played: &f" + hours + "h",
                    " ",
                    "&e" + UiKit.ARROW + " &e&l&nCLICK &r&eto claim all"));
        } else if (nextAt >= 0) {
            playtimeItem = UiKit.item(Material.CLOCK, "&eNext Playtime Reward",
                    UiKit.progressBar((double) hours / nextAt, 16, "&e", "&8",
                            hours + "h", nextAt + "h"),
                    " ",
                    "&7Keep playing to unlock it.");
        } else {
            playtimeItem = UiKit.item(Material.BARRIER, "&7Playtime Complete",
                    "&7Played: &f" + hours + "h",
                    " ",
                    "&7Every playtime reward claimed. Nice.");
        }
        inv.setItem(13, playtimeItem);

        boolean anythingReady = dailyReady || ready > 0;
        ItemStack claimItem = anythingReady
                ? UiKit.glow(UiKit.item(Material.EMERALD_BLOCK, "&a&lClaim All",
                        "&7Daily + playtime in one click.",
                        " ",
                        "&e" + UiKit.ARROW + " &e&l&nCLICK &r&eto claim everything ready."))
                : UiKit.item(Material.GRAY_CONCRETE, "&7Nothing Ready",
                        "&7Come back later.");
        inv.setItem(15, claimItem);

        UiKit.checkerFill(inv, Material.BLACK_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE);
        inv.setItem(26, UiKit.close());

        player.openInventory(inv);
        UiKit.sound(player, org.bukkit.Sound.BLOCK_CHEST_OPEN, 1.2F);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null) return;
        // The title carries colour codes - compare stripped. The old check
        // compared against the uncoloured string and never matched, so no
        // button in this menu ever did anything.
        if (!ChatColor.stripColor(event.getView().getTitle()).equals("DAILY REWARDS")) return;

        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        if (clicked.getItemMeta() == null
                || clicked.getItemMeta().getDisplayName() == null) return;

        String displayName = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());

        if (displayName.contains("Daily Ready")) {
            UiKit.sound(player, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0F);
            rewards.command(player, new String[]{"daily"});
            player.closeInventory();
            openGui(player);
            return;
        }

        if (displayName.contains("Playtime Ready") || displayName.contains("Claim All")) {
            UiKit.sound(player, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0F);
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
}
