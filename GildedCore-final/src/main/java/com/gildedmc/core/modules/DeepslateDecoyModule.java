package com.gildedmc.core.modules;

import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DeepslateDecoyModule — floods the world with randomly-rotated deepslate to
 * blind stash/base finders that key off the AXIS of player-placed deepslate
 * (e.g. RotatedDeepslateESP / ChunkFinder in Meteor-based clients).
 *
 * Those finders assume natural deepslate is axis=Y and treat X/Z-rotated
 * deepslate as "player placed" => a base. This seeds decoy vertical columns of
 * X/Z-rotated deepslate through the natural deepslate layer, so rotated
 * deepslate appears everywhere and the finder can no longer tell a real base
 * from noise.
 *
 * SAFETY / SCOPE (read before enabling):
 *   - It ONLY overwrites natural filler blocks (stone/deepslate/tuff/andesite/
 *     diorite/granite). It never touches ores, air (caves), or any other block,
 *     so it won't fill caves, delete ores, or wreck player builds.
 *   - It works in the natural deepslate band (default y -60..0) so the decoy
 *     blocks look in-place. Deepslate axis is cosmetic — legit players won't
 *     notice; the ESP will.
 *   - It DOES permanently change terrain and sends block updates to everyone.
 *     Run it scoped (command with a radius) rather than blanketing the map.
 *
 * Hook-up in GildedCorePlugin#onEnable():
 *     this.deepslateDecoyModule = new DeepslateDecoyModule(this);
 *     this.deepslateDecoyModule.enable();
 */
public final class DeepslateDecoyModule implements Listener {

    private final JavaPlugin plugin;
    private final ThreadLocalRandom rng = ThreadLocalRandom.current();

    private boolean enabled;
    private Set<Material> replaceable;
    private int columnsPerRun;
    private int minLength, maxLength;
    private int minY, maxY;
    private boolean autoOnChunkLoad;
    private int columnsPerChunk;
    private boolean listenerRegistered;

    public DeepslateDecoyModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        loadConfig();
        if (!this.listenerRegistered) {
            this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
            this.listenerRegistered = true;
        }
    }

    public void reload() {
        loadConfig();
    }

    private void loadConfig() {
        ConfigurationSection root = this.plugin.getConfig().getConfigurationSection("deepslate-decoy");
        if (root == null) { this.enabled = false; defaults(); return; }

        this.enabled = root.getBoolean("enabled", false);
        this.columnsPerRun = Math.max(1, root.getInt("columns-per-run", 40));
        this.minLength = Math.max(1, root.getInt("min-length", 4));
        this.maxLength = Math.max(this.minLength, root.getInt("max-length", 20));
        this.minY = root.getInt("min-y", -60);
        this.maxY = root.getInt("max-y", 0);
        if (this.maxY < this.minY) { int t = this.minY; this.minY = this.maxY; this.maxY = t; }

        this.replaceable = EnumSet.noneOf(Material.class);
        for (String s : root.getStringList("replaceable")) {
            Material m = Material.matchMaterial(s.toUpperCase(Locale.ROOT));
            if (m != null) this.replaceable.add(m);
        }
        if (this.replaceable.isEmpty()) {
            this.replaceable = EnumSet.of(Material.STONE, Material.DEEPSLATE, Material.TUFF,
                    Material.ANDESITE, Material.DIORITE, Material.GRANITE);
        }

        ConfigurationSection auto = root.getConfigurationSection("auto-on-chunk-load");
        this.autoOnChunkLoad = auto != null && auto.getBoolean("enabled", false);
        this.columnsPerChunk = auto == null ? 3 : Math.max(1, auto.getInt("columns-per-chunk", 3));
    }

    private void defaults() {
        this.replaceable = EnumSet.of(Material.STONE, Material.DEEPSLATE, Material.TUFF,
                Material.ANDESITE, Material.DIORITE, Material.GRANITE);
        this.columnsPerRun = 40; this.minLength = 4; this.maxLength = 20;
        this.minY = -60; this.maxY = 0; this.autoOnChunkLoad = false; this.columnsPerChunk = 3;
    }

    /* ------------------------------------------------------------------ */
    /*  Command: /deepslatedecoy [radius]                                  */
    /* ------------------------------------------------------------------ */

    public boolean command(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cPlayers only.");
            return true;
        }
        if (!player.hasPermission("gildedcore.deepslatedecoy")) {
            player.sendMessage("§cNo permission.");
            return true;
        }
        if (!this.enabled) {
            player.sendMessage("§cDeepslate decoy is disabled in config.");
            return true;
        }
        int radius = 32;
        if (args.length > 0) {
            try { radius = Math.max(4, Math.min(256, Integer.parseInt(args[0]))); }
            catch (NumberFormatException ex) { player.sendMessage("§cUsage: /deepslatedecoy [radius]"); return true; }
        }
        Location base = player.getLocation();
        int placed = seed(base.getWorld(), base.getBlockX(), base.getBlockZ(), radius, this.columnsPerRun);
        player.sendMessage("§aSeeded §f" + this.columnsPerRun + " §adecoy columns (§f" + placed
                + " §arotated-deepslate blocks) within §f" + radius + " §ablocks.");
        return true;
    }

    /* ------------------------------------------------------------------ */
    /*  Auto seeding on chunk generate/load                               */
    /* ------------------------------------------------------------------ */

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!this.enabled || !this.autoOnChunkLoad) return;
        if (!event.isNewChunk()) return; // only freshly generated chunks
        int cx = event.getChunk().getX() << 4;
        int cz = event.getChunk().getZ() << 4;
        seed(event.getWorld(), cx + 8, cz + 8, 7, this.columnsPerChunk);
    }

    /* ------------------------------------------------------------------ */
    /*  Core                                                              */
    /* ------------------------------------------------------------------ */

    /** Places {@code columns} decoy rotated-deepslate columns; returns blocks changed. */
    private int seed(World world, int centerX, int centerZ, int radius, int columns) {
        if (world == null) return 0;
        int changed = 0;
        int floor = Math.max(world.getMinHeight(), this.minY);
        int ceil = Math.min(world.getMaxHeight() - 1, this.maxY);
        if (ceil < floor) return 0;

        for (int c = 0; c < columns; c++) {
            int x = centerX + this.rng.nextInt(-radius, radius + 1);
            int z = centerZ + this.rng.nextInt(-radius, radius + 1);
            int topY = ceil == floor ? floor : this.rng.nextInt(floor, ceil + 1);
            int len = this.rng.nextInt(this.minLength, this.maxLength + 1);
            Axis axis = this.rng.nextBoolean() ? Axis.X : Axis.Z; // rotated, never Y

            for (int y = topY; y > topY - len && y >= floor; y--) {
                Block b = world.getBlockAt(x, y, z);
                if (!this.replaceable.contains(b.getType())) continue; // skip ores, air, builds
                b.setType(Material.DEEPSLATE, false);
                BlockData bd = b.getBlockData();
                if (bd instanceof Orientable o) {
                    o.setAxis(axis);
                    b.setBlockData(o, false);
                }
                changed++;
            }
        }
        return changed;
    }
}
