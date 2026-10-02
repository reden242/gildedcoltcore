package com.coltcore.core.modules;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared look and feel for every ColtCore menu.
 *
 * <p>Before this existed each GUI built its own filler, its own item factory
 * and its own colour handling, so the wipe menu, the RTP menu and the staff
 * panels all looked like they came from different plugins. Everything visual
 * now goes through here: same border, same header, same back button, same
 * lore shape.
 *
 * <h2>The information card</h2>
 * {@link #infoLore} renders the standard card used by the punish menus and anything
 * else that needs to explain a destructive action before it is clicked:
 *
 * <pre>
 * &#ff0000&lBANS
 * &8Description:
 *
 * &#00ff00Information:
 * &fBans a player and prevents them from rejoining for their bantime.
 * ...
 * &cUse responsibly.
 *
 * &e&#10148 &e&l&nCLICK &r&eto open
 * </pre>
 */
public final class UiKit {

    private UiKit() { }

    /** Arrow glyph used on every "click to open" line. */
    public static final String ARROW = "➤";

    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    /* ------------------------------------------------------------------ */
    /*  Colour                                                            */
    /* ------------------------------------------------------------------ */

    /** Translates &amp;a codes and &amp;#rrggbb hex. Null-safe. */
    public static String colour(String text) {
        if (text == null) return "";
        Matcher m = HEX.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            StringBuilder rep = new StringBuilder("§x");
            for (char c : m.group(1).toCharArray()) rep.append('§').append(c);
            m.appendReplacement(out, rep.toString());
        }
        m.appendTail(out);
        return ChatColor.translateAlternateColorCodes('&', out.toString());
    }

    /* ------------------------------------------------------------------ */
    /*  Reward lines                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Turns one reward command into a short player-facing line.
     *
     * <p>The reward commands are all {@code <thing> give %player% <args>}, so
     * the placeholder sits at index 2 and shifts every real argument one slot
     * to the right. Reading index 2 as the item is the bug this replaces: it
     * printed "1x %player% Key" for {@code crate give %player% gold 1}. There
     * was a private copy of this in both reward GUIs, which is how the two
     * drifted apart.
     *
     * <p>Anything unrecognised is echoed with the placeholder stripped, so a new
     * command form is visible rather than silently blank.
     */
    public static String rewardLine(String command) {
        if (command == null) return "";
        String text = command.trim();
        try {
            String lower = text.toLowerCase(Locale.ROOT);
            String[] p = text.split("\\s+");
            // eco give %player% <amount>
            if (lower.startsWith("eco give") && p.length >= 4) {
                return "$" + p[3];
            }
            // crate give %player% <type> [amount]  /  shard give %player% <amount>
            if (lower.startsWith("crate give") && p.length >= 4) {
                String type = p[3];
                String amount = p.length >= 5 ? p[4] : "1";
                return amount + "x " + titleCase(type) + " Key";
            }
            if (lower.startsWith("shard give") && p.length >= 3) {
                String amount = p.length >= 4 ? p[3] : "1";
                return amount + "x Shard" + ("1".equals(amount) ? "" : "s");
            }
            // give %player% <material> [amount]
            if (lower.startsWith("give ") && p.length >= 4) {
                String material = p[2];
                String amount = p.length >= 4 ? p[3] : "1";
                return amount + "x " + titleCase(material);
            }
        } catch (RuntimeException ignored) {
            // fall through to the raw echo
        }
        return text.replace("%player%", "").replaceAll("\\s+", " ").trim();
    }

    /** {@code amethyst} / {@code crimson_key} to {@code Amethyst} / {@code Crimson Key}. */
    private static String titleCase(String token) {
        if (token == null || token.isEmpty()) return "";
        String[] words = token.replace('_', ' ').toLowerCase(Locale.ROOT).trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w, 1, w.length());
        }
        return sb.toString();
    }

    public static String strip(String text) {
        return ChatColor.stripColor(colour(text));
    }

    /**
     * Per-character colour blend from one hex colour to another, for short
     * titles. Existing &amp;-codes pass through untouched; every other
     * character is recoloured along the gradient. Null-safe.
     */
    public static String gradient(String text, String fromHex, String toHex) {
        if (text == null) return "";
        int[] from = parseHex(fromHex);
        int[] to = parseHex(toHex);
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 1 < text.length()) { i++; continue; }
            if (c == '§' && i + 1 < text.length()) { i++; continue; }
            plain.append(c);
        }
        int n = 0;
        for (int i = 0; i < plain.length(); i++) if (plain.charAt(i) != ' ') n++;
        StringBuilder out = new StringBuilder();
        int seen = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < text.length()) {
                out.append(c).append(text.charAt(i + 1));
                i++;
                continue;
            }
            if (c == ' ' || n <= 1) { out.append(c); continue; }
            double t = (double) seen++ / (n - 1);
            int r = (int) (from[0] + (to[0] - from[0]) * t);
            int g = (int) (from[1] + (to[1] - from[1]) * t);
            int b = (int) (from[2] + (to[2] - from[2]) * t);
            out.append(String.format("§x§%c§%c§%c§%c§%c§%c%c",
                    hexChar(r >> 4), hexChar(r), hexChar(g >> 4), hexChar(g),
                    hexChar(b >> 4), hexChar(b), c));
        }
        return out.toString();
    }

    private static int[] parseHex(String hex) {
        int[] rgb = {255, 255, 255};
        if (hex == null) return rgb;
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() != 6) return rgb;
        try {
            rgb[0] = Integer.parseInt(h.substring(0, 2), 16);
            rgb[1] = Integer.parseInt(h.substring(2, 4), 16);
            rgb[2] = Integer.parseInt(h.substring(4, 6), 16);
        } catch (NumberFormatException ignored) { }
        return rgb;
    }

    private static char hexChar(int v) {
        return "0123456789abcdef".charAt(v & 0xF);
    }

    /* ------------------------------------------------------------------ */
    /*  Items                                                             */
    /* ------------------------------------------------------------------ */

    public static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material == null ? Material.PAPER : material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(colour(name));
            List<String> out = new ArrayList<>();
            if (lore != null) for (String l : lore) out.add(colour(l));
            meta.setLore(out);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static ItemStack item(Material material, String name, String... lore) {
        return item(material, name, Arrays.asList(lore));
    }

    /** A player head, falling back to a plain skull if the profile is unknown. */
    public static ItemStack head(OfflinePlayer of, String name, List<String> lore) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            try { skull.setOwningPlayer(of); } catch (Throwable ignored) { }
        }
        if (meta != null) {
            meta.setDisplayName(colour(name));
            List<String> out = new ArrayList<>();
            if (lore != null) for (String l : lore) out.add(colour(l));
            meta.setLore(out);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static Material material(String name, Material fallback) {
        if (name == null || name.isBlank()) return fallback;
        try {
            return Material.valueOf(name.toUpperCase(Locale.ROOT).trim());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Layout                                                            */
    /* ------------------------------------------------------------------ */

    public static Inventory chest(int rows, String title) {
        return Bukkit.createInventory(null, Math.max(1, Math.min(6, rows)) * 9, colour(title));
    }

    /**
     * The same chest, but with an {@link org.bukkit.inventory.InventoryHolder}
     * attached.
     *
     * <p>Prefer this over {@link #chest(int, String)} for any menu that has to
     * recognise its own clicks. Bukkit's {@code Inventory} exposes no title, so
     * a holder is the only reliable way to tell one staff menu from another
     * when both are open, and title-string comparison breaks the moment a title
     * contains a colour code or is longer than the client renders.
     */
    public static Inventory chest(int rows, String title,
                                  org.bukkit.inventory.InventoryHolder holder) {
        return Bukkit.createInventory(holder, Math.max(1, Math.min(6, rows)) * 9, colour(title));
    }

    /**
     * Leaves empty slots empty.
     *
     * <p>This used to pack every free slot with a black glass pane. It was kept
     * as a method rather than deleted so that a border can be reinstated in one
     * place if wanted, and so the call sites read the same either way.
     *
     * <p>Panes cost nothing to render but they do cost the reader: a menu with
     * nine buttons and forty-five panes takes longer to scan than one with nine
     * buttons and empty space, and every pane is another click target that has
     * to be caught and cancelled.
     */
    public static void fill(Inventory inv) {
        // Intentionally empty. See the note above.
    }

    /** The old behaviour, if a menu genuinely wants a solid backdrop. */
    public static void fillWithPanes(Inventory inv, Material pane) {
        ItemStack filler = item(pane == null ? Material.BLACK_STAINED_GLASS_PANE : pane, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }
    }

    /** Border only: edges panelled, middle left clear for content. */
    /**
     * The house frame: dark edge, <b>orange corners</b>.
     *
     * <p>Applied through one method so every menu matches. Orange corners give
     * the eye four anchors and make a 6-row chest read as a framed panel rather
     * than a wall of slots.
     */
    public static final Material FRAME_EDGE = Material.BLACK_STAINED_GLASS_PANE;
    public static final Material FRAME_CORNER = Material.ORANGE_STAINED_GLASS_PANE;

    /** Red through to gold, used for every menu title. */
    public static String titleGradient(String label) {
        return gradient(label, "#ff3b3b", "#ffd700");
    }

    /** Title gradient with an explicit pair, for a menu with its own palette. */
    public static String titleGradient(String label, String from, String to) {
        return gradient(label, from, to);
    }

    /** Creates a chest with the house frame and title gradient already applied. */
    public static Inventory themed(int rows, String label,
                                   org.bukkit.inventory.InventoryHolder holder) {
        Inventory inv = chest(rows, titleGradient(label), holder);
        framed(inv, FRAME_EDGE, FRAME_CORNER);
        return inv;
    }

    /** Creates a chest with the house frame, for menus that need no holder. */
    public static Inventory themed(int rows, String label) {
        return themed(rows, label, null);
    }

    /**
     * A full interior row of alternating grey panes, used as a visual limit
     * between sections of a menu.
     *
     * <p>Deliberately not part of the border: it sits between content rows, so a
     * 4-row menu reads as two stacked panels rather than one undifferentiated
     * grid, and the eye can tell at a glance which buttons belong together.
     */
    public static void limitRow(Inventory inv, int row, Material first, Material second) {
        Material a = first == null ? Material.LIGHT_GRAY_STAINED_GLASS_PANE : first;
        Material b = second == null ? Material.GRAY_STAINED_GLASS_PANE : second;
        ItemStack left = item(a, " ");
        ItemStack right = item(b, " ");
        for (int c = 1; c < 8; c++) {
            int slot = row * 9 + c;
            if (inv.getItem(slot) == null) inv.setItem(slot, c % 2 == 0 ? right : left);
        }
    }

    /**
     * The slots strictly inside the border, in reading order.
     *
     * <p>Content must never occupy the outer ring. A button sitting on the
     * frame reads as part of the decoration, and the frame is the one thing
     * that makes the menu's shape legible at a glance. This is the single
     * definition of "inside", so every menu agrees on where the frame ends.
     */
    public static List<Integer> interiorSlots(int size) {
        List<Integer> slots = new ArrayList<>();
        int rows = size / 9;
        for (int r = 1; r < rows - 1; r++) {
            for (int c = 1; c < 8; c++) {
                slots.add(r * 9 + c);
            }
        }
        return slots;
    }

    /** One page of interior slots, clamped to what is available. */
    public static List<Integer> interiorPage(int size, int page, int perPage) {
        List<Integer> all = interiorSlots(size);
        int from = Math.max(0, page) * perPage;
        if (from >= all.size()) return List.of();
        return new ArrayList<>(all.subList(from, Math.min(all.size(), from + perPage)));
    }

    /**
     * The last interior row, used for navigation.
     *
     * <p>Kept inside the border like everything else, so a 6-row menu gets 21
     * content slots across three rows and 7 navigation slots on the fourth.
     */
    public static List<Integer> lastInteriorRow(int size) {
        List<Integer> slots = new ArrayList<>();
        int rows = size / 9;
        int r = rows - 2;
        for (int c = 1; c < 8; c++) slots.add(r * 9 + c);
        return slots;
    }

    /**
     * Draws the border ring and leaves every interior slot empty.
     *
     * <p>Order matters: call this before placing items, or it will overwrite
     * anything already sitting on the frame.
     */
    public static void frame(Inventory inv, Material edge, Material corner) {
        framed(inv, edge, corner);
    }

    public static void border(Inventory inv, Material paneMaterial) {
        ItemStack pane = item(paneMaterial, " ");
        int rows = inv.getSize() / 9;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < 9; c++) {
                boolean edge = r == 0 || r == rows - 1 || c == 0 || c == 8;
                int slot = r * 9 + c;
                if (edge && inv.getItem(slot) == null) inv.setItem(slot, pane);
            }
        }
    }

    /**
     * Border with accent corners: edges in one glass, the four corners in
     * another. Reads as a frame rather than a backdrop.
     */
    public static void framed(Inventory inv, Material edge, Material corner) {
        ItemStack edgePane = item(edge, " ");
        ItemStack cornerPane = item(corner, " ");
        int rows = inv.getSize() / 9;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < 9; c++) {
                boolean isEdge = r == 0 || r == rows - 1 || c == 0 || c == 8;
                if (!isEdge) continue;
                int slot = r * 9 + c;
                if (inv.getItem(slot) != null) continue;
                boolean isCorner = (r == 0 || r == rows - 1) && (c == 0 || c == 8);
                inv.setItem(slot, isCorner ? cornerPane : edgePane);
            }
        }
    }

    /** Alternating two-glass backdrop on empty slots. */
    public static void checkerFill(Inventory inv, Material first, Material second) {
        ItemStack a = item(first == null ? Material.BLACK_STAINED_GLASS_PANE : first, " ");
        ItemStack b = item(second == null ? Material.GRAY_STAINED_GLASS_PANE : second, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) != null) continue;
            inv.setItem(i, ((i / 9 + i % 9) % 2 == 0) ? a : b);
        }
    }

    /**
     * Lore progress bar, e.g. {@code ████████░░░░ 8/12}. Fraction is clamped
     * to 0-1; colours are &amp;-code strings.
     */
    public static String progressBar(double fraction, int width, String filledColour,
                                     String emptyColour, String current, String target) {
        double f = Math.max(0.0D, Math.min(1.0D, fraction));
        int w = Math.max(4, Math.min(30, width));
        int filled = (int) Math.round(f * w);
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < filled; i++) bar.append('█');
        String head = bar.toString();
        bar.setLength(0);
        for (int i = filled; i < w; i++) bar.append('█');
        return (filledColour == null ? "&a" : filledColour) + head
                + (emptyColour == null ? "&8" : emptyColour) + bar
                + " &7" + current + "&8/&7" + target;
    }

    /**
     * Enchant-glint without enchantment text, for "ready to claim" items.
     * Returns the same stack for chaining.
     */
    public static ItemStack glow(ItemStack stack) {
        if (stack == null) return null;
        try {
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return stack;
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            stack.setItemMeta(meta);
        } catch (Throwable ignored) { }
        return stack;
    }

    public static ItemStack back(String where) {
        return item(Material.ARROW, "&7" + ARROW + " &fBack",
                "&8Return to " + where + ".");
    }

    public static ItemStack close() {
        return item(Material.BARRIER, "&c" + ARROW + " &fClose", "&8Shut this menu.");
    }

    /* ------------------------------------------------------------------ */
    /*  The information card                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Builds the standard card.
     *
     * @param description  short subtitle, may be blank
     * @param information  body lines, printed under "Information:"
     * @param warning      the red line at the bottom, may be null
     * @param clickAction  what CLICK does, e.g. "to open"
     */
    public static List<String> infoLore(String description, List<String> information,
                                        String warning, String clickAction) {
        List<String> lore = new ArrayList<>();
        lore.add("&8Description:");
        if (description != null && !description.isBlank()) lore.add("&7" + description);
        lore.add(" ");
        lore.add("&#00ff00Information:");
        if (information != null) for (String line : information) lore.add("&f" + line);
        lore.add(" ");
        if (warning != null && !warning.isBlank()) {
            lore.add("&c" + warning);
            lore.add(" ");
        }
        lore.add("&e" + ARROW + "&e&l&n CLICK &r&e" + (clickAction == null ? "to open" : clickAction));
        return lore;
    }

    /** Convenience: the whole card as one item. */
    public static ItemStack card(Material material, String title, String description,
                                 List<String> information, String warning, String clickAction) {
        return item(material, title, infoLore(description, information, warning, clickAction));
    }

    /* ------------------------------------------------------------------ */
    /*  Misc                                                              */
    /* ------------------------------------------------------------------ */

    /** Wraps a long sentence onto lore-sized lines without splitting words. */
    public static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (line.length() > 0 && line.length() + word.length() + 1 > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    public static void sound(Player p, org.bukkit.Sound sound, float pitch) {
        try { p.playSound(p.getLocation(), sound, 0.7F, pitch); } catch (Throwable ignored) { }
    }
}
