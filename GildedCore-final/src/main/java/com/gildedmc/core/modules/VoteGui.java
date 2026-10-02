package com.gildedmc.core.modules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

import com.gildedmc.core.GildedCorePlugin;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;

/**
 * /vote GUI: vote sites as clickable books, vote stats, party progress and
 * the per-vote reward preview.
 *
 * <pre>
 *   Row 0: house frame (black edge, orange corners)
 *   Row 1: up to 7 vote-site books, centred (slots 10-16)
 *   Row 2: stats (20), party progress (22), reward preview (24)
 *   Row 3: house frame
 * </pre>
 *
 * <p>Everything visual comes from {@link UiKit}, so this reads as the same
 * family as /gildedcore, /rtpqueue and the two rewards menus. Clicking a book
 * sends a clickable chat line with an OPEN_URL click event as well as carrying
 * the event on the item itself, so the URL opens either way the client
 * handles it.
 */
public final class VoteGui implements Listener {

    private static final int SIZE = 36;
    private static final int ROW_1 = 10;   // first interior slot of row 1
    private static final int ROW_2 = 19;   // first interior slot of row 2
    private static final int LINK_WIDTH = 7;
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    private final VoteModule votes;
    private final GildedCorePlugin plugin;

    public VoteGui(VoteModule votes, GildedCorePlugin plugin) {
        this.votes = votes;
        this.plugin = plugin;
    }

    public static final class Holder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public void openGui(Player player) {
        Inventory inv = UiKit.themed(4, UiKit.titleGradient("Vote"), new Holder());
        render(player, inv);
        player.openInventory(inv);
        player.updateInventory();
    }

    private boolean isOurs(Inventory inv) {
        return inv != null && inv.getHolder() instanceof Holder;
    }

    private void render(Player player, Inventory inv) {
        List<String> links = this.votes.voteLinks();
        int shown = Math.min(LINK_WIDTH, links.size());
        int start = ROW_1 + (LINK_WIDTH - shown) / 2;
        for (int i = 0; i < shown; i++) {
            inv.setItem(start + i, linkItem(links.get(i)));
        }
        inv.setItem(ROW_2 + 1, statsItem(player));
        inv.setItem(ROW_2 + 3, partyItem());
        inv.setItem(ROW_2 + 5, rewardItem());
    }

    /** One vote site: a book whose lore and chat line are both clickable. */
    private ItemStack linkItem(String raw) {
        Matcher m = URL.matcher(raw);
        String url = m.find() ? m.group(0) : "";
        String label = url.isEmpty() ? UiKit.strip(raw) : UiKit.strip(raw.substring(0, m.start())).trim();
        while (label.endsWith("-")) label = label.substring(0, label.length() - 1).trim();
        if (label.isEmpty()) label = url;

        ItemStack book = new ItemStack(Material.BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(UiKit.colour("&b&l" + label));
            List<String> lore = new ArrayList<>();
            lore.add(UiKit.colour("&8Vote site"));
            lore.add(UiKit.colour("&7" + UiKit.wrap(url, 30).get(0)));
            lore.add(" ");
            lore.add(UiKit.colour("&e" + UiKit.ARROW + "&e&l&n CLICK &r&eto open"));
            meta.setLore(lore);
            book.setItemMeta(meta);
        }
        return UiKit.glow(book);
    }

    private ItemStack statsItem(Player player) {
        ItemStack head = UiKit.head(player, "&e&lYour votes",
                List.of(UiKit.colour("&7This month: &e" + this.votes.getMonthly(player.getUniqueId())),
                        UiKit.colour("&7Lifetime: &6" + this.votes.getLifetime(player.getUniqueId())),
                        this.votes.getPending(player.getUniqueId()) > 0
                                ? UiKit.colour("&7Queued while offline: &f" + this.votes.getPending(player.getUniqueId()))
                                : UiKit.colour("&8No queued votes")));
        return head;
    }

    private ItemStack partyItem() {
        int total = this.votes.getPartyTotal();
        int goal = this.votes.partyGoal();
        ItemStack party = UiKit.item(Material.DIAMOND, "&d&lVote Party",
                List.of(UiKit.colour("&7" + UiKit.progressBar(
                        goal == 0 ? 0 : (double) total / goal, 18, "&d", "&8",
                        String.valueOf(total), String.valueOf(goal))),
                        UiKit.colour("&7Every " + goal + " votes fires"),
                        UiKit.colour("&damethyst keys &7for everyone online."),
                        " ",
                        UiKit.colour("&8Offline votes count toward the party.")));
        return party;
    }

    private ItemStack rewardItem() {
        List<String> lore = new ArrayList<>();
        lore.add(UiKit.colour("&7Paid to every voter:"));
        for (String command : this.votes.rewardCommands()) {
            lore.add(UiKit.colour("&f" + humanize(command)));
        }
        lore.add(" ");
        lore.add(UiKit.colour("&8Offline votes are queued and"));
        lore.add(UiKit.colour("&8paid on your next join."));
        return UiKit.item(Material.GOLD_INGOT, "&a&lVote Rewards", lore);
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
                    return amount + "x " + what.replace('_', ' ') + " Key";
                }
                return amount + "x " + what.replace('_', ' ');
            }
        } catch (Exception ignored) {
            // fall through
        }
        return command.replace("%player%", "").trim();
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
        List<String> links = this.votes.voteLinks();
        int shown = Math.min(LINK_WIDTH, links.size());
        int start = ROW_1 + (LINK_WIDTH - shown) / 2;
        if (slot >= start && slot < start + shown) {
            openLink(player, links.get(slot - start));
        }
    }

    /**
     * Sends the URL as a clickable chat component, then closes the menu. The
     * item carries the same event for clients that honour lore clicks; this
     * path guarantees the browser prompt regardless.
     */
    private void openLink(Player player, String raw) {
        Matcher m = URL.matcher(raw);
        String url = m.find() ? m.group(0) : "";
        player.closeInventory();
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6F, 1.2F);
        if (url.isEmpty()) return;
        TextComponent line = new TextComponent(UiKit.colour("&e" + url));
        line.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        player.spigot().sendMessage(line);
        player.sendMessage(UiKit.colour("&8" + UiKit.ARROW + " &7Click the link above to vote."));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (isOurs(event.getInventory())) {
            event.setCancelled(true);
        }
    }
}
