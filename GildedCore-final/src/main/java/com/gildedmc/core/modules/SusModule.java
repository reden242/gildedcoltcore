package com.gildedmc.core.modules;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sus addon: one consolidated log of every anticheat flag on the
 * server, viewable with {@code /sus} (alias {@code /suspicious}).
 *
 * <h2>Sources</h2>
 * Kratos is hooked through its own API events
 * ({@code PlayerFlagEvent}, reflected so the API jar is never a hard
 * dependency). Everything else is scraped from console output, because
 * MLSAC, BaritoneRemover, Grounded, GrimAC and AngleGuard announce their
 * flags by logging:
 * <ul>
 *   <li>AngleGuard — {@code FURY AG > mtyri AnchorAuraA (1/20)}</li>
 *   <li>GrimAC, MLSAC, BaritoneRemover, Grounded, Kratos — keyword lines
 *       with the player and check extracted best-effort</li>
 * </ul>
 *
 * <h2>What happens per flag</h2>
 * The entry (who, what check, which anticheat, violations, when, the raw
 * line) is appended to {@code sus.yml} (bounded), kept in a live ring for
 * the GUI, and broadcast to staff holding the alert permission. Nothing is
 * punished, muted or kicked — this addon only watches and records.
 */
public final class SusModule implements Listener {

    /** Most entries kept live for the GUI. The file keeps more. */
    static final int LIVE_CAP = 54;
    /** Most entries retained in sus.yml. */
    static final int FILE_CAP = 500;

    /** One recorded flag. */
    public record Entry(long time, String source, String player, String check,
                        String violations, String line) { }

    /** AngleGuard: {@code FURY AG > mtyri AnchorAuraA (1/20)}. */
    private static final Pattern ANGLE =
            Pattern.compile("(?i)\\b([A-Z]+)\\s+AG\\s*>\\s*(\\S+)\\s+([A-Za-z]+)\\s*\\((\\d+)\\s*/\\s*(\\d+)\\)");

    private final JavaPlugin plugin;
    private Handler handler;
    private final Deque<Entry> live = new ArrayDeque<>();
    private File dataFile;
    private boolean enabled = true;
    private String alertPermission = "gildedcore.sus.alerts";
    private boolean kratosHooked = false;

    public SusModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        readConfig();
        detach();
        loadFile();
        if (this.enabled) {
            this.handler = new Handler() {
                @Override public void publish(LogRecord record) {
                    if (record == null || record.getMessage() == null) return;
                    scan(record.getMessage());
                }
                @Override public void flush() { }
                @Override public void close() { }
            };
            Logger.getLogger("").addHandler(this.handler);
            hookKratos();
            this.plugin.getLogger().info("[Sus] watching anticheat output"
                    + (this.kratosHooked ? " (Kratos API hooked)" : " (Kratos API absent)"));
        }
    }

    public void reload() {
        enable();
    }

    public void disable() {
        detach();
    }

    private void detach() {
        if (this.handler != null) {
            try {
                Logger.getLogger("").removeHandler(this.handler);
            } catch (Throwable ignored) { }
            this.handler = null;
        }
    }

    private void readConfig() {
        ConfigurationSection c = this.plugin.getConfig().getConfigurationSection("sus");
        if (c == null) {
            this.enabled = true;
            return;
        }
        this.enabled = c.getBoolean("enabled", true);
        this.alertPermission = c.getString("alert-permission", "gildedcore.sus.alerts");
    }

    /* ------------------------------------------------------------------ */
    /*  Kratos API hook (reflective, no hard dependency)                  */
    /* ------------------------------------------------------------------ */

    private void hookKratos() {
        this.kratosHooked = false;
        final Class<? extends Event> eventClass;
        try {
            eventClass = Class.forName("me.blazezstudio.minecraft.api.event.PlayerFlagEvent")
                    .asSubclass(Event.class);
        } catch (Throwable ignored) {
            return;
        }
        try {
            EventExecutor executor = (listener, event) -> {
                try {
                    recordKratos(event);
                } catch (Throwable ignored) {
                    // A sus hook must never break Kratos dispatch.
                }
            };
            Bukkit.getPluginManager().registerEvent(eventClass, this,
                    EventPriority.MONITOR, executor, this.plugin, true);
            this.kratosHooked = true;
        } catch (Throwable ignored) {
            this.kratosHooked = false;
        }
    }

    private void recordKratos(Event event) throws Exception {
        Class<?> cls = event.getClass();
        Player player = (Player) cls.getMethod("getPlayer").invoke(event);
        String check = String.valueOf(cls.getMethod("getCheckName").invoke(event));
        Object violations = cls.getMethod("getViolations").invoke(event);
        String verbose = "";
        try {
            Method verboseMethod = cls.getMethod("getVerbose");
            Object value = verboseMethod.invoke(event);
            if (value != null) verbose = String.valueOf(value);
        } catch (NoSuchMethodException ignored) { }
        if (player == null) return;
        record("Kratos", player.getName(), check,
                "vl " + violations + (verbose.isEmpty() ? "" : " " + verbose), null);
    }

    /* ------------------------------------------------------------------ */
    /*  Console scraping                                                  */
    /* ------------------------------------------------------------------ */

    private void scan(String line) {
        if (line == null || line.isBlank()) return;
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("[sus]")) return;

        Matcher angle = ANGLE.matcher(line);
        if (angle.find()) {
            record("AngleGuard", angle.group(2), angle.group(3),
                    angle.group(4) + "/" + angle.group(5), line.trim());
            return;
        }
        if (lower.contains("grim") && (lower.contains("failed") || lower.contains("flag"))) {
            Entry parsed = generic(line, "GrimAC");
            if (parsed != null) record(parsed);
            return;
        }
        if (lower.contains("mlsac")) {
            Entry parsed = generic(line, "MLSAC");
            if (parsed != null) record(parsed);
            return;
        }
        if (lower.contains("baritone")) {
            Entry parsed = generic(line, "BaritoneRemover");
            if (parsed != null) record(parsed);
            return;
        }
        if (lower.contains("grounded") && (lower.contains("flag") || lower.contains("detect")
                || lower.contains("cheat") || lower.contains("kick") || lower.contains("ban"))) {
            Entry parsed = generic(line, "Grounded");
            if (parsed != null) record(parsed);
            return;
        }
        if (lower.contains("kratos") && (lower.contains("flag") || lower.contains("cheat")
                || lower.contains("violat"))) {
            Entry parsed = generic(line, "Kratos");
            if (parsed != null) record(parsed);
        }
    }

    /**
     * Best-effort player + check extraction: the first name-like token after
     * the source keyword is the player, the first CamelCase token after that
     * is the check. Returns null when no player token is found, so ordinary
     * startup lines mentioning a plugin name are not logged.
     */
    private static Entry generic(String line, String source) {
        String text = line.replaceAll("\\[\\d{2}:\\d{2}:\\d{2}.*?\\]:\\s*", "").trim();
        Matcher tokens = Pattern.compile("[A-Za-z0-9_]{3,16}").matcher(text);
        String player = null;
        boolean pastSource = false;
        String lowerSource = source.toLowerCase(Locale.ROOT).replace("remover", "");
        while (tokens.find()) {
            String token = tokens.group();
            if (!pastSource) {
                if (token.toLowerCase(Locale.ROOT).contains(lowerSource.replace("ac", ""))) {
                    pastSource = true;
                }
                continue;
            }
            if (isNoise(token)) continue;
            player = token;
            break;
        }
        if (player == null) return null;
        String check = "flagged";
        Matcher camel = Pattern.compile("\\b[A-Z][a-z]+(?:[A-Z][a-z]+)+\\b").matcher(text);
        while (camel.find()) {
            String candidate = camel.group();
            if (!candidate.equalsIgnoreCase(player)) {
                check = candidate;
                break;
            }
        }
        return new Entry(System.currentTimeMillis(), source, player, check, "", text);
    }

    private static boolean isNoise(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        return t.equals("the") || t.equals("and") || t.equals("for") || t.equals("with")
                || t.equals("from") || t.equals("failed") || t.equals("flagged")
                || t.equals("player") || t.equals("info") || t.equals("warn");
    }

    /* ------------------------------------------------------------------ */
    /*  Recording                                                         */
    /* ------------------------------------------------------------------ */

    private synchronized void record(String source, String player, String check,
                                     String violations, String line) {
        record(new Entry(System.currentTimeMillis(), source, player, check,
                violations == null ? "" : violations,
                line == null ? (player + " " + check) : line));
    }

    private synchronized void record(Entry entry) {
        this.live.addLast(entry);
        while (this.live.size() > LIVE_CAP) this.live.removeFirst();
        appendFile(entry);
        String alert = UiKit.colour("&c[Sus] &f" + entry.player() + " &7"
                + entry.check() + " &8(&7" + entry.source()
                + (entry.violations().isEmpty() ? "" : " " + entry.violations()) + "&8)");
        for (Player staff : Bukkit.getOnlinePlayers()) {
            try {
                if (staff.hasPermission(this.alertPermission)) staff.sendMessage(alert);
            } catch (Throwable ignored) { }
        }
    }

    public synchronized List<Entry> list() {
        List<Entry> out = new ArrayList<>(this.live);
        out.sort((a, b) -> Long.compare(b.time(), a.time()));
        return out;
    }

    private void loadFile() {
        this.dataFile = new File(this.plugin.getDataFolder(), "sus.yml");
    }

    private void appendFile(Entry entry) {
        try {
            if (this.dataFile == null) loadFile();
            if (!this.dataFile.exists()) this.dataFile.createNewFile();
            List<String> lines = java.nio.file.Files.readAllLines(
                    this.dataFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            String row = entry.time() + "|" + entry.source() + "|" + entry.player()
                    + "|" + entry.check() + "|" + entry.violations().replace("|", "/")
                    + "|" + entry.line().replace("|", "/").replaceAll("\\s+", " ").trim();
            lines.add(row);
            while (lines.size() > FILE_CAP) lines.remove(0);
            java.nio.file.Files.write(this.dataFile.toPath(), lines,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // Logging must never break detection.
        }
    }

    /* ------------------------------------------------------------------ */
    /*  GUI                                                               */
    /* ------------------------------------------------------------------ */

    private static final String TITLE = "SUS LOG";

    /** Opens recent flags. View-only; nothing here punishes. */
    public void openGui(Player player) {
        Inventory inv = UiKit.chest(54 / 9, UiKit.gradient(TITLE, "#ff5d5d", "#ffb04d"));
        UiKit.framed(inv, Material.BLACK_STAINED_GLASS_PANE, Material.RED_STAINED_GLASS_PANE);
        List<Entry> entries = list();
        int slot = 10;
        for (Entry entry : entries) {
            if (slot >= 54 - 10) break;
            if ((slot + 1) % 9 == 0) slot += 2;
            if (slot >= 54 - 9) break;
            List<String> lore = new ArrayList<>();
            lore.add("&7Check: &f" + entry.check());
            lore.add("&7Source: &f" + entry.source());
            if (!entry.violations().isEmpty()) lore.add("&7VL: &f" + entry.violations());
            lore.add("&7When: &f" + ago(entry.time()));
            lore.add(" ");
            for (String wrapped : UiKit.wrap(entry.line(), 32)) lore.add("&8" + wrapped);
            inv.setItem(slot, UiKit.head(offline(entry.player()),
                    "&e" + entry.player(), lore));
            slot++;
        }
        if (entries.isEmpty()) {
            inv.setItem(22, UiKit.item(Material.LIME_WOOL, "&aNo Flags",
                    "&7No anticheat flags recorded yet."));
        }
        UiKit.checkerFill(inv, Material.BLACK_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE);
        inv.setItem(53, UiKit.close());
        player.openInventory(inv);
        UiKit.sound(player, org.bukkit.Sound.BLOCK_CHEST_OPEN, 1.2F);
    }

    private static org.bukkit.OfflinePlayer offline(String name) {
        try {
            return Bukkit.getOfflinePlayer(name == null ? "?" : name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String ago(long time) {
        long s = Math.max(0L, (System.currentTimeMillis() - time) / 1000L);
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        return (s / 3600) + "h ago";
    }

    @org.bukkit.event.EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null) return;
        if (!ChatColor.stripColor(event.getView().getTitle()).equals(TITLE)) return;
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        if (clicked.getItemMeta() == null
                || clicked.getItemMeta().getDisplayName() == null) return;
        String name = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
        if (name.contains("Close")) player.closeInventory();
    }
}
