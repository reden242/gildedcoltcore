package com.coltcore.core.modules;

import com.coltcore.core.SchedulerCompat;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * RedstoneUnstaler - detects and repairs redstone components that are stuck
 * powered when nothing around them supplies power any more.
 *
 * <h2>The failure mode</h2>
 * Chunk unloads, aggressive tick optimizations and plugin-induced update
 * skipping all share one side effect: a powered component occasionally never
 * receives the update that would have turned it off. The torch stays lit with
 * nothing feeding it, the repeater stays locked on against a dead line, dust
 * holds its power level with the circuit around it long gone. Farms half-run,
 * and the only vanilla cure is somebody walking past and jostling it by hand.
 *
 * <h2>How it works</h2>
 * This module watches {@link BlockRedstoneEvent} and remembers the exact set
 * of blocks that were ACTIVATED (received current &gt; 0). Only that set is
 * ever examined - never a global world scan - so the cost stays proportional
 * to how much redstone is actually cycling, not to how much is built.
 *
 * Every 3 seconds the tracked set is swept on the main thread. A block whose
 * persisted state says "on" while no neighbouring source can explain the
 * state is stale, and is handled per {@code redstone-unstaler.action}:
 * <ul>
 *   <li>{@code sync} (default, farm-safe) - the component's own block data is
 *       re-applied with physics running plus a nudge to its watched
 *       neighbours. Vanilla re-resolves the whole circuit around it, which is
 *       precisely the update pass it missed, and legit farms just keep
 *       farming untouched.</li>
 *   <li>{@code turn-off} - the component's powered flag (or wire power level)
 *       is forced to off with physics running. Stricter; a live-but-obscure
 *       source may get flicked once before the next real update re-powers it,
 *       so this is for servers that would rather kill ghosts than wake them.</li>
 * </ul>
 *
 * Comparators are NEVER force-flipped: their input can be a container, which
 * reads as "no source" to a geometric scan and would eat every item sorter on
 * the server. In {@code turn-off} mode comparators are silently promoted to
 * {@code sync}.
 *
 * <h2>Budget</h2>
 * At most {@code max-per-sweep} tracked blocks are worked per 3-second pass
 * and the set itself is capped, so a hostile lag machine cannot turn the
 * repair pass into a lag machine. Unloaded chunks are skipped rather than
 * force-loaded.
 */
public final class RedstoneUnstaler implements Listener {

    private static final Set<Material> WATCHED = EnumSet.of(
            Material.REDSTONE_WIRE,
            Material.REDSTONE_TORCH,
            Material.REDSTONE_WALL_TORCH,
            Material.REPEATER,
            Material.COMPARATOR,
            Material.REDSTONE_LAMP);

    private final JavaPlugin plugin;

    private boolean enabled = true;
    private long periodTicks = 60L;           // every 3 seconds
    private int maxPerSweep = 512;
    private int maxTracked = 65536;
    private Action action = Action.SYNC;
    private boolean logRepairs = false;

    private enum Action { SYNC, TURN_OFF }

    /**
     * "world;x;y;z" of blocks that currently carry current. Linked so the
     * sweep walks them oldest-first; insertion-ordered LRU meets the spam cap.
     */
    private final Set<String> tracked = new LinkedHashSet<>();

    private SchedulerCompat.ManagedTask task;

    public RedstoneUnstaler(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        readConfig();
        this.tracked.clear();
        if (this.task != null) { this.task.cancel(); this.task = null; }
        if (!this.enabled) return;
        this.task = SchedulerCompat.timer(this.plugin, this::sweep,
                this.periodTicks, this.periodTicks);
        this.plugin.getLogger().info("[Redstone] Unstaler enabled: sweep every "
                + (this.periodTicks / 20.0D) + "s, budget " + this.maxPerSweep
                + ", action " + this.action.name().toLowerCase(java.util.Locale.ROOT) + ".");
    }

    public void reload() { enable(); }

    public void disable() {
        if (this.task != null) { this.task.cancel(); this.task = null; }
        this.tracked.clear();
    }

    public boolean isEnabled() { return this.enabled; }

    private void readConfig() {
        ConfigurationSection c = this.plugin.getConfig().getConfigurationSection("redstone-unstaler");
        if (c == null) { this.enabled = false; return; }
        this.enabled = c.getBoolean("enabled", true);
        this.periodTicks = Math.max(20L, c.getLong("period-ticks", 60L));
        this.maxPerSweep = Math.max(8, c.getInt("max-per-sweep", 512));
        this.maxTracked = Math.max(1024, c.getInt("max-tracked", 65536));
        this.action = "turn-off".equalsIgnoreCase(c.getString("action", "sync"))
                ? Action.TURN_OFF : Action.SYNC;
        this.logRepairs = c.getBoolean("log-repairs", false);
    }

    /* ------------------------------------------------------------------ */
    /*  Tracking                                                            */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        if (!this.enabled) return;
        Block b = event.getBlock();
        if (!WATCHED.contains(b.getType())) return;
        String key = key(b);
        if (event.getNewCurrent() > 0) {
            if (this.tracked.add(key) && this.tracked.size() > this.maxTracked) {
                Iterator<String> it = this.tracked.iterator();
                // Sacrifice the oldest fifth so growth is amortized, not stop-the-world.
                for (int i = this.maxTracked / 5; i > 0 && it.hasNext(); i--) {
                    it.next();
                    it.remove();
                }
            }
        } else {
            // A clean turn-off frees the bookkeeping slot immediately.
            this.tracked.remove(key);
        }
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ';' + b.getX() + ';' + b.getY() + ';' + b.getZ();
    }

    private static Block resolve(String key) {
        String[] p = key.split(";");
        if (p.length != 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        int x, y, z;
        try {
            x = Integer.parseInt(p[1]);
            y = Integer.parseInt(p[2]);
            z = Integer.parseInt(p[3]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null; // never force-load
        return w.getBlockAt(x, y, z);
    }

    /* ------------------------------------------------------------------ */
    /*  Sweep + repair                                                      */
    /* ------------------------------------------------------------------ */

    private void sweep() {
        if (this.tracked.isEmpty()) return;
        int budget = this.maxPerSweep;
        Iterator<String> it = this.tracked.iterator();
        while (it.hasNext() && budget-- > 0) {
            String key = it.next();
            Block b = resolve(key);
            if (b == null) {
                // World gone or malformed key: drop per-capita churn.
                it.remove();
                continue;
            }
            if (!WATCHED.contains(b.getType())) {   // player replaced the block
                it.remove();
                continue;
            }
            if (!isPoweredOn(b)) {
                // Turned off by other means since tracking; nothing to fix.
                it.remove();
                continue;
            }
            if (!isStale(b)) continue;              // healthy, stay watched
            repair(b);
            it.remove();
        }
    }

    private boolean isPoweredOn(Block b) {
        BlockData d = b.getBlockData();
        if (d instanceof AnaloguePowerable ap) return ap.getPower() > 0;
        if (d instanceof Lightable l) return l.isLit();
        if (d instanceof Powerable p) return p.isPowered();
        return false;
    }

    private void repair(Block b) {
        Material type = b.getType();
        if (type == Material.COMPARATOR
                || this.action == Action.SYNC) {
            // The farm-safe path. Re-asserting the same data with physics on
            // forces vanilla to re-resolve the component and its neighbours -
            // exactly the update pass it lost.
            b.setBlockData(b.getBlockData(), true);
            for (BlockFace f : BlockFace.values()) {
                if (f == BlockFace.SELF) continue;
                Block n = b.getRelative(f);
                if (WATCHED.contains(n.getType())) continue; // handled on its own turn
                if (n.getType().isSolid()) {
                    // Weakly-powered solids carry the signal past the component;
                    // re-asserting their data re-runs propagation from them too.
                    n.setBlockData(n.getBlockData(), true);
                }
            }
        } else {
            BlockData d = b.getBlockData();
            if (d instanceof AnaloguePowerable ap) ap.setPower(0);
            else if (d instanceof Lightable l) l.setLit(false);
            else if (d instanceof Powerable p) p.setPowered(false);
            b.setBlockData(d, true);
        }
        if (this.logRepairs) {
            this.plugin.getLogger().info("[Unstaler] " + key(b) + " (" + type + ") "
                    + (this.action == Action.SYNC ? "re-synced" : "turned off"));
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Staleness                                                          */
    /* ------------------------------------------------------------------ */

    private boolean isStale(Block b) {
        return switch (b.getType()) {
            case REDSTONE_WIRE, REDSTONE_LAMP -> !this.hasPowerSource(b);
            case REDSTONE_TORCH -> {
                // A floor torch must be OFF whenever the block above it (its
                // attachment) carries power. Lit with a powered attachment is stale.
                boolean attached = b.getRelative(BlockFace.UP).isBlockPowered();
                yield attached;
            }
            case REDSTONE_WALL_TORCH -> {
                if (!(b.getBlockData() instanceof Directional dir)) yield false;
                Block attach = b.getRelative(dir.getFacing().getOppositeFace());
                yield attach.isBlockPowered();
            }
            case REPEATER, COMPARATOR -> {
                if (!(b.getBlockData() instanceof Directional dir)) yield false;
                Block input = b.getRelative(dir.getFacing().getOppositeFace());
                yield !this.providesPower(input, b);
            }
            default -> false;
        };
    }

    /** True when ANY neighbour plausibly explains the component being powered. */
    private boolean hasPowerSource(Block b) {
        for (BlockFace f : BlockFace.values()) {
            if (f == BlockFace.SELF) continue;
            Block n = b.getRelative(f);
            if (this.providesPower(n, b)) return true;
            // Weakly powered opaque solids feed mechanisms (lamps/pistons) and
            // the dust stacked on them and beside them.
            if (n.getType().isSolid() && !WATCHED.contains(n.getType())
                    && (n.isBlockPowered() || n.isBlockIndirectlyPowered())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code src} can deliver current into {@code dst}. Deliberately
     * conservative - when in doubt we say yes, so farms are never condemned
     * by an edge case the scan did not understand.
     */
    private boolean providesPower(Block src, Block dst) {
        Material t = src.getType();
        if (t == Material.REDSTONE_BLOCK) return true;
        BlockData d = src.getBlockData();

        // Levers/buttons/pressure plates/hooks light everything they touch.
        if (d instanceof Switch sw && sw.isPowered()) return true;

        // Observers fire out of their REAR, opposite the face that watches.
        if (t == Material.OBSERVER && d instanceof Directional dir
                && d instanceof Powerable pw && pw.isPowered()) {
            return src.getRelative(dir.getFacing().getOppositeFace()).equals(dst);
        }

        // Repeaters/comparators deliver out of their facing direction.
        if (d instanceof Directional dir && d instanceof Powerable pw && pw.isPowered()) {
            return src.getRelative(dir.getFacing()).equals(dst);
        }

        // Torches power ONLY the dust/direct block stacked on their own tip.
        if ((t == Material.REDSTONE_TORCH || t == Material.REDSTONE_WALL_TORCH)
                && d instanceof Lightable l && l.isLit()) {
            return src.getRelative(BlockFace.UP).equals(dst);
        }

        // Chained dust: any live dust next to this one is indistinguishable
        // from its supplier and equally valid here.
        if (t == Material.REDSTONE_WIRE && d instanceof AnaloguePowerable ap) {
            return ap.getPower() > 0;
        }

        // Opaque block transmitting (strong or weak).
        if (t.isSolid()) {
            return src.isBlockPowered() || src.isBlockIndirectlyPowered();
        }

        return false;
    }

    /* ------------------------------------------------------------------ */

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", this.enabled);
        out.put("tracked", this.tracked.size());
        out.put("action", this.action.name().toLowerCase(java.util.Locale.ROOT));
        out.put("period_ticks", this.periodTicks);
        out.put("max_per_sweep", this.maxPerSweep);
        return out;
    }
}
