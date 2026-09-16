package com.coltcore.core.modules;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
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

    public static String strip(String text) {
        return ChatColor.stripColor(colour(text));
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
