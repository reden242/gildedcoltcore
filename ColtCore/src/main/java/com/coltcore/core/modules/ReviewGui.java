package com.coltcore.core.modules;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Chest GUI over {@link FlagReviewStore}: every recent advertising flag with
 * who said it, what they said, and the model percentage behind the decision.
 *
 * <p>Left-click a head marks the flag <b>false positive</b> — the words are
 * fed back as confirmed-clean, so the Layer 3 word cache defends them next
 * time, and the entry leaves the queue. Right-click <b>confirms</b> it as a
 * real advert, teaching the cache the other way. Either decision is restricted
 * to executive and above; everyone else sees a locked menu.
 */
public final class ReviewGui implements Listener {

    private static final int SIZE = 54;
    private static final String TITLE = "FLAG REVIEW";

    private final JavaPlugin plugin;
    private final FlagReviewStore store;
    private final AntiAdPipeline antiAd;
    private final ChatGuardModule guard;
    private final Predicate<Player> adjudicator;

    /** Maps inventory slot to flag id for the open menu. */
    private final Map<Integer, Integer> slotToFlag = new HashMap<>();

    public ReviewGui(JavaPlugin plugin, FlagReviewStore store, AntiAdPipeline antiAd,
                     ChatGuardModule guard, Predicate<Player> adjudicator) {
        this.plugin = plugin;
        this.store = store;
        this.antiAd = antiAd;
        this.guard = guard;
        this.adjudicator = adjudicator;
    }

    public void enable() { }

    public void disable() { }

    /** Opens the queue. Rank gate lives here, not just on the command. */
    public void openGui(Player player) {
        if (!canAdjudicate(player)) {
            player.sendMessage(UiKit.colour("&cFlag review is executive and above."));
            UiKit.sound(player, org.bukkit.Sound.ENTITY_VILLAGER_NO, 1.0F);
            return;
        }
        Inventory inv = UiKit.chest(SIZE, UiKit.gradient(TITLE, "#ff5d5d", "#ffb04d"));
        UiKit.framed(inv, Material.BLACK_STAINED_GLASS_PANE, Material.RED_STAINED_GLASS_PANE);
        this.slotToFlag.clear();

        List<FlagReviewStore.Flag> flags = this.store.list();
        int slot = 10;
        for (FlagReviewStore.Flag flag : flags) {
            if (slot >= SIZE - 10) break;
            if ((slot + 1) % 9 == 0) slot += 2;
            if (slot >= SIZE - 9) break;
            inv.setItem(slot, flagItem(flag));
            this.slotToFlag.put(slot, flag.id());
            slot++;
        }
        if (flags.isEmpty()) {
            inv.setItem(22, UiKit.item(Material.LIME_WOOL, "&aQueue Clear",
                    "&7No flags awaiting review."));
        }
        UiKit.checkerFill(inv, Material.BLACK_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE);
        inv.setItem(SIZE - 1, UiKit.close());
        player.openInventory(inv);
        UiKit.sound(player, org.bukkit.Sound.BLOCK_CHEST_OPEN, 1.2F);
    }

    private ItemStack flagItem(FlagReviewStore.Flag flag) {
        OfflineHead head = new OfflineHead(flag.playerName());
        List<String> lore = new ArrayList<>();
        lore.add("&7Said: &f");
        lore.addAll(UiKit.wrap(flag.text(), 30));
        lore.add(" ");
        lore.add("&7Model: " + pctColour(flag.score()) + String.format("%.1f%%", flag.score() * 100.0D));
        lore.add("&7Reason: &f" + flag.reason());
        lore.add("&7Surface: &f" + flag.surface());
        lore.add("&7When: &f" + ago(flag.time()));
        lore.add(" ");
        lore.add("&a&lLEFT-CLICK &r&aFALSE POSITIVE");
        lore.add("&c&lRIGHT-CLICK &r&cCONFIRM ADVERT");
        return UiKit.head(head.player(), "&e" + flag.playerName() + " &8#" + flag.id(), lore);
    }

    private static String pctColour(double score) {
        if (score >= 0.85D) return "&c";
        if (score >= 0.60D) return "&e";
        return "&a";
    }

    private static String ago(long time) {
        long s = Math.max(0L, (System.currentTimeMillis() - time) / 1000L);
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        return (s / 3600) + "h ago";
    }

    private boolean canAdjudicate(Player player) {
        try {
            return player != null && this.adjudicator.test(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null) return;
        if (!ChatColor.stripColor(event.getView().getTitle()).equals(TITLE)) return;
        event.setCancelled(true);
        if (!canAdjudicate(player)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        if (clicked.getItemMeta() == null
                || clicked.getItemMeta().getDisplayName() == null) return;
        String name = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
        if (name.contains("Close")) {
            player.closeInventory();
            return;
        }
        Integer flagId = this.slotToFlag.get(event.getSlot());
        if (flagId == null) return;
        FlagReviewStore.Flag flag = this.store.remove(flagId);
        if (flag == null) {
            openGui(player);
            return;
        }

        String form = ChatGuardModule.advertForm(flag.text());
        boolean falsePositive = event.isLeftClick();
        try {
            if (falsePositive) {
                if (this.antiAd != null) this.antiAd.confirmClean(form);
                this.guard.localAi().learnClean(flag.text());
                player.sendMessage(UiKit.colour("&aFlag #" + flag.id()
                        + " marked FALSE POSITIVE - words fed back as clean."));
            } else {
                if (this.antiAd != null) this.antiAd.confirmAdvert(form);
                player.sendMessage(UiKit.colour("&cFlag #" + flag.id()
                        + " CONFIRMED as advertising."));
            }
            this.plugin.getLogger().info("[FlagReview] " + player.getName()
                    + (falsePositive ? " cleared (FP) #" : " confirmed #")
                    + flag.id() + " <" + flag.playerName() + ">: " + flag.text());
        } catch (Throwable ignored) {
            // Adjudication feedback must never break the menu.
        }
        UiKit.sound(player, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP,
                falsePositive ? 1.4F : 0.7F);
        openGui(player);
    }

    /** Head lookup that never throws the menu off when the profile is unknown. */
    private static final class OfflineHead {
        private final org.bukkit.OfflinePlayer player;

        OfflineHead(String name) {
            org.bukkit.OfflinePlayer found = null;
            try {
                found = Bukkit.getOfflinePlayer(name == null ? "?" : name);
            } catch (Throwable ignored) {
                found = null;
            }
            this.player = found;
        }

        org.bukkit.OfflinePlayer player() {
            return this.player;
        }
    }
}
