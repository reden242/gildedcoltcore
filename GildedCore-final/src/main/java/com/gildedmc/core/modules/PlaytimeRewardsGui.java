package com.gildedmc.core.modules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.gildedmc.core.modules.RewardsModule.Reward;

/**
 * Playtime claim GUI: all 41 milestones on one page.
 *
 * <pre>
 *   Row 0: border, info at slot 4 (total active hours + next milestone)
 *   Milestone i (0 = hour 1 ... 40 = hour 200) at slot 9 + i (9-49)
 *   Slots 50-52: border. Slot 53: Claim All.
 * </pre>
 *
 * <p>States: claimed = green pane + "Claimed"; reached unclaimed = reward
 * icon with glow + "Click to claim"; not reached = gray pane + "Reach X
 * (Y left)". Hours 100/150/195 use a distinct Prime icon, hour 200 a distinct
 * finale icon.
 */
public final class PlaytimeRewardsGui implements Listener {

    private static final int SIZE = 54;
    private static final String TITLE = UiKit.titleGradient("Playtime Rewards");

    private static final Material EDGE = UiKit.FRAME_EDGE;
    private static final int INFO_SLOT = 4;
    private static final int CLAIM_ALL_SLOT = 53;

    private static final java.util.Set<Integer> PRIME_HOURS =
            java.util.Set.of(100, 150, 195);
    private static final int FINAL_HOUR = 200;

    private final RewardsModule rewards;

    public PlaytimeRewardsGui(RewardsModule rewards) {
        this.rewards = rewards;
    }

    public static final class Holder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public void openGui(Player player) {
        Inventory inv = UiKit.chest(6, TITLE, new Holder());
        render(player, inv);
        player.openInventory(inv);
    }

    private boolean isOurs(Inventory inv) {
        return inv != null && inv.getHolder() instanceof Holder;
    }

    /**
     * The 41 milestones deliberately occupy slots 9-49, which crosses the
     * ring on both sides and along the bottom - one page beats a perfect
     * frame. The house frame is applied last so the top row, the surviving
     * ring slots and the corner accents still read as /gildedcore.
     */
    private void render(Player player, Inventory inv) {
        inv.setItem(INFO_SLOT, infoItem(player));
        renderMilestones(player, inv);
        inv.setItem(CLAIM_ALL_SLOT, claimAllButton(player));
        UiKit.framed(inv, UiKit.FRAME_EDGE, UiKit.FRAME_CORNER);
        player.updateInventory();
    }

    private void renderMilestones(Player player, Inventory inv) {
        List<Integer> hours = this.rewards.playtimeMilestoneHours();
        long haveHours = this.rewards.activeHours(player);
        boolean completed = this.rewards.playtimeCompleted(player);
        for (int i = 0; i < hours.size() && i < 41; i++) {
            int hour = hours.get(i);
            int slot = 9 + i;
            Reward reward = this.rewards.playtimeRewardForHour(hour);
            if (reward == null) {
                inv.setItem(slot, lockedMilestone(hour, haveHours));
                continue;
            }
            boolean claimed = completed || this.rewards.playtimeHourClaimed(player, hour);
            if (claimed) {
                inv.setItem(slot, claimedMilestone(hour, reward));
            } else if (haveHours >= hour) {
                inv.setItem(slot, readyMilestone(hour, reward));
            } else {
                inv.setItem(slot, lockedMilestone(hour, haveHours));
            }
        }
    }

    private ItemStack claimedMilestone(int hour, Reward reward) {
        ItemStack icon = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        name(icon, ChatColor.GREEN + "" + ChatColor.BOLD + "Hour " + hour);
        List<String> lore = contentsLore(reward);
        lore.add("");
        lore.add(ChatColor.GREEN + "Claimed");
        lore(lore, icon);
        return icon;
    }

    private ItemStack readyMilestone(int hour, Reward reward) {
        ItemStack icon = milestoneIcon(hour, reward);
        name(icon, ChatColor.GREEN + "" + ChatColor.BOLD + "Hour " + hour);
        List<String> lore = contentsLore(reward);
        String tag = hourTag(hour);
        if (tag != null) {
            lore.add(0, tag);
        }
        lore.add("");
        lore.add(ChatColor.GREEN + "" + ChatColor.BOLD + "Click to claim");
        lore(lore, icon);
        glow(icon);
        return icon;
    }

    private ItemStack lockedMilestone(int hour, long haveHours) {
        ItemStack icon = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        name(icon, ChatColor.GRAY + "" + ChatColor.BOLD + "Hour " + hour);
        List<String> lore = new ArrayList<>();
        String tag = hourTag(hour);
        if (tag != null) {
            lore.add(ChatColor.GRAY + ChatColor.stripColor(tag));
        }
        Reward reward = this.rewards.playtimeRewardForHour(hour);
        if (reward != null) {
            for (String line : contentsLore(reward)) {
                lore.add(ChatColor.GRAY + ChatColor.stripColor(line));
            }
        }
        long left = Math.max(0L, hour - haveHours);
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Reach " + hour + " online hours"
                + " (" + left + "h left)");
        lore(lore, icon);
        return icon;
    }

    /** Rare/finale markers. Null for ordinary milestones. */
    private static String hourTag(int hour) {
        if (hour == FINAL_HOUR) {
            return ChatColor.GOLD + "" + ChatColor.BOLD + "FINAL REWARD";
        }
        if (PRIME_HOURS.contains(hour)) {
            return ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "RARE";
        }
        return null;
    }

    private ItemStack milestoneIcon(int hour, Reward reward) {
        if (hour == FINAL_HOUR) {
            return new ItemStack(Material.DRAGON_EGG);
        }
        if (PRIME_HOURS.contains(hour)) {
            return new ItemStack(Material.END_CRYSTAL);
        }
        return derivedIcon(reward);
    }

    private ItemStack infoItem(Player player) {
        long haveHours = this.rewards.activeHours(player);
        List<Integer> hours = this.rewards.playtimeMilestoneHours();
        int next = -1;
        for (int hour : hours) {
            if (!this.rewards.playtimeHourClaimed(player, hour) && haveHours < hour) {
                next = hour;
                break;
            }
        }
        // Next unclaimed regardless of reachability (may already be reachable).
        if (next < 0) {
            for (int hour : hours) {
                if (!this.rewards.playtimeHourClaimed(player, hour)) {
                    next = hour;
                    break;
                }
            }
        }
        ItemStack info = new ItemStack(Material.NETHER_STAR);
        List<String> lore = new ArrayList<>();
        if (this.rewards.playtimeCompleted(player)) {
            name(info, ChatColor.GOLD + "" + ChatColor.BOLD + "Playtime Completed");
            lore.add(ChatColor.GRAY + "All 41 milestones claimed!");
        } else {
            name(info, ChatColor.AQUA + "" + ChatColor.BOLD + "Total: "
                    + RewardsModule.formatMinutes(haveHours * 60L));
            if (next > 0) {
                lore.add(ChatColor.GRAY + "Next milestone: " + ChatColor.WHITE + "Hour " + next);
                long left = Math.max(0L, next - haveHours);
                lore.add(ChatColor.GRAY + "Progress: " + ChatColor.WHITE
                        + RewardsModule.formatMinutes(haveHours * 60L)
                        + ChatColor.GRAY + " / " + ChatColor.WHITE
                        + RewardsModule.formatMinutes((long) next * 60L)
                        + (left > 0 ? ChatColor.GRAY + " (" + left + "h left)" : ""));
            } else {
                lore.add(ChatColor.GRAY + "No milestones left.");
            }
        }
        lore(lore, info);
        return info;
    }

    private ItemStack claimAllButton(Player player) {
        List<Integer> hours = this.rewards.playtimeMilestoneHours();
        long haveHours = this.rewards.activeHours(player);
        int ready = 0;
        for (int hour : hours) {
            if (!this.rewards.playtimeHourClaimed(player, hour) && haveHours >= hour) {
                ready++;
            }
        }
        if (this.rewards.playtimeCompleted(player)) {
            ItemStack done = named(Material.BARRIER, ChatColor.GOLD + "" + ChatColor.BOLD + "Completed");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "All 41 milestones claimed!");
            lore(lore, done);
            return done;
        }
        if (ready > 0) {
            ItemStack claim = named(Material.LIME_CONCRETE,
                    ChatColor.GREEN + "" + ChatColor.BOLD + "CLAIM ALL (" + ready + ")");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Collect every reached milestone, in order");
            lore.add("");
            lore.add(ChatColor.GREEN + "" + ChatColor.BOLD + "Click to collect!");
            lore(lore, claim);
            glow(claim);
            return claim;
        }
        ItemStack idle = named(Material.GRAY_CONCRETE, ChatColor.GRAY + "" + ChatColor.BOLD + "CLAIM ALL");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "Nothing ready right now.");
        lore(lore, idle);
        return idle;
    }

    private List<String> contentsLore(Reward reward) {
        List<String> out = new ArrayList<>();
        for (String command : reward.commands()) {
            out.add(ChatColor.GRAY + humanize(command));
        }
        return out;
    }

    private ItemStack derivedIcon(Reward reward) {
        for (String command : reward.commands()) {
            String c = command.toLowerCase(Locale.ROOT);
            if (c.startsWith("eco give")) return new ItemStack(Material.GOLD_INGOT);
            if (c.startsWith("crate give")) return new ItemStack(Material.CHEST);
            if (c.startsWith("shard give")) return new ItemStack(Material.AMETHYST_SHARD);
            if (c.startsWith("give ")) {
                String[] parts = command.split(" ");
                if (parts.length >= 3) {
                    Material mat = Material.matchMaterial(parts[2].toUpperCase(Locale.ROOT));
                    if (mat != null && !mat.isAir()) return new ItemStack(mat);
                }
            }
        }
        return new ItemStack(Material.CHEST);
    }

    private String humanize(String command) {
        try {
            String c = command.trim();
            String lower = c.toLowerCase(Locale.ROOT);
            String[] parts = c.split(" ");
            if (lower.startsWith("eco give") && parts.length >= 4) {
                return "$" + parts[3];
            }
            if ((lower.startsWith("crate give") || lower.startsWith("shard give")) && parts.length >= 4) {
                String what = parts[2];
                String amount = parts.length >= 5 ? parts[4] : parts[3];
                if (lower.startsWith("crate")) {
                    return amount + "x " + capitalize(what) + " Key";
                }
                return amount + "x " + capitalize(what);
            }
            if (lower.startsWith("give ") && parts.length >= 4) {
                return parts[3] + "x " + capitalize(parts[2]);
            }
        } catch (Exception e) {
            // fall through
        }
        return command.replace("%player%", "").trim();
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        String clean = s.replace('_', ' ').toLowerCase(Locale.ROOT).trim();
        if (clean.isEmpty()) return s;
        return Character.toUpperCase(clean.charAt(0)) + clean.substring(1);
    }

    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isOurs(event.getInventory())) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getInventory())) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;
        if (slot == CLAIM_ALL_SLOT) {
            int paid = this.rewards.claimAllPlaytime(player);
            if (paid > 0) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
                refresh(player);
            } else {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
            }
            return;
        }
        if (slot >= 9 && slot <= 49) {
            int idx = slot - 9;
            List<Integer> hours = this.rewards.playtimeMilestoneHours();
            if (idx < 0 || idx >= hours.size()) return;
            int hour = hours.get(idx);
            if (this.rewards.playtimeHourClaimed(player, hour)
                    || this.rewards.playtimeCompleted(player)) {
                return;
            }
            if (!this.rewards.playtimeHourReady(player, hour)) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
                return;
            }
            if (this.rewards.claimPlaytimeHour(player, hour)) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
                refresh(player);
            } else {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
            }
        }
    }

    private void refresh(Player player) {
        Inventory top = player.getOpenInventory() != null
                ? player.getOpenInventory().getTopInventory() : null;
        if (isOurs(top)) {
            render(player, top);
        } else {
            openGui(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (isOurs(event.getInventory())) {
            event.setCancelled(true);
        }
    }

    /* ------------------------------------------------------------------ */

    private static ItemStack named(Material mat, String name) {
        ItemStack stack = new ItemStack(mat);
        name(stack, name);
        return stack;
    }

    private static void name(ItemStack stack, String name) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        meta.setDisplayName(UiKit.colour(name));
        stack.setItemMeta(meta);
    }

    private static void lore(List<String> lore, ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        List<String> out = new ArrayList<>();
        for (String line : lore) out.add(UiKit.colour(line));
        meta.setLore(out);
        stack.setItemMeta(meta);
    }

    private static void glow(ItemStack stack) {
        UiKit.glow(stack);
    }
}