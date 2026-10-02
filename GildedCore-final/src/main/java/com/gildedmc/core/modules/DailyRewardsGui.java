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
 * Daily claim GUI: 30 day slots over rows 1-5, info at top, Claim button.
 *
 * <pre>
 *   Row 0: border, info at slot 4
 *   Rows 1-5, cols 1-6: days, 6 per row. Day d at 9*(1+(d-1)/6)+1+(d-1)%6.
 *   Cols 0, 7, 8: border. Slot 53: Claim (active iff pending &gt; 0).
 * </pre>
 *
 * <p>States: claimed = green pane + "Claimed" + rewards; next = reward icon
 * with glow + "Click Claim to collect"; locked = gray pane + reward preview;
 * milestone days (7, 14, 21, 28, 30) use a distinct icon.
 */
public final class DailyRewardsGui implements Listener {

    private static final int SIZE = 54;
    private static final String TITLE = UiKit.titleGradient("Daily Rewards");

    private static final Material EDGE = UiKit.FRAME_EDGE;
    private static final int INFO_SLOT = 4;
    private static final int CLAIM_SLOT = 53;

    private static final java.util.Set<Integer> MILESTONE_DAYS =
            java.util.Set.of(7, 14, 21, 28, 30);

    private final RewardsModule rewards;

    public DailyRewardsGui(RewardsModule rewards) {
        this.rewards = rewards;
    }

    public static final class Holder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public void openGui(Player player) {
        this.rewards.checkDailyReset(player);
        this.rewards.qualifyDaily(player);
        Inventory inv = UiKit.chest(6, TITLE, new Holder());
        render(player, inv);
        player.openInventory(inv);
    }

    private boolean isOurs(Inventory inv) {
        return inv != null && inv.getHolder() instanceof Holder;
    }

    /** Day d (1-based) lives here. Day 1 = 10, day 30 = 51. */
    static int slotForDay(int day) {
        int d = day - 1;
        return 9 * (1 + d / 6) + 1 + d % 6;
    }

    private void render(Player player, Inventory inv) {
        inv.setItem(INFO_SLOT, infoItem(player));
        renderDays(player, inv);
        inv.setItem(CLAIM_SLOT, claimButton(player));
        // House frame last: it only fills ring slots still empty, so the info
        // star and the Claim button keep their places while every other edge
        // slot picks up the black pane / orange corner treatment used by
        // /gildedcore.
        UiKit.framed(inv, UiKit.FRAME_EDGE, UiKit.FRAME_CORNER);
        player.updateInventory();
    }

    private void renderDays(Player player, Inventory inv) {
        int next = this.rewards.dailyNextDay(player);
        int pending = this.rewards.dailyPending(player);
        boolean completed = this.rewards.dailyCompleted(player);
        for (int day = 1; day <= 30; day++) {
            int slot = slotForDay(day);
            Reward reward = this.rewards.dailyRewardForDay(day);
            if (reward == null) {
                inv.setItem(slot, lockedDay(day, null));
                continue;
            }
            if (completed || day < next) {
                inv.setItem(slot, claimedDay(day, reward));
            } else if (day == next && pending > 0 && !completed) {
                inv.setItem(slot, nextDay(day, reward));
            } else {
                inv.setItem(slot, lockedDay(day, reward));
            }
        }
    }

    private ItemStack claimedDay(int day, Reward reward) {
        ItemStack icon = new ItemStack(Material.LIME_DYE);
        name(icon, ChatColor.GREEN + "" + ChatColor.BOLD + "Day " + day);
        List<String> lore = contentsLore(reward);
        lore.add("");
        lore.add(ChatColor.GREEN + "Claimed");
        lore(lore, icon);
        return icon;
    }

    private ItemStack nextDay(int day, Reward reward) {
        ItemStack icon = dayIcon(day, reward);
        name(icon, ChatColor.GREEN + "" + ChatColor.BOLD + "Day " + day);
        List<String> lore = contentsLore(reward);
        if (MILESTONE_DAYS.contains(day)) {
            lore.add(0, ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "MILESTONE");
        }
        lore.add("");
        lore.add(ChatColor.GREEN + "" + ChatColor.BOLD + "Click Claim to collect");
        lore(lore, icon);
        glow(icon);
        return icon;
    }

    private ItemStack lockedDay(int day, Reward reward) {
        ItemStack icon = new ItemStack(Material.GRAY_DYE);
        name(icon, ChatColor.GRAY + "" + ChatColor.BOLD + "Day " + day);
        List<String> lore = reward == null ? new ArrayList<>() : contentsLore(reward);
        // Preview in gray so future rewards read as locked, not payable.
        for (int i = 0; i < lore.size(); i++) {
            lore.set(i, ChatColor.GRAY + ChatColor.stripColor(lore.get(i)));
        }
        lore(lore, icon);
        return icon;
    }

    private ItemStack dayIcon(int day, Reward reward) {
        if (day == 30) {
            return new ItemStack(Material.PURPLE_DYE);
        }
        if (MILESTONE_DAYS.contains(day)) {
            return new ItemStack(Material.LIGHT_BLUE_DYE);
        }
        return derivedIcon(reward);
    }

    private ItemStack infoItem(Player player) {
        int next = this.rewards.dailyNextDay(player);
        int pending = this.rewards.dailyPending(player);
        int safe = this.rewards.dailySafeDaysLeft(player);
        long needMin = Math.max(1L, this.rewards.dailyQualifySeconds() / 60L);
        long onlineMin = this.rewards.activeToday(player.getUniqueId()) / 60L;
        boolean completed = this.rewards.dailyCompleted(player);
        ItemStack info = new ItemStack(Material.NETHER_STAR);
        List<String> lore = new ArrayList<>();
        if (completed) {
            name(info, ChatColor.GOLD + "" + ChatColor.BOLD + "Daily Rewards Completed");
            lore.add(ChatColor.GRAY + "All 30 days claimed!");
        } else {
            name(info, ChatColor.AQUA + "" + ChatColor.BOLD + "Day " + Math.min(next, 30) + " of 30");
            lore.add(ChatColor.GRAY + "Pending claims: " + ChatColor.WHITE + pending);
            lore.add(ChatColor.GRAY + "Streak safe for " + ChatColor.WHITE + safe + " more day(s)");
            lore.add(ChatColor.GRAY + "Online time today:");
            lore.add(UiKit.colour("&f" + UiKit.progressBar(
                    onlineMin / (double) needMin, 12, "&a", "&8",
                    onlineMin + "m", needMin + "m")));
            if (safe <= 1 && pending <= 0) {
                lore.add("");
                lore.add(ChatColor.RED + "" + ChatColor.BOLD + "Warning: your streak resets");
                lore.add(ChatColor.RED + "if you miss today!");
            }
        }
        lore(lore, info);
        return info;
    }

    private ItemStack claimButton(Player player) {
        int pending = this.rewards.dailyPending(player);
        boolean completed = this.rewards.dailyCompleted(player);
        if (completed) {
            ItemStack done = named(Material.BARRIER, ChatColor.GOLD + "" + ChatColor.BOLD + "Completed");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "All 30 days claimed!");
            lore(lore, done);
            return done;
        }
        if (pending > 0) {
            ItemStack claim = named(Material.LIME_CONCRETE,
                    ChatColor.GREEN + "" + ChatColor.BOLD + "CLAIM (" + pending + ")");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Collect Day " + this.rewards.dailyNextDay(player) + "'s reward");
            lore.add("");
            lore.add(ChatColor.GREEN + "" + ChatColor.BOLD + "Click to collect!");
            lore(lore, claim);
            glow(claim);
            return claim;
        }
        ItemStack idle = named(Material.GRAY_CONCRETE, ChatColor.GRAY + "" + ChatColor.BOLD + "CLAIM");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "Nothing to claim right now.");
        lore.add(ChatColor.DARK_GRAY + "Play " + Math.max(1L,
                this.rewards.dailyQualifySeconds() / 60L) + " minutes online to earn a day.");
        lore(lore, idle);
        return idle;
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
        if (!isOurs(event.getInventory())) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getInventory())) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;
        if (slot == CLAIM_SLOT) {
            clickClaim(player);
            return;
        }
        int day = dayForSlot(slot);
        if (day >= 1) {
            clickDay(player, day);
        }
    }

    private int dayForSlot(int slot) {
        for (int day = 1; day <= 30; day++) {
            if (slotForDay(day) == slot) return day;
        }
        return -1;
    }

    private void clickClaim(Player player) {
        if (this.rewards.claimDailyNext(player)) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
            refresh(player);
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
        }
    }

    private void clickDay(Player player, int day) {
        int next = this.rewards.dailyNextDay(player);
        int pending = this.rewards.dailyPending(player);
        if (!this.rewards.dailyCompleted(player) && day == next && pending > 0) {
            clickClaim(player);
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