package com.coltcore.core.modules;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Handles loading, choosing, and pasting fake "stash" base schematics from
 * plugins/ColtCore/stashes into the world via /spawnstash, and detects
 * players tampering with a pasted stash.
 *
 * Pasted stashes are recorded to stashes.yml, keyed by an id, together with
 * the world-space bounding box of the paste. The listeners below use that box
 * to work out which stash any break / place / interact belongs to, who found
 * it, and how long it took.
 *
 * Layout: any mix of files directly under plugins/ColtCore/stashes, in any
 * subfolders:
 *   *.schem, *.schematic   (WorldEdit formats, read natively)
 *   *.litematic            (Litematica, translated on the fly and cached)
 *
 * There are no size types and no per-type folders: every spawn picks uniformly
 * at random from everything available. The recorded "size" is derived from the
 * pasted volume (small/medium/large) for the alerts only.
 */
public final class StashModule implements Listener {

    /** Extension point for external plugins (e.g. inxsus). */
    public interface StashAlertHook {
        void onStashTamper(Player player, TamperType type, Location location, String stashId, String size);
    }

    public enum TamperType { BREAK, PLACE, INTERACT }

    private final JavaPlugin plugin;
    private final SecureRandom random = new SecureRandom();
    private final List<StashAlertHook> alertHooks = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private File stashesFolder;
    private File dataFile;
    private FileConfiguration data;

    private String webhookUrl;
    private String webhookUsername;
    private String alertPermission;
    private boolean cancelBreaks;
    private boolean airGapEnabled;
    private int airGapMargin;
    private int airGapMaxVolume;
    private String airGapAction;

    public StashModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerAlertHook(StashAlertHook hook) {
        if (hook != null) this.alertHooks.add(hook);
    }

    /* ------------------------------------------------------------------ */
    /*  Lifecycle                                                          */
    /* ------------------------------------------------------------------ */

    public void enable() {
        this.stashesFolder = new File(this.plugin.getDataFolder(), "stashes");
        if (!this.stashesFolder.exists()) {
            this.stashesFolder.mkdirs();
        }
        this.dataFile = new File(this.plugin.getDataFolder(), "stashes.yml");
        this.data = YamlConfiguration.loadConfiguration(this.dataFile);
        this.loadConfig();
    }

    public void reload() {
        this.enable();
    }

    public void disable() {
        if (this.data == null || this.dataFile == null) return;
        try {
            this.data.save(this.dataFile);
        } catch (IOException e) {
            this.plugin.getLogger().warning("Failed to save stashes.yml on disable: " + e.getMessage());
        }
    }

    private void loadConfig() {
        ConfigurationSection root = this.plugin.getConfig().getConfigurationSection("stash");
        if (root == null) {
            this.webhookUrl = "";
            this.webhookUsername = "ColtCore Anti-Cheat";
            this.alertPermission = "coltcore.spawnstash";
            this.cancelBreaks = true;
            this.airGapEnabled = true;
            this.airGapMargin = 3;
            this.airGapMaxVolume = 60;
            this.airGapAction = "warn";
            return;
        }
        this.webhookUrl = root.getString("webhook-url", "");
        this.webhookUsername = root.getString("webhook-username", "ColtCore Anti-Cheat");
        this.alertPermission = root.getString("alert-permission", "coltcore.spawnstash");
        this.cancelBreaks = root.getBoolean("cancel-breaks", true);

        ConfigurationSection gap = root.getConfigurationSection("air-gap");
        if (gap == null) {
            this.airGapEnabled = true;
            this.airGapMargin = 3;
            this.airGapMaxVolume = 60;
            this.airGapAction = "warn";
        } else {
            this.airGapEnabled = gap.getBoolean("enabled", true);
            this.airGapMargin = Math.max(1, gap.getInt("margin", 3));
            this.airGapMaxVolume = Math.max(1, gap.getInt("max-gap-volume", 60));
            this.airGapAction = gap.getString("action", "warn").toLowerCase(Locale.ROOT);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Command                                                            */
    /* ------------------------------------------------------------------ */

    public boolean command(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(this.color("&cThis command can only be used by a player."));
            return true;
        }
        Player player = (Player) sender;

        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            List<File> files = this.listAllSchematics();
            if (files.isEmpty()) {
                sender.sendMessage(this.color("&cNo schematics in &fstashes/&c."));
            } else {
                sender.sendMessage(this.color("&7Available (" + files.size() + "): &f" + this.namesJoined(files)));
            }
            return true;
        }

        boolean force = args.length > 0 && args[args.length - 1].equalsIgnoreCase("force");

        // /spawnstash [force] — no types: a uniform random pick from everything
        // available, whatever the format.
        File chosenFile = this.randomSchematic();
        if (chosenFile == null) {
            sender.sendMessage(this.color("&cNo schematics in &fstashes/&c. Drop .schem, .schematic or .litematic files in there."));
            return true;
        }

        Location targetLoc = this.resolveTargetLocation(player);

        Clipboard clipboard;
        try {
            clipboard = this.readClipboard(chosenFile);
        } catch (Exception ex) {
            sender.sendMessage(this.color("&cFailed to read stash schematic: " + ex.getMessage()));
            return true;
        }

        // World-space bounding box the paste will occupy.
        BlockVector3 origin = clipboard.getOrigin();
        BlockVector3 cMin = clipboard.getRegion().getMinimumPoint();
        BlockVector3 cMax = clipboard.getRegion().getMaximumPoint();
        int bx = targetLoc.getBlockX(), by = targetLoc.getBlockY(), bz = targetLoc.getBlockZ();

        int minX = bx + (cMin.getX() - origin.getX());
        int minY = by + (cMin.getY() - origin.getY());
        int minZ = bz + (cMin.getZ() - origin.getZ());
        int maxX = bx + (cMax.getX() - origin.getX());
        int maxY = by + (cMax.getY() - origin.getY());
        int maxZ = bz + (cMax.getZ() - origin.getZ());

        // --- Air gap check on the region the stash will occupy -------------
        if (this.airGapEnabled && !force) {
            AirGapResult gap = this.scanAirGap(targetLoc.getWorld(),
                    minX, minY, minZ, maxX, maxY, maxZ, this.airGapMargin, this.airGapMaxVolume);
            if (gap.exceeded()) {
                if ("abort".equals(this.airGapAction)) {
                    sender.sendMessage(this.color("&c&lStash aborted. &7Open air pocket of &e"
                            + gap.volume() + " &7blocks touching the paste region"
                            + (gap.exposedToSky() ? " &7(&copen to sky&7)" : "") + "."));
                    sender.sendMessage(this.color("&7This stash would be visible without X-ray. "
                            + "Add &fforce &7to place anyway."));
                    return true;
                }
                sender.sendMessage(this.color("&e&lWarning: &7Air pocket of &e" + gap.volume()
                        + " &7blocks touching this stash"
                        + (gap.exposedToSky() ? " &7(&copen to sky&7)" : "")
                        + " &7- it may be legitimately visible."));
            }
        }

        try {
            this.pasteClipboard(clipboard, targetLoc);
        } catch (Exception ex) {
            sender.sendMessage(this.color("&cFailed to paste stash schematic: " + ex.getMessage()));
            this.plugin.getLogger().warning("Failed to paste stash '" + chosenFile.getName() + "': " + ex.getMessage());
            return true;
        }

        String stashId = this.recordStash(chosenFile, sizeLabel(clipboard), targetLoc, player,
                minX, minY, minZ, maxX, maxY, maxZ);

        sender.sendMessage(this.color("&aStash Summoned &7(&f" + this.stripExtension(chosenFile.getName())
                + "&7, &f" + sizeLabel(clipboard) + "&7)"));
        sender.sendMessage(this.color("&7ID: &f" + stashId
                + " &8| &7region &f" + minX + "," + minY + "," + minZ
                + " &7to &f" + maxX + "," + maxY + "," + maxZ));
        return true;
    }

    /* ------------------------------------------------------------------ */
    /*  Detection                                                          */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        String stashId = this.stashAt(event.getBlock().getLocation());
        if (stashId == null) return;
        if (this.cancelBreaks) event.setCancelled(true);
        this.handleTamper(event.getPlayer(), TamperType.BREAK, event.getBlock().getLocation(), stashId);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        String stashId = this.stashAt(event.getBlock().getLocation());
        if (stashId == null) return;
        this.handleTamper(event.getPlayer(), TamperType.PLACE, event.getBlock().getLocation(), stashId);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        String stashId = this.stashAt(event.getClickedBlock().getLocation());
        if (stashId == null) return;
        // Opening the chest is the point of the honeypot - log it, allow it.
        this.handleTamper(event.getPlayer(), TamperType.INTERACT, event.getClickedBlock().getLocation(), stashId);
    }

    /** Returns the id of the active stash containing this location, or null. */
    private String stashAt(Location loc) {
        if (this.data == null || loc.getWorld() == null) return null;
        ConfigurationSection active = this.data.getConfigurationSection("active");
        if (active == null) return null;

        String world = loc.getWorld().getName();
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();

        for (String id : active.getKeys(false)) {
            ConfigurationSection s = active.getConfigurationSection(id);
            if (s == null || !world.equals(s.getString("world"))) continue;
            if (x < s.getInt("min-x") || x > s.getInt("max-x")) continue;
            if (y < s.getInt("min-y") || y > s.getInt("max-y")) continue;
            if (z < s.getInt("min-z") || z > s.getInt("max-z")) continue;
            return id;
        }
        return null;
    }

    private void handleTamper(Player player, TamperType type, Location loc, String stashId) {
        // Staff who can place stashes are never flagged by their own traps.
        if (player.hasPermission("coltcore.spawnstash")) return;

        String path = "active." + stashId;
        String size = this.data.getString(path + ".size", "unknown");
        long placedAt = this.data.getLong(path + ".placed-at", 0L);
        boolean firstFind = !this.data.getBoolean(path + ".found", false);

        if (firstFind) {
            this.data.set(path + ".found", true);
            this.data.set(path + ".found-by", player.getUniqueId().toString());
            this.data.set(path + ".found-by-name", player.getName());
            this.data.set(path + ".found-at", System.currentTimeMillis());
            this.data.set(path + ".found-via", type.name());
            this.saveData();
        }

        String verb;
        String suspicion;
        switch (type) {
            case BREAK -> { verb = "broke a block in"; suspicion = "Suspected X-Ray"; }
            case PLACE -> { verb = "placed a block in"; suspicion = "Suspected X-Ray / griefing"; }
            default -> { verb = "opened a container in"; suspicion = "Suspected Chest ESP"; }
        }

        String coords = loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
        String worldName = loc.getWorld() == null ? "unknown" : loc.getWorld().getName();
        String age = placedAt > 0 ? humanDuration(System.currentTimeMillis() - placedAt) : "unknown";

        String line1 = this.color("&c&l[ANTI-CHEAT] &e" + player.getName() + " &f" + verb
                + " a decoy stash! &7(" + suspicion + ")");
        String line2 = this.color("&7  at &f" + coords + " &7in &f" + worldName
                + " &8| &7stash &f" + stashId + " &7(&f" + size + "&7)"
                + (firstFind ? " &8| &7found after &f" + age : " &8| &7repeat"));

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(this.alertPermission)) {
                staff.sendMessage(line1);
                staff.sendMessage(line2);
            }
        }
        this.plugin.getLogger().warning("[Stash] " + player.getName() + " " + verb
                + " stash " + stashId + " (" + size + ") at " + coords + " in " + worldName);

        for (StashAlertHook hook : this.alertHooks) {
            try {
                hook.onStashTamper(player, type, loc, stashId, size);
            } catch (Exception ex) {
                this.plugin.getLogger().warning("[Stash] Alert hook threw: " + ex.getMessage());
            }
        }

        if (firstFind) {
            this.sendWebhook(player, type, verb, suspicion, coords, worldName, stashId, size, age);
        }
    }

    private static String humanDuration(long millis) {
        long s = millis / 1000L;
        if (s < 60) return s + "s";
        long m = s / 60L;
        if (m < 60) return m + "m " + (s % 60) + "s";
        long h = m / 60L;
        if (h < 24) return h + "h " + (m % 60) + "m";
        return (h / 24L) + "d " + (h % 24) + "h";
    }

    /* ------------------------------------------------------------------ */
    /*  Webhook                                                            */
    /* ------------------------------------------------------------------ */

    private void sendWebhook(Player player, TamperType type, String verb, String suspicion,
                             String coords, String worldName, String stashId, String size, String age) {
        if (this.webhookUrl == null || this.webhookUrl.isBlank()) return;

        int colour;
        switch (type) {
            case BREAK -> colour = 0xE74C3C;
            case PLACE -> colour = 0xE67E22;
            default -> colour = 0xF1C40F;
        }

        String json = "{"
                + "\"username\":\"" + esc(this.webhookUsername) + "\","
                + "\"embeds\":[{"
                + "\"title\":\"Stash found - " + esc(suspicion) + "\","
                + "\"color\":" + colour + ","
                + "\"fields\":["
                + field("Player", player.getName(), true)
                + "," + field("UUID", player.getUniqueId().toString(), true)
                + "," + field("Action", verb, true)
                + "," + field("Location", coords + " (" + worldName + ")", false)
                + "," + field("Stash", stashId + " - " + size, true)
                + "," + field("Time to find", age, true)
                + "],"
                + "\"timestamp\":\"" + Instant.now().toString() + "\""
                + "}]}";

        com.coltcore.core.SchedulerCompat.runAsync(this.plugin, () -> {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(this.webhookUrl))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> res = this.http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() >= 300) {
                    this.plugin.getLogger().warning("[Stash] Webhook failed: HTTP "
                            + res.statusCode() + " " + res.body());
                }
            } catch (Exception ex) {
                this.plugin.getLogger().warning("[Stash] Webhook error: " + ex.getMessage());
            }
        });
    }

    private static String field(String name, String value, boolean inline) {
        return "{\"name\":\"" + esc(name) + "\",\"value\":\"" + esc(value) + "\",\"inline\":" + inline + "}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        // Quotes and backslashes first (they delimit), then every remaining
        // ISO control char goes - a tab or NUL in a player name would
        // otherwise produce invalid JSON and silently kill the webhook.
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                .replaceAll("\\p{Cntrl}", "");
    }

    /* ------------------------------------------------------------------ */
    /*  Air gap scanning                                                   */
    /* ------------------------------------------------------------------ */

    private record AirGapResult(boolean exceeded, int volume, boolean exposedToSky) {}

    /**
     * Flood-fills air starting from the shell just outside the paste region.
     * A large connected air volume, or one open to the sky, means the stash
     * would be visible without X-ray - which would make any detection a false
     * positive.
     */
    private AirGapResult scanAirGap(World world, int minX, int minY, int minZ,
                                    int maxX, int maxY, int maxZ,
                                    int margin, int maxVolume) {
        if (world == null) return new AirGapResult(false, 0, false);

        Set<Long> visited = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();

        int sMinX = minX - margin, sMaxX = maxX + margin;
        int sMinY = minY - margin, sMaxY = maxY + margin;
        int sMinZ = minZ - margin, sMaxZ = maxZ + margin;

        // Seed from the shell immediately surrounding the region.
        for (int x = sMinX; x <= sMaxX; x++) {
            for (int y = sMinY; y <= sMaxY; y++) {
                for (int z = sMinZ; z <= sMaxZ; z++) {
                    boolean insideRegion = x >= minX && x <= maxX
                            && y >= minY && y <= maxY
                            && z >= minZ && z <= maxZ;
                    if (insideRegion) continue;
                    queue.add(new int[]{x, y, z});
                }
            }
        }

        int volume = 0;
        boolean sky = false;

        while (!queue.isEmpty() && volume <= maxVolume) {
            int[] pos = queue.poll();
            int x = pos[0], y = pos[1], z = pos[2];

            if (x < sMinX || x > sMaxX || y < sMinY || y > sMaxY || z < sMinZ || z > sMaxZ) continue;
            if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) continue;
            if (!visited.add(blockKey(x, y, z))) continue;

            Block b = world.getBlockAt(x, y, z);
            if (!isPassable(b)) continue;

            volume++;
            if (world.getHighestBlockYAt(x, z) <= y) sky = true;

            for (int[] d : NEIGHBOURS) {
                queue.add(new int[]{x + d[0], y + d[1], z + d[2]});
            }
        }

        return new AirGapResult(volume > maxVolume || sky, volume, sky);
    }

    private static final int[][] NEIGHBOURS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private static boolean isPassable(Block b) {
        Material m = b.getType();
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR || m == Material.WATER;
    }

    private static long blockKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    /* ------------------------------------------------------------------ */
    /*  Schematics                                                         */
    /* ------------------------------------------------------------------ */

    private Clipboard readClipboard(File schematicFile) throws IOException {
        String lower = schematicFile.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".litematic")) {
            // Translated on the fly and cached inside LitematicReader.
            return LitematicReader.read(schematicFile, name -> {
                try {
                    return com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(
                            org.bukkit.Bukkit.createBlockData(name));
                } catch (IllegalArgumentException ex) {
                    return null;
                }
            });
        }
        ClipboardFormat format = ClipboardFormats.findByFile(schematicFile);
        if (format == null) {
            throw new IOException("Unrecognized schematic format for file " + schematicFile.getName());
        }
        try (ClipboardReader reader = format.getReader(new FileInputStream(schematicFile))) {
            return reader.read();
        }
    }

    /**
     * Pastes the clipboard at the target location. The schematic's saved
     * origin/offset is respected, so its relative origin ends up at targetLoc.
     */
    private void pasteClipboard(Clipboard clipboard, Location targetLoc)
            throws com.sk89q.worldedit.WorldEditException {
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(targetLoc.getWorld());
        BlockVector3 to = BlockVector3.at(targetLoc.getBlockX(), targetLoc.getBlockY(), targetLoc.getBlockZ());

        try (EditSession editSession = WorldEdit.getInstance().newEditSession(weWorld)) {
            Operation operation = new ClipboardHolder(clipboard)
                    .createPaste(editSession)
                    .to(to)
                    .ignoreAirBlocks(false)
                    .build();
            Operations.complete(operation);
        }
    }

    private String recordStash(File schematicFile, String size, Location targetLoc, Player placer,
                               int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        String stashId = this.generateStashId();
        String path = "active." + stashId;

        this.data.set(path + ".schematic", this.stripExtension(schematicFile.getName()));
        this.data.set(path + ".size", size);
        this.data.set(path + ".placed-by", placer.getUniqueId().toString());
        this.data.set(path + ".placed-by-name", placer.getName());
        this.data.set(path + ".placed-at", System.currentTimeMillis());
        this.data.set(path + ".world", targetLoc.getWorld().getName());
        this.data.set(path + ".x", targetLoc.getBlockX());
        this.data.set(path + ".y", targetLoc.getBlockY());
        this.data.set(path + ".z", targetLoc.getBlockZ());
        this.data.set(path + ".min-x", minX);
        this.data.set(path + ".min-y", minY);
        this.data.set(path + ".min-z", minZ);
        this.data.set(path + ".max-x", maxX);
        this.data.set(path + ".max-y", maxY);
        this.data.set(path + ".max-z", maxZ);
        this.data.set(path + ".found", false);

        this.saveData();
        return stashId;
    }

    private String generateStashId() {
        String id;
        do {
            id = Long.toHexString(this.random.nextLong() & Long.MAX_VALUE);
        } while (this.data.contains("active." + id));
        return id;
    }

    private Location resolveTargetLocation(Player player) {
        Block targetBlock = player.getTargetBlockExact(100);
        if (targetBlock != null && !targetBlock.getType().isAir()) {
            return targetBlock.getLocation();
        }
        Location eye = player.getEyeLocation();
        Location loc = eye.clone().add(eye.getDirection().normalize().multiply(2));
        loc.setY(Math.floor(loc.getY()));
        return loc.getBlock().getLocation();
    }

    /** Size label derived from pasted volume, for alerts only. */
    private static String sizeLabel(Clipboard clipboard) {
        try {
            com.sk89q.worldedit.math.BlockVector3 min =
                    clipboard.getRegion().getMinimumPoint();
            com.sk89q.worldedit.math.BlockVector3 max =
                    clipboard.getRegion().getMaximumPoint();
            long volume = (long) (max.getX() - min.getX() + 1)
                    * (max.getY() - min.getY() + 1)
                    * (max.getZ() - min.getZ() + 1);
            if (volume < 1_000L) return "small";
            if (volume < 8_000L) return "medium";
            return "large";
        } catch (Throwable ignored) {
            return "custom";
        }
    }

    /** Every schematic under the stashes root, any subfolder, all formats. */
    private List<File> listAllSchematics() {
        List<File> out = new ArrayList<>();
        if (this.stashesFolder == null || !this.stashesFolder.isDirectory()) return out;
        Deque<File> queue = new ArrayDeque<>();
        queue.add(this.stashesFolder);
        while (!queue.isEmpty()) {
            File dir = queue.removeFirst();
            File[] found = dir.listFiles();
            if (found == null) continue;
            for (File file : found) {
                if (file.isDirectory()) {
                    queue.addLast(file);
                } else {
                    String lower = file.getName().toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".schem") || lower.endsWith(".schematic")
                            || lower.endsWith(".litematic")) {
                        out.add(file);
                    }
                }
            }
        }
        out.sort(Comparator.comparing(File::getName));
        return out;
    }

    /** Uniform random pick from everything available. */
    private File randomSchematic() {
        List<File> files = this.listAllSchematics();
        if (files.isEmpty()) return null;
        return files.get(this.random.nextInt(files.size()));
    }

    private String namesJoined(List<File> files) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(this.stripExtension(files.get(i).getName()));
        }
        return sb.toString();
    }

    private String stripExtension(String name) {
        return name.replaceFirst("(?i)\\.schematic$", "").replaceFirst("(?i)\\.schem$", "")
                .replaceFirst("(?i)\\.litematic$", "");
    }

    /** Tab completion: list and force. Names are gone — spawns are random. */
    public List<String> schematicNames() {
        return new ArrayList<>(List.of("list", "force"));
    }

    private void saveData() {
        try {
            this.data.save(this.dataFile);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("Could not save stashes.yml: " + ex.getMessage());
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
