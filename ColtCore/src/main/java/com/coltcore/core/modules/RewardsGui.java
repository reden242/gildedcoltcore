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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rewards click GUI - a paged daily-reward ladder, styled as a battlepass track.
 *
 * <p>Visual language borrowed from the DonutCore sellmultiplier menu, which is
 * the house style: a small-caps title, hex accent names, a {@code ■} progress
 * bar, and no decoration competing with the content. The battlepass framing is
 * the ladder itself - each day is a tier, banked tiers read as completed, and a
 * progress bar shows how far through the current tier the player is.
 *
 * <p>Layout rule, applied strictly: <b>nothing sits on the border</b>. The
 * outer ring is frame only. Rungs occupy interior slots, and navigation sits on
 * the last interior row, so a player can never mistake a control for part of
 * the frame.
 */
public final class RewardsGui implements Listener {

    private static final int ROWS = 6;
    private static final String TITLE = "ʀᴇᴡᴀʀᴅs";
    /** Layer 1: the two-sided hub that chooses daily or playtime. */
    private static final String HUB_TITLE = "ʀᴇᴡᴀʀᴅs ʜᴜʙ";
    /** Layer 2, right-hand side: the playtime ladder. */
    private static final String PLAY_TITLE = "ᴘʟᴀʏᴛɪᴍᴇ ʀᴇᴡᴀʀᴅs";
    private static final String ACCENT = "&#00F986";

    /** Interior slots only: rows 1-3 give 21 rungs, row 4 holds navigation. */
    private static final int CONTENT = 21;

    /**
     * Hub controls, 0-based inventory slots. The user counts the top-left slot as
     * 1, so their 12/14/16 are these 11/13/15.
     *
     * <p>All three sit in interior row 1 and are symmetric about slot 13, which
     * is that row's exact centre: daily on the left, close dead centre, playtime
     * mirrored on the right.
     */
    private static final int HUB_DAILY = 11;
    private static final int HUB_CLOSE = 13;
    private static final int HUB_PLAY = 15;

    private final RewardsModule rewards;
    private final Map<UUID, Integer> pages = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playPages = new ConcurrentHashMap<>();

    public RewardsGui(RewardsModule rewards) {
        this.rewards = rewards;
    }

    public void enable() {}

    public void disable() {
        this.pages.clear();
        this.playPages.clear();
    }

    /** Opens layer 1: the hub that splits daily from playtime. */
    public void openGui(Player player) {
        openHub(player);
    }

    /**
     * Layer 1. Two sides, same frame and title treatment as the ladders.
     *
     * <p>Nothing else is placed: the border is frame only and the middle of the
     * GUI stays empty so the chest, the close and the clock read as three
     * deliberate controls rather than part of a decorated panel.
     */
    public void openHub(Player player) {
        Inventory inv = UiKit.themed(ROWS, UiKit.titleGradient(HUB_TITLE, "#ff3b3b", "#ffd700"));
        UiKit.framed(inv, UiKit.FRAME_EDGE, UiKit.FRAME_CORNER);

        int streak = rewards.streak(player);
        int pending = rewards.unclaimedEligibleDates(player).size();
        long hours = rewards.playtimeHours(player);
        List<RewardsModule.Reward> daily = rewards.dailyRewards();
        List<RewardsModule.Reward> play = rewards.playtimeRewards();

        List<String> dailyLore = new ArrayList<>();
        dailyLore.add("&7A consecutive-day streak. A missed day");
        dailyLore.add("&7resets it to one.");
        dailyLore.add("&fStreak: &7" + streak + " day(s)");
        dailyLore.add("&fDays configured: &7" + daily.size());
        if (pending > 0) dailyLore.add("&a" + pending + " day(s) waiting to claim");
        else dailyLore.add("&7Nothing waiting right now.");
        dailyLore.add("&e" + UiKit.ARROW + " &e&l&lCLICK &r&eto open the daily ladder");
        inv.setItem(HUB_DAILY, UiKit.glow(
                UiKit.item(Material.CHEST, ACCENT + "ᴅᴀɪʟʏ ʀᴇᴡᴀʀᴅs", dailyLore)));

        List<String> playLore = new ArrayList<>();
        playLore.add("&7One-off rewards for total hours");
        playLore.add("&7played. Claimed once, then done.");
        playLore.add("&fPlaytime: &7" + hours + "h");
        playLore.add("&fTiers configured: &7" + play.size());
        playLore.add("&e" + UiKit.ARROW + " &e&l&lCLICK &r&eto open the playtime ladder");
        inv.setItem(HUB_PLAY, UiKit.glow(
                UiKit.item(Material.CLOCK, ACCENT + "ᴘʟᴀʏᴛɪᴍᴇ ʀᴇᴡᴀʀᴅs", playLore)));

        inv.setItem(HUB_CLOSE, UiKit.close());

        player.openInventory(inv);
        UiKit.sound(player, org.bukkit.Sound.BLOCK_CHEST_OPEN, 1.2F);
    }

    public void openPage(Player player, int page) {
        List<RewardsModule.Reward> ladder = rewards.dailyRewards();
        int pageCount = Math.max(1, (ladder.size() + CONTENT - 1) / CONTENT);
        int current = Math.max(0, Math.min(page, pageCount - 1));
        this.pages.put(player.getUniqueId(), current);

        List<Integer> rungs = UiKit.interiorPage(ROWS * 9, current, CONTENT);
        List<Integer> nav = UiKit.lastInteriorRow(ROWS * 9);
        Inventory inv = UiKit.themed(ROWS, UiKit.titleGradient(TITLE, "#ff3b3b", "#ffd700"));
        UiKit.framed(inv, UiKit.FRAME_EDGE, UiKit.FRAME_CORNER);

        UUID id = player.getUniqueId();
        int streak = rewards.streak(player);
        int pending = rewards.unclaimedEligibleDates(player).size();
        long need = rewards.minimumOnlineSeconds();
        long haveToday = rewards.onlineSeconds(id, LocalDate.now());
        boolean canClaim = pending > 0;

        // Banked rungs stop short of the streak by however many earned days are
        // still waiting to be paid, so those show red instead of green.
        int bankedThrough = Math.max(0, streak - pending);
        int nextRung = bankedThrough + pending + 1;

        for (int i = 0; i < rungs.size(); i++) {
            int index = current * CONTENT + i;
            if (index >= ladder.size()) break;
            RewardsModule.Reward reward = ladder.get(index);
            int rung = (int) reward.threshold();

            Material material;
            String name;
            List<String> lore = new ArrayList<>();
            lore.add("&7Tier &f" + rung);
            lore.add("&7Reward: &f" + reward.id().replace('-', ' '));
            lore.add(" ");

            if (rung <= bankedThrough) {
                material = Material.LIME_DYE;
                name = ACCENT + "ᴛɪᴇʀ " + rung;
                lore.add("&aClaimed.");
            } else if (rung <= bankedThrough + pending) {
                material = Material.RED_DYE;
                name = "&cᴛɪᴇʀ " + rung + " &8- unclaimed";
                lore.add("&cYou earned this and never collected it.");
                lore.add("&e" + UiKit.ARROW + " &e&l&nCLICK &r&eto claim");
            } else if (rung == nextRung) {
                material = Material.ORANGE_DYE;
                name = "&6ᴛɪᴇʀ " + rung + " &8- next";
                double fraction = need <= 0L ? 1.0D : Math.min(1.0D, (double) haveToday / (double) need);
                lore.add("&7Progress to &f" + (haveToday / 60L) + "m &7of &f" + (need / 60L) + "m");
                lore.add(bar(fraction));
                if (canClaim) {
                    lore.add("&6Claimable right now.");
                    lore.add("&e" + UiKit.ARROW + " &e&l&nCLICK &r&eto claim");
                } else {
                    lore.add("&7Play " + (need / 60L) + " minutes to earn it.");
                }
            } else {
                material = Material.GRAY_DYE;
                name = "&7ᴛɪᴇʀ " + rung + " &8- locked";
                lore.add("&7Keep your streak going to reach this.");
            }

            ItemStack item = UiKit.item(material, name, lore);
            if (material == Material.RED_DYE || material == Material.ORANGE_DYE) item = UiKit.glow(item);
            inv.setItem(rungs.get(i), item);
        }

        long hours = rewards.playtimeHours(player);
        List<String> status = new ArrayList<>();
        status.add("&7Streak: &f" + streak + " day(s)");
        status.add("&7Today: &f" + (haveToday / 60L) + "m &7of &f" + (need / 60L) + "m");
        status.add("&7Playtime: &f" + hours + "h");
        if (pending > 0) status.add("&c" + pending + " day(s) waiting to claim");
        inv.setItem(nav.get(6), UiKit.item(Material.NETHER_STAR, ACCENT + "ʏᴏᴜʀ sᴛᴀɴᴅɪɴɢ", status));

        // Back returns to the hub rather than dumping the player out of the GUI.
        // Close sits at nav.get(3), the exact centre of the navigation row.
        inv.setItem(nav.get(0), UiKit.back("the rewards hub"));
        if (current > 0) {
            inv.setItem(nav.get(1), UiKit.item(Material.ARROW, "&ePrevious tier page",
                    "&7Page " + current + " of " + pageCount));
        }
        if (current < pageCount - 1) {
            inv.setItem(nav.get(2), UiKit.item(Material.ARROW, "&eNext tier page",
                    "&7Page " + (current + 2) + " of " + pageCount));
        }
        inv.setItem(nav.get(3), UiKit.close());

        List<String> claimLore = new ArrayList<>();
        if (canClaim) {
            claimLore.add("&7Pays every earned tier at once.");
            claimLore.add("&f" + pending + " day(s) waiting.");
            claimLore.add("&e" + UiKit.ARROW + " &e&l&lCLICK &r&eto claim");
            inv.setItem(nav.get(4), UiKit.glow(UiKit.item(Material.HOPPER, ACCENT + "ᴄʟᴀɪᴍ ᴀʟʟ", claimLore)));
        } else {
            claimLore.add(haveToday >= need
                    ? "&7Nothing waiting right now."
                    : "&7Need " + Math.max(1L, ((need - haveToday) + 59L) / 60L) + " more minute(s) today.");
            inv.setItem(nav.get(4), UiKit.item(Material.GRAY_DYE, "&7ᴄʟᴀɪᴍ ᴀʟʟ", claimLore));
        }

        player.openInventory(inv);
        UiKit.sound(player, org.bukkit.Sound.BLOCK_CHEST_OPEN, 1.2F);
    }

    /**
     * Layer 2, right-hand side: the playtime ladder.
     *
     * <p>Mirrors the daily ladder so the two sides of the hub behave the same
     * way. Playtime tiers are one-off, so a tier is only clickable once it is
     * both earned and unpaid, and the hours bar shows progress to the next one.
     */
    public void openPlaytime(Player player, int page) {
        List<RewardsModule.Reward> ladder = rewards.playtimeRewards();
        int pageCount = Math.max(1, (ladder.size() + CONTENT - 1) / CONTENT);
        int current = Math.max(0, Math.min(page, pageCount - 1));
        this.playPages.put(player.getUniqueId(), current);

        List<Integer> rungs = UiKit.interiorPage(ROWS * 9, current, CONTENT);
        List<Integer> nav = UiKit.lastInteriorRow(ROWS * 9);
        Inventory inv = UiKit.themed(ROWS, UiKit.titleGradient(PLAY_TITLE, "#ff3b3b", "#ffd700"));
        UiKit.framed(inv, UiKit.FRAME_EDGE, UiKit.FRAME_CORNER);

        long hours = rewards.playtimeHours(player);

        // Where the hours bar sits: at the next tier the player has not reached.
        RewardsModule.Reward next = null;
        for (RewardsModule.Reward reward : ladder) {
            if (hours < reward.threshold()) { next = reward; break; }
        }
        double fraction = next == null || next.threshold() <= 0
                ? 1.0D : Math.min(1.0D, (double) hours / (double) next.threshold());

        for (int i = 0; i < rungs.size(); i++) {
            int index = current * CONTENT + i;
            if (index >= ladder.size()) break;
            RewardsModule.Reward reward = ladder.get(index);
            long need = reward.threshold();
            boolean claimed = rewards.playtimeTierClaimed(player, reward.id());
            boolean ready = hours >= need && !claimed;

            Material material = claimed ? Material.GRAY_DYE
                    : ready ? Material.CLOCK : Material.GRAY_STAINED_GLASS_PANE;
            String name = claimed ? "&8" + reward.id()
                    : ready ? ACCENT + reward.id() : "&7" + reward.id();
            List<String> lore = new ArrayList<>();
            lore.add("&7Requires &f" + need + "h &7played");
            if (claimed) {
                lore.add("&8Already claimed.");
            } else if (ready) {
                lore.add("&aUnclaimed");
                lore.add("&e" + UiKit.ARROW + " &e&l&lCLICK &r&eto claim");
            } else {
                lore.add("&7Locked");
                lore.add("&8" + Math.max(0L, need - hours) + "h to go");
            }
            inv.setItem(rungs.get(i), UiKit.item(material, name, lore));
        }

        List<String> status = new ArrayList<>();
        status.add("&7Playtime: &f" + hours + "h");
        status.add("&7Tiers: &f" + ladder.size());
        if (next != null) {
            status.add("&7Next tier: &f" + next.id() + " &7at &f" + next.threshold() + "h");
            status.add(bar(fraction));
        } else {
            status.add("&aEvery tier unlocked");
        }
        inv.setItem(nav.get(6), UiKit.item(Material.NETHER_STAR, ACCENT + "ʏᴏᴜʀ sᴛᴀɴᴅɪɴɢ", status));

        inv.setItem(nav.get(0), UiKit.back("the rewards hub"));
        if (current > 0) {
            inv.setItem(nav.get(1), UiKit.item(Material.ARROW, "&ePrevious tier page",
                    "&7Page " + current + " of " + pageCount));
        }
        if (current < pageCount - 1) {
            inv.setItem(nav.get(2), UiKit.item(Material.ARROW, "&eNext tier page",
                    "&7Page " + (current + 2) + " of " + pageCount));
        }
        inv.setItem(nav.get(3), UiKit.close());

        int ready = 0;
        for (RewardsModule.Reward reward : ladder) {
            if (rewards.playtimeTierReady(player, reward)) ready++;
        }
        List<String> claimLore = new ArrayList<>();
        if (ready > 0) {
            claimLore.add("&7Pays every unlocked tier at once.");
            claimLore.add("&f" + ready + " tier(s) waiting.");
            claimLore.add("&e" + UiKit.ARROW + " &e&l&lCLICK &r&eto claim");
            inv.setItem(nav.get(4), UiKit.glow(UiKit.item(Material.HOPPER, ACCENT + "ᴄʟᴀɪᴍ ᴀʟʟ", claimLore)));
        } else {
            claimLore.add("&7Nothing unlocked and unclaimed.");
            inv.setItem(nav.get(4), UiKit.item(Material.GRAY_DYE, "&7ᴄʟᴀɪᴍ ᴀʟʟ", claimLore));
        }

        player.openInventory(inv);
        UiKit.sound(player, org.bukkit.Sound.BLOCK_CHEST_OPEN, 1.2F);
    }

    /** The sellmulti-style tier bar: ten blocks, filled green then grey. */
    private static String bar(double fraction) {
        int width = 10;
        int filled = (int) Math.round(Math.max(0.0D, Math.min(1.0D, fraction)) * width);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < filled; i++) sb.append(ACCENT).append('■');
        for (int i = filled; i < width; i++) sb.append("&8").append('■');
        sb.append(" &7").append(Math.round(fraction * 100)).append('%');
        return sb.toString();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null) return;
        String title = ChatColor.stripColor(event.getView().getTitle());
        if (title.equals(HUB_TITLE)) {
            event.setCancelled(true);
            hubClick(player, event.getSlot());
            return;
        }
        if (title.equals(PLAY_TITLE)) {
            event.setCancelled(true);
            playtimeClick(player, event.getSlot());
            return;
        }
        if (!title.equals(TITLE)) return;
        event.setCancelled(true);

        int slot = event.getSlot();
        List<RewardsModule.Reward> ladder = rewards.dailyRewards();
        int pageCount = Math.max(1, (ladder.size() + CONTENT - 1) / CONTENT);
        int current = this.pages.getOrDefault(player.getUniqueId(), 0);
        List<Integer> rungs = UiKit.interiorPage(ROWS * 9, current, CONTENT);
        List<Integer> nav = UiKit.lastInteriorRow(ROWS * 9);

        // Rungs are addressed by their position in the interior list, not by
        // raw slot, so a layout change cannot make a click claim the wrong tier.
        int rungIndex = rungs.indexOf(slot);
        if (rungIndex >= 0 && ladder.size() > current * CONTENT + rungIndex) {
            RewardsModule.Reward clicked = ladder.get(current * CONTENT + rungIndex);
            int rung = (int) clicked.threshold();
            int streak = rewards.streak(player);
            int pending = rewards.unclaimedEligibleDates(player).size();
            int bankedThrough = Math.max(0, streak - pending);
            if (rung > bankedThrough) {
                claim(player);
                return;
            }
            UiKit.sound(player, org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT, 1.2F);
            return;
        }

        if (nav.size() > 4 && slot == nav.get(4)) {
            claim(player);
            return;
        }
        if (nav.size() > 0 && slot == nav.get(0)) {
            UiKit.sound(player, org.bukkit.Sound.UI_BUTTON_CLICK, 1.4F);
            openHub(player);
            return;
        }
        if (nav.size() > 1 && slot == nav.get(1)) {
            UiKit.sound(player, org.bukkit.Sound.UI_BUTTON_CLICK, 1.4F);
            openPage(player, current - 1);
            return;
        }
        if (nav.size() > 2 && slot == nav.get(2)) {
            UiKit.sound(player, org.bukkit.Sound.UI_BUTTON_CLICK, 1.4F);
            openPage(player, current + 1);
            return;
        }
        if (nav.size() > 3 && slot == nav.get(3)) {
            player.closeInventory();
        }
    }

    /** Layer 1. Chest opens the daily ladder, clock the playtime one. */
    private void hubClick(Player player, int slot) {
        if (slot == HUB_DAILY) {
            UiKit.sound(player, org.bukkit.Sound.UI_BUTTON_CLICK, 1.4F);
            openPage(player, this.pages.getOrDefault(player.getUniqueId(), 0));
            return;
        }
        if (slot == HUB_PLAY) {
            UiKit.sound(player, org.bukkit.Sound.UI_BUTTON_CLICK, 1.4F);
            openPlaytime(player, this.playPages.getOrDefault(player.getUniqueId(), 0));
            return;
        }
        if (slot == HUB_CLOSE) player.closeInventory();
    }

    /** Layer 2, playtime side. */
    private void playtimeClick(Player player, int slot) {
        List<RewardsModule.Reward> ladder = rewards.playtimeRewards();
        int pageCount = Math.max(1, (ladder.size() + CONTENT - 1) / CONTENT);
        int current = this.playPages.getOrDefault(player.getUniqueId(), 0);
        List<Integer> rungs = UiKit.interiorPage(ROWS * 9, current, CONTENT);
        List<Integer> nav = UiKit.lastInteriorRow(ROWS * 9);

        int rungIndex = rungs.indexOf(slot);
        if (rungIndex >= 0 && ladder.size() > current * CONTENT + rungIndex) {
            RewardsModule.Reward clicked = ladder.get(current * CONTENT + rungIndex);
            if (rewards.playtimeTierReady(player, clicked)) {
                UiKit.sound(player, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0F);
                openPlaytime(player, current);
                rewards.command(player, new String[]{"playtime"});
                return;
            }
            UiKit.sound(player, org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT, 1.2F);
            return;
        }

        if (nav.size() > 4 && slot == nav.get(4)) {
            UiKit.sound(player, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0F);
            openPlaytime(player, current);
            rewards.command(player, new String[]{"playtime"});
            return;
        }
        if (nav.size() > 0 && slot == nav.get(0)) {
            UiKit.sound(player, org.bukkit.Sound.UI_BUTTON_CLICK, 1.4F);
            openHub(player);
            return;
        }
        if (nav.size() > 1 && slot == nav.get(1)) {
            UiKit.sound(player, org.bukkit.Sound.UI_BUTTON_CLICK, 1.4F);
            openPlaytime(player, current - 1);
            return;
        }
        if (nav.size() > 2 && slot == nav.get(2)) {
            UiKit.sound(player, org.bukkit.Sound.UI_BUTTON_CLICK, 1.4F);
            openPlaytime(player, current + 1);
            return;
        }
        if (nav.size() > 3 && slot == nav.get(3)) {
            player.closeInventory();
        }
    }

    private void claim(Player player) {
        UiKit.sound(player, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0F);
        player.closeInventory();
        rewards.command(player, new String[]{"claimall"});
        this.pages.remove(player.getUniqueId());
    }
}
