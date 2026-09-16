package com.coltcore.core.modules;

import com.coltcore.core.SchedulerCompat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KelpGrowthModule — kelp only grows if it was PLAYER-PLACED.
 *
 * Naturally generated / naturally grown kelp is prevented from growing any
 * further, while kelp a player planted keeps growing normally.
 *
 * How: when a player places kelp we remember the base of that column. When any
 * kelp tries to grow (BlockSpreadEvent / BlockGrowEvent), we walk down to the
 * base of the column and only allow the growth if that base is player-owned.
 *
 * Ownership is keyed to the column BASE (the lowest kelp block), so the whole
 * column is covered no matter how tall it gets. It persists to kelp-owned.yml.
 *
 * Hook-up in ColtCorePlugin#onEnable():
 *     this.kelpGrowthModule = new KelpGrowthModule(this);
 *     this.kelpGrowthModule.enable();
 *     Bukkit.getPluginManager().registerEvents((Listener)this.kelpGrowthModule, (Plugin)this);
 * and onDisable(): if (this.kelpGrowthModule != null) this.kelpGrowthModule.disable();
 */
public final class KelpGrowthModule implements Listener {

    private final JavaPlugin plugin;
    private final Set<String> playerOwnedBases = ConcurrentHashMap.newKeySet();

    private boolean enabled;
    private File dataFile;
    private volatile boolean dirty;

    public KelpGrowthModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        this.enabled = this.plugin.getConfig().getBoolean("kelp-growth-control.enabled", false);
        load();
        // Periodic save if changed (avoids disk write on every placement).
        SchedulerCompat.timerAsync(this.plugin,
                this::saveIfDirty, 20L * 120, 20L * 120);
    }

    public void reload() {
        this.enabled = this.plugin.getConfig().getBoolean("kelp-growth-control.enabled", false);
    }

    public void disable() {
        saveIfDirty();
    }

    private static boolean isKelp(Material m) {
        return m == Material.KELP || m == Material.KELP_PLANT;
    }

    /* ------------------------------------------------------------------ */
    /*  Track player-placed kelp                                          */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!this.enabled) return;
        if (!isKelp(event.getBlock().getType())) return;
        String base = key(baseOf(event.getBlock()));
        if (this.playerOwnedBases.add(base)) this.dirty = true;
    }

    /* ------------------------------------------------------------------ */
    /*  Gate growth                                                       */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (!this.enabled) return;
        // Kelp growth fires as a spread from the existing plant.
        if (!isKelp(event.getSource().getType()) && !isKelp(event.getNewState().getType())) return;
        if (!ownedColumn(event.getSource())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (!this.enabled) return;
        if (!isKelp(event.getBlock().getType()) && !isKelp(event.getNewState().getType())) return;
        if (!ownedColumn(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** True if the kelp column containing/under this block has a player-owned base. */
    private boolean ownedColumn(Block anyKelpInColumn) {
        return this.playerOwnedBases.contains(key(baseOf(anyKelpInColumn)));
    }

    /** Walk down through kelp to the lowest kelp block (the column base). */
    private Block baseOf(Block b) {
        Block cur = b;
        while (cur.getY() > cur.getWorld().getMinHeight()) {
            Block below = cur.getRelative(0, -1, 0);
            if (!isKelp(below.getType())) break;
            cur = below;
        }
        return cur;
    }

    /* ------------------------------------------------------------------ */
    /*  Persistence                                                       */
    /* ------------------------------------------------------------------ */

    private static String key(Block b) {
        return (b.getWorld() == null ? "?" : b.getWorld().getName())
                + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    private static String key(Location l) {
        return (l.getWorld() == null ? "?" : l.getWorld().getName())
                + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ();
    }

    private void load() {
        this.dataFile = new File(this.plugin.getDataFolder(), "kelp-owned.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(this.dataFile);
        this.playerOwnedBases.clear();
        this.playerOwnedBases.addAll(y.getStringList("owned"));
    }

    private void saveIfDirty() {
        if (!this.dirty || this.dataFile == null) return;
        this.dirty = false;
        YamlConfiguration y = new YamlConfiguration();
        y.set("owned", new java.util.ArrayList<>(new HashSet<>(this.playerOwnedBases)));
        try {
            y.save(this.dataFile);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("[KelpGrowth] Could not save kelp-owned.yml: " + ex.getMessage());
        }
    }
}
