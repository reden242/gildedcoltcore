package com.gildedmc.core.modules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.gildedmc.core.modules.RewardsModule.Reward;

/**
 * Playtime claim GUI, paginated.
 *
 * <pre>
 *   row 0        frame, info star at slot 4
 *   rows 1-3     18 milestone slots: 10-15, 19-24, 28-33  (3 pages for 41)
 *   row 4        page indicator (40) alone, as a separator
 *   row 5        legend (45), prev (47), CLAIM ALL (49), next (51), stats (53)
 * </pre>
 *
 * <p>Milestones come from the configured ladder, so the page count follows the
 * config rather than a hardcoded 41 - a ladder with fewer or more tiers still
 * pages correctly.
 *
 * <p>Icons are dyes: LIME claimed, ORANGE ready, GRAY locked, LIGHT_BLUE for
 * prime, PURPLE for the 200-hour finale.
 */
public final class PlaytimeRewardsGui implements Listener {

    private static final int SIZE = 54;
    private static final String TITLE = UiKit.titleGradient("Playtime Rewards");

    private static final int INFO_SLOT = 4;
    private static final int PREV_SLOT = 47;
    private static final int PAGE_SLOT = 40;
    private static final int NEXT_SLOT = 51;
    private static final int LEGEND_SLOT = 45;
    private static final int CLAIM_ALL_SLOT = 49;
    private static final int STATS_SLOT = 53;

    private static final int[] TIER_SLOTS = {
            10, 11, 12, 13, 14, 15,
            19, 20, 21, 22, 23, 24,
            28, 29, 30, 31, 32, 33,
    };
    private static final int PER_PAGE = TIER_SLOTS.length;

    private static final List<Integer> PRIME_HOURS = List.of(100, 150, 195);
    private static final int FINAL_HOUR = 200;

    private final RewardsModule rewards;

    public PlaytimeRewardsGui(RewardsModule rewards) {
        this.rewards = rewards;
    }

    public static final class Holder implements InventoryHolder {
        private final int page;

        public Holder(int page) {
            this.page = page;
        }

        public int page() {
            return this.page;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private int pageCount() {
        List<Integer> hours = this.rewards.playtimeMilestoneHours();
        return Math.max(1, (hours.size() + PER_PAGE - 1) / PER_PAGE);
    }

    public void openGui(Player player) {
        openPage(player, 0);
    }

    private void openPage(Player player, int page) {
        int pages = pageCount();
        int safe = Math.max(0, Math.min(page, pages - 1));
        Inventory inv = UiKit.chest(6, TITLE, new Holder(safe));
        render(player, inv, safe, pages);
        player.openInventory(inv);
    }

    private boolean holderOf(Inventory inv) {
        return inv != null && inv.getHolder() instanceof Holder;
    }

    private void render(Player player, Inventory inv, int page, int pages) {
        renderMilestones(player, inv, page);
        inv.setItem(INFO_SLOT, infoItem(player));
        renderNav(inv, page, pages);
        inv.setItem(CLAIM_ALL_SLOT, claimAllButton(player));
        inv.setItem(LEGEND_SLOT, legend());
        inv.setItem(STATS_SLOT, statsItem(player));
        UiKit.framed(inv, UiKit.FRAME_EDGE, UiKit.FRAME_CORNER);
        player.updateInventory();
    }

    private void renderMilestones(Player player, Inventory inv, int page) {
        List<Integer> hours = this.rewards.playtimeMilestoneHours();
        long have = this.rewards.activeHours(player);
        boolean completed = this.rewards.playtimeCompleted(player);
        int first = page * PER_PAGE;

        for (int i = 0; i < PER_PAGE; i++) {
            int index = first + i;
            if (index >= hours.size()) {
                inv.setItem(TIER_SLOTS[i], null);
                continue;
            }
            int hour = hours.get(index);
            Reward reward = this.rewards.playtimeRewardForHour(hour);
            boolean claimed = completed || this.rewards.playtimeHourClaimed(player, hour);
            if (reward == null) {
                inv.setItem(TIER_SLOTS[i], lockedMilestone(hour, have));
            } else if (claimed) {
                inv.setItem(TIER_SLOTS[i], claimedMilestone(hour, reward));
            } else if (have >= hour) {
                inv.setItem(TIER_SLOTS[i], readyMilestone(hour, reward));
            } else {
                inv.setItem(TIER_SLOTS[i], lockedMilestone(hour, have));
            }
        }
    }

    private void renderNav(Inventory inv, int page, int pages) {
        if (page > 0) {
            inv.setItem(PREV_SLOT, named(Material.ARROW,
                    ChatColor.YELLOW + "" + ChatColor.BOLD + "Previous page",
                    ChatColor.GRAY + "Page " + page + " of " + pages));
        }
        if (page < pages - 1) {
            inv.setItem(NEXT_SLOT, named(Material.ARROW,
                    ChatColor.YELLOW + "" + ChatColor.BOLD + "Next page",
                    ChatColor.GRAY + "Page " + (page + 2) + " of " + pages));
        }
        inv.setItem(PAGE_SLOT, named(Material.PAPER,
                ChatColor.AQUA + "" + ChatColor.BOLD + "Page " + (page + 1) + " of " + pages,
                ChatColor.GRAY + "Click a milestone to claim it on its own."));
    }

    private ItemStack claimedMilestone(int hour, Reward reward) {
        ItemStack icon = new ItemStack(Material.LIME_DYE);
        named(icon, ChatColor.GREEN + "" + ChatColor.BOLD + "Hour " + hour);
        List<String> lore = contentsLore(reward);
        lore.add("");
        lore.add(ChatColor.GREEN + "" + ChatColor.BOLD + "Claimed");
        lore(icon, lore);
        return icon;
    }

    private ItemStack readyMilestone(int hour, Reward reward) {
        ItemStack icon = milestoneIcon(hour, reward);
        named(icon, ChatColor.GREEN + "" + ChatColor.BOLD + "Hour " + hour);
        List<String> lore = contentsLore(reward);
        String tag = hourTag(hour);
        if (tag != null) lore.add(0, tag);
        lore.add("");
        lore.add(ChatColor.GREEN + "" + ChatColor.BOLD + "Click to claim");
        lore(icon, lore);
        UiKit.glow(icon);
        return icon;
    }

    private ItemStack lockedMilestone(int hour, long have) {
        ItemStack icon = new ItemStack(Material.GRAY_DYE);
        named(icon, ChatColor.GRAY + "" + ChatColor.BOLD + "Hour " + hour);
        List<String> lore = new ArrayList<>();
        String tag = hourTag(hour);
        if (tag != null) lore.add(ChatColor.GRAY + ChatColor.stripColor(tag));
        Reward reward = this.rewards.playtimeRewardForHour(hour);
        if (reward != null) {
            for (String line : contentsLore(reward)) {
                lore.add(ChatColor.GRAY + ChatColor.stripColor(line));
            }
        }
        long left = Math.max(0L, hour - have);
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Reach " + hour + " active hours"
                + " (" + left + "h left)");
        lore(icon, lore);
        return icon;
    }

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
        if (hour == FINAL_HOUR) return new ItemStack(Material.PURPLE_DYE);
        if (PRIME_HOURS.contains(hour)) return new ItemStack(Material.LIGHT_BLUE_DYE);
        return derivedIcon(reward);
    }

    private ItemStack infoItem(Player player) {
        long have = this.rewards.activeHours(player);
        List<Integer> hours = this.rewards.playtimeMilestoneHours();
        int next = -1;
        for (int hour : hours) {
            if (!this.rewards.playtimeHourClaimed(player, hour)) {
                next = hour;
                break;
            }
        }
        ItemStack info = new ItemStack(Material.NETHER_STAR);
        List<String> lore = new ArrayList<>();
        if (this.rewards.playtimeCompleted(player)) {
            named(info, ChatColor.GOLD + "" + ChatColor.BOLD + "Playtime Completed");
            lore.add(ChatColor.GRAY + "All " + hours.size() + " milestones claimed!");
        } else {
            named(info, ChatColor.AQUA + "" + ChatColor.BOLD + "Total: "
                    + RewardsModule.formatMinutes(have * 60L));
            if (next > 0) {
                long left = Math.max(0L, next - have);
                lore.add(ChatColor.GRAY + "Next milestone: " + ChatColor.WHITE + "Hour " + next);
                lore.add(ChatColor.GRAY + "Progress: " + ChatColor.WHITE
                        + RewardsModule.formatMinutes(have * 60L)
                        + ChatColor.GRAY + " / " + ChatColor.WHITE
                        + RewardsModule.formatMinutes((long) next * 60L)
                        + (left > 0 ? ChatColor.GRAY + " (" + left + "h left)" : ""));
            } else {
                lore.add(ChatColor.GRAY + "No milestones left.");
            }
        }
        lore(info, lore);
        return info;
    }

    private ItemStack claimAllButton(Player player) {
        List<Integer> hours = this.rewards.playtimeMilestoneHours();
        long have = this.rewards.activeHours(player);
        int ready = 0;
        for (int hour : hours) {
            if (!this.rewards.playtimeHourClaimed(player, hour) && have >= hour) ready++;
        }
        if (this.rewards.playtimeCompleted(player)) {
            return named(Material.BARRIER,
                    ChatColor.GOLD + "" + ChatColor.BOLD + "COMPLETED",
                    ChatColor.GRAY + "All " + hours.size() + " milestones claimed!");
        }
        if (ready > 0) {
            return named(Material.ORANGE_DYE,
                    ChatColor.GREEN + "" + ChatColor.BOLD + "CLAIM ALL (" + ready + ")",
                    ChatColor.GRAY + "Collect every reached milestone, in order.",
                    "",
                    ChatColor.GREEN + "" + ChatColor.BOLD + "Click to collect!");
        }
        return named(Material.GRAY_DYE,
                ChatColor.GRAY + "" + ChatColor.BOLD + "NOTHING READY",
                ChatColor.DARK_GRAY + "Keep playing to reach the next milestone.");
    }

    private ItemStack legend() {
        return named(Material.BOOK,
                ChatColor.AQUA + "" + ChatColor.BOLD + "How this works",
                ChatColor.GRAY + "Milestones unlock at set playtime hours.",
                ChatColor.GRAY + "This ladder never resets.",
                "",
                ChatColor.GREEN + "Lime" + ChatColor.GRAY + " = claimed",
                ChatColor.YELLOW + "Orange" + ChatColor.GRAY + " = ready, click it",
                ChatColor.GRAY + "Grey" + ChatColor.GRAY + " = not reached yet",
                ChatColor.LIGHT_PURPLE + "Blue" + ChatColor.GRAY + " = rare",
                ChatColor.GOLD + "Purple" + ChatColor.GRAY + " = final reward");
    }

    private ItemStack statsItem(Player player) {
        return named(Material.CLOCK,
                ChatColor.AQUA + "" + ChatColor.BOLD + "Your progress",
                ChatColor.GRAY + "Active hours: " + ChatColor.WHITE
                        + this.rewards.activeHours(player),
                ChatColor.GRAY + "Milestones: " + ChatColor.WHITE
                        + this.rewards.playtimeClaimedHours(player).size()
                        + ChatColor.GRAY + " / " + this.rewards.playtimeMilestoneHours().size()
                        + " claimed");
    }

    private List<String> contentsLore(Reward reward) {
        List<String> out = new ArrayList<>();
        for (String command : reward.commands()) {
            out.add(ChatColor.GRAY + UiKit.rewardLine(command));
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

    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!holderOf(event.getInventory())) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getInventory())) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;

        Holder holder = (Holder) event.getInventory().getHolder();

        if (slot == CLAIM_ALL_SLOT) {
            int paid = this.rewards.claimAllPlaytime(player);
            if (paid > 0) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
                refresh(player, holder.page());
            } else {
                deny(player);
            }
            return;
        }
        if (slot == PREV_SLOT) {
            openPage(player, holder.page() - 1);
            return;
        }
        if (slot == NEXT_SLOT) {
            openPage(player, holder.page() + 1);
            return;
        }
        for (int i = 0; i < PER_PAGE; i++) {
            if (TIER_SLOTS[i] != slot) continue;
            List<Integer> hours = this.rewards.playtimeMilestoneHours();
            int index = holder.page() * PER_PAGE + i;
            if (index >= hours.size()) return;
            int hour = hours.get(index);
            if (this.rewards.playtimeCompleted(player)
                    || this.rewards.playtimeHourClaimed(player, hour)
                    || !this.rewards.playtimeHourReady(player, hour)) {
                deny(player);
                return;
            }
            if (this.rewards.claimPlaytimeHour(player, hour)) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
                refresh(player, holder.page());
            } else {
                deny(player);
            }
            return;
        }
    }

    private void deny(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
    }

    private void refresh(Player player, int page) {
        Inventory top = player.getOpenInventory() != null
                ? player.getOpenInventory().getTopInventory() : null;
        if (holderOf(top)) {
            render(player, top, page, pageCount());
        } else {
            openPage(player, page);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (holderOf(event.getInventory())) event.setCancelled(true);
    }

    /* ------------------------------------------------------------------ */

    private static ItemStack named(Material mat, String name, String... lore) {
        ItemStack stack = new ItemStack(mat);
        named(stack, name);
        List<String> l = new ArrayList<>();
        for (String s : lore) l.add(s);
        lore(stack, l);
        return stack;
    }

    private static void named(ItemStack stack, String name) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        meta.setDisplayName(UiKit.colour(name));
        stack.setItemMeta(meta);
    }

    private static void lore(ItemStack stack, List<String> lore) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        List<String> out = new ArrayList<>();
        for (String line : lore) out.add(UiKit.colour(line));
        meta.setLore(out);
        stack.setItemMeta(meta);
    }
}
