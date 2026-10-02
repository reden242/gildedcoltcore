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
 * Daily claim GUI, paginated.
 *
 * <pre>
 *   row 0        frame, info star at slot 4
 *   rows 1-3     21 slots: 10-16, 19-25, 28-33   (2 pages either way)
 *   row 4        legend 37, prev 38, page 39, CLAIM 41, next 42, stats 43
 *   row 5        frame only - nothing is placed on the border
 * </pre>
 *
 * <p>Seven columns, not six. UiKit.framed only draws the outer ring
 * (c == 0 || c == 8), so column 7 is interior by the house definition - a
 * 6-wide grid leaves slots 16, 25 and 34 empty and unframed, which reads as a
 * hole in the middle of the menu rather than as margin.
 *
 * <p>18 per page rather than all 30 crammed into five rows: every tier's reward
 * is spelled out in its lore, and at 30 in one grid the text was unreadable.
 *
 * <p>State is read from the reward itself rather than the pane type, so an
 * unconfigured tier can never be drawn as "claimed" by accident. Icons are
 * dyes: LIME claimed, ORANGE ready, GRAY locked, PURPLE for the weekly
 * milestones, PURPLE for the finale.
 */
public final class DailyRewardsGui implements Listener {

    private static final int SIZE = 54;
    private static final String TITLE = UiKit.titleGradient("Daily Rewards");

    private static final int INFO_SLOT = 4;
    private static final int PREV_SLOT = 38;
    private static final int PAGE_SLOT = 39;
    private static final int NEXT_SLOT = 42;
    private static final int LEGEND_SLOT = 37;
    private static final int CLAIM_SLOT = 41;
    private static final int STATS_SLOT = 43;

    /** Rows 1-3, columns 1-6. */
    private static final int[] DAY_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
    };
    private static final int PER_PAGE = DAY_SLOTS.length;

    private static final List<Integer> MILESTONE_DAYS = List.of(7, 14, 21, 28, 30);
    private static final int TOTAL_DAYS = 30;

    private final RewardsModule rewards;

    public DailyRewardsGui(RewardsModule rewards) {
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
        int days = this.rewards.dailyRewards().size();
        if (days < 1) days = TOTAL_DAYS;
        return Math.max(1, (days + PER_PAGE - 1) / PER_PAGE);
    }
    public void openGui(Player player) {
        openPage(player, 0);
    }

    private void openPage(Player player, int page) {
        this.rewards.checkDailyReset(player);
        this.rewards.qualifyDaily(player);
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
        renderDays(player, inv, page);
        inv.setItem(INFO_SLOT, infoItem(player));
        renderNav(inv, page, pages);
        inv.setItem(CLAIM_SLOT, claimButton(player));
        inv.setItem(LEGEND_SLOT, legend());
        inv.setItem(STATS_SLOT, statsItem(player));
        // Frame last: it only fills ring slots that are still empty, so the
        // page indicator and claim button keep their places.
        UiKit.framed(inv, UiKit.FRAME_EDGE, UiKit.FRAME_CORNER);
        player.updateInventory();
    }

    private void renderDays(Player player, Inventory inv, int page) {
        int next = this.rewards.dailyNextDay(player);
        int pending = this.rewards.dailyPending(player);
        boolean completed = this.rewards.dailyCompleted(player);
        List<Reward> days = this.rewards.dailyRewards();
        int total = days.isEmpty() ? TOTAL_DAYS : days.size();
        int first = page * PER_PAGE;

        for (int i = 0; i < PER_PAGE; i++) {
            int day = first + i + 1;
            if (day > total) {
                inv.setItem(DAY_SLOTS[i], null);
                continue;
            }
            Reward reward = this.rewards.dailyRewardForDay(day);
            boolean claimed = completed || day < next;
            boolean ready = !completed && day == next && pending > 0;
            if (claimed) {
                inv.setItem(DAY_SLOTS[i], claimedDay(day, reward));
            } else if (ready) {
                inv.setItem(DAY_SLOTS[i], readyDay(day, reward));
            } else {
                inv.setItem(DAY_SLOTS[i], lockedDay(day, reward));
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
                ChatColor.GRAY + "Days " + (page * PER_PAGE + 1) + "-" + Math.min(TOTAL_DAYS, (page + 1) * PER_PAGE)));
    }

    private ItemStack infoItem(Player player) {
        int next = this.rewards.dailyNextDay(player);
        int pending = this.rewards.dailyPending(player);
        int safe = this.rewards.dailySafeDaysLeft(player);
        long needMin = Math.max(1L, this.rewards.dailyQualifySeconds() / 60L);
        long onlineMin = this.rewards.activeToday(player.getUniqueId()) / 60L;
        ItemStack info = new ItemStack(Material.NETHER_STAR);
        List<String> lore = new ArrayList<>();
        if (this.rewards.dailyCompleted(player)) {
            named(info, ChatColor.GOLD + "" + ChatColor.BOLD + "Daily Rewards Completed");
            lore.add(ChatColor.GRAY + "All " + TOTAL_DAYS + " days claimed!");
        } else {
            named(info, ChatColor.AQUA + "" + ChatColor.BOLD + "Day " + Math.min(next, TOTAL_DAYS)
                    + " of " + TOTAL_DAYS);
            lore.add(ChatColor.GRAY + "Pending claims: " + ChatColor.WHITE + pending);
            lore.add(ChatColor.GRAY + "Streak safe for " + ChatColor.WHITE + safe + " more day(s)");
            lore.add("");
            lore.add(ChatColor.GRAY + "Online time today:");
            lore.add(UiKit.colour("&f" + UiKit.progressBar(
                    onlineMin / (double) needMin, 20, "&a", "&8",
                    onlineMin + "m", needMin + "m")));
            if (safe <= 1 && pending <= 0) {
                lore.add(ChatColor.RED + "" + ChatColor.BOLD + "Warning: your streak resets");
                lore.add(ChatColor.RED + "if you miss today!");
            }
        }
        lore(info, lore);
        return info;
    }

    private ItemStack claimedDay(int day, Reward reward) {
        ItemStack icon = new ItemStack(Material.LIME_DYE);
        named(icon, ChatColor.GREEN + "" + ChatColor.BOLD + "Day " + day);
        List<String> lore = contentsLore(reward);
        lore.add("");
        lore.add(ChatColor.GREEN + "" + ChatColor.BOLD + "Claimed");
        lore(icon, lore);
        return icon;
    }

    private ItemStack readyDay(int day, Reward reward) {
        ItemStack icon = dayIcon(day, reward);
        named(icon, ChatColor.GREEN + "" + ChatColor.BOLD + "Day " + day);
        List<String> lore = contentsLore(reward);
        if (MILESTONE_DAYS.contains(day)) {
            lore.add(0, ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "MILESTONE");
        }
        lore.add("");
        lore.add(ChatColor.GREEN + "" + ChatColor.BOLD + "Click to collect");
        lore(icon, lore);
        UiKit.glow(icon);
        return icon;
    }

    private ItemStack lockedDay(int day, Reward reward) {
        ItemStack icon = new ItemStack(Material.GRAY_DYE);
        named(icon, ChatColor.GRAY + "" + ChatColor.BOLD + "Day " + day);
        List<String> lore = contentsLore(reward);
        for (int i = 0; i < lore.size(); i++) {
            lore.set(i, ChatColor.GRAY + ChatColor.stripColor(lore.get(i)));
        }
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Locked - play "
                + Math.max(1L, this.rewards.dailyQualifySeconds() / 60L)
                + " minutes online on day " + day + ".");
        lore(icon, lore);
        return icon;
    }

    private ItemStack dayIcon(int day, Reward reward) {
        if (day == TOTAL_DAYS) return new ItemStack(Material.PURPLE_DYE);
        if (MILESTONE_DAYS.contains(day)) return new ItemStack(Material.PURPLE_DYE);
        return derivedIcon(reward);
    }

    private ItemStack claimButton(Player player) {
        int pending = this.rewards.dailyPending(player);
        if (this.rewards.dailyCompleted(player)) {
            return named(Material.BARRIER,
                    ChatColor.GOLD + "" + ChatColor.BOLD + "COMPLETED",
                    ChatColor.GRAY + "All " + TOTAL_DAYS + " days claimed!");
        }
        if (pending > 0) {
            return named(Material.ORANGE_DYE,
                    ChatColor.GREEN + "" + ChatColor.BOLD + "CLAIM DAY "
                            + this.rewards.dailyNextDay(player),
                    ChatColor.GRAY + "Collect " + pending + " pending reward(s).",
                    "",
                    ChatColor.GREEN + "" + ChatColor.BOLD + "Click to collect!");
        }
        return named(Material.GRAY_DYE,
                ChatColor.GRAY + "" + ChatColor.BOLD + "NOTHING TO CLAIM",
                ChatColor.DARK_GRAY + "Play " + Math.max(1L,
                        this.rewards.dailyQualifySeconds() / 60L)
                        + " minutes online to earn a day.");
    }

    private ItemStack legend() {
        return named(Material.BOOK,
                ChatColor.AQUA + "" + ChatColor.BOLD + "How this works",
                ChatColor.GRAY + "Claim " + ChatColor.WHITE + TOTAL_DAYS
                        + ChatColor.GRAY + " days in a row.",
                ChatColor.GRAY + "A day needs "
                        + ChatColor.WHITE + Math.max(1L,
                                this.rewards.dailyQualifySeconds() / 60L)
                        + ChatColor.GRAY + " online minutes.",
                ChatColor.GRAY + "Miss " + ChatColor.WHITE + "3"
                        + ChatColor.GRAY + " days and you return to day 1.",
                "",
                ChatColor.GREEN + "Lime" + ChatColor.GRAY + " = claimed",
                ChatColor.YELLOW + "Orange" + ChatColor.GRAY + " = ready, click it",
                ChatColor.GRAY + "Grey" + ChatColor.GRAY + " = still locked",
                ChatColor.LIGHT_PURPLE + "Purple" + ChatColor.GRAY + " = milestone");
    }

    private ItemStack statsItem(Player player) {
        return named(Material.CLOCK,
                ChatColor.AQUA + "" + ChatColor.BOLD + "Your streak",
                ChatColor.GRAY + "Next day: " + ChatColor.WHITE + this.rewards.dailyNextDay(player),
                ChatColor.GRAY + "Pending: " + ChatColor.WHITE + this.rewards.dailyPending(player),
                ChatColor.GRAY + "Safe for: " + ChatColor.WHITE + this.rewards.dailySafeDaysLeft(player)
                        + ChatColor.GRAY + " more day(s)");
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
        int pages = pageCount();

        if (slot == CLAIM_SLOT) {
            claim(player, holder.page());
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
            if (DAY_SLOTS[i] != slot) continue;
            int day = holder.page() * PER_PAGE + i + 1;
            // Clicking a ready day collects it; anything else is inert, so the
            // item is a preview rather than a promise.
            if (this.rewards.dailyPending(player) > 0 && !this.rewards.dailyCompleted(player)
                    && day == this.rewards.dailyNextDay(player)) {
                claim(player, holder.page());
            } else {
                deny(player);
            }
            return;
        }
    }

    private void claim(Player player, int page) {
        if (this.rewards.claimDailyNext(player)) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
            refresh(player, page);
        } else {
            deny(player);
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