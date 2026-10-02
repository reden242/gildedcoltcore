package com.coltcore.core.modules;

import com.coltcore.core.SchedulerCompat;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
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
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * RedstoneDestaler - chunk-granularity repair for redstone that arrives
 * already powered and therefore never trips an update event.
 *
 * <h2>Why this exists next to RedstoneUnstaler</h2>
 * {@code RedstoneUnstaler} is event-driven: it tracks blocks that were
 * ACTIVATED (received current &gt; 0 via {@code BlockRedstoneEvent}) and
 * sweeps that set. A component that loads pre-powered - chunk load after an
 * unclean unload, a WorldEdit/clone paste, a schematic restore, a rollback -
 * never fires the event, never enters the tracked set, and stays lit forever
 * with nothing feeding it. The Unstaler cannot see it by construction.
 *
 * <p>The Destaler closes that gap from the other side: it keeps an index of
 * chunks that contain redstone (components plus anything that outputs a
 * signal), loads them when needed, and asks of every powered component
 * whether any neighbour can explain the state. Anything powered with no
 * plausible source is stale and gets the same repair the Unstaler applies.
 * The two modules share semantics and budgets but no bookkeeping: the
 * Unstaler owns live circuits (fast, event-driven), the Destaler owns quiet
 * ghosts (chunk-driven).
 *
 * <h2>How chunks are found</h2>
 * The index has three feeds, cheapest first:
 * <ol>
 *   <li>Placement listener - a redstone block placed under a running server
 *       adds its chunk immediately.</li>
 *   <li>Persistence - the index survives restarts in
 *       {@code redstone-chunks.txt}, so nothing learned is lost.</li>
 *   <li>Discovery round-robin - a slow walk over loaded chunks finds anything
 *       the first two missed (pre-existing builds, offline edits) and adds
 *       hits to the index.</li>
 * </ol>
 * Chunks that scan with zero redstone blocks are pruned from the index, so
 * it converges to exactly the chunks that have redstone. Indexed chunks that
 * are not loaded are force-loaded without generation
 * ({@code loadChunk(x, z, false)}) on a strict per-sweep budget, scanned,
 * and left for natural unload - never held open.
 *
 * <h2>Cost control</h2>
 * Sweeps run on the main thread every {@code period-ticks} (default 5s) with
 * separate budgets for indexed chunks, force-loads, discovery chunks and
 * repairs, plus a per-chunk rescan cooldown. The scan itself is a
 * {@code getType()} walk (chunk-array lookup, no snapshots, no physics) and
 * only powered {@code WATCHED} components pay for neighbour checks.
 *
 * <p>Comparators are NEVER force-flipped, for the same reason as in the
 * Unstaler: a container input reads as "no source" to a geometric scan and
 * flipping it would eat every item sorter on the server. In
 * {@code turn-off} mode comparators are silently promoted to {@code sync}.
 */
public final class RedstoneDestaler implements Listener {

    /** Components whose powered state we repair. */
    private static final Set<Material> WATCHED = EnumSet.of(
            Material.REDSTONE_WIRE,
            Material.REDSTONE_TORCH,
            Material.REDSTONE_WALL_TORCH,
            Material.REPEATER,
            Material.COMPARATOR,
            Material.REDSTONE_LAMP);

    /**
     * Anything that outputs a redstone signal, used to (a) index chunks worth
     * scanning and (b) conservatively explain powered neighbours. Buttons and
     * pressure plates match by suffix so every wood/stone variant is covered.
     */
    private static final Set<Material> SOURCES = EnumSet.of(
            Material.REDSTONE_BLOCK,
            Material.REDSTONE_TORCH,
            Material.REDSTONE_WALL_TORCH,
            Material.REDSTONE_WIRE,
            Material.REPEATER,
            Material.COMPARATOR,
            Material.OBSERVER,
            Material.LEVER,
            Material.TRIPWIRE_HOOK,
            Material.TRAPPED_CHEST,
            Material.DAYLIGHT_DETECTOR,
            Material.TARGET,
            Material.SCULK_SENSOR,
            Material.CALIBRATED_SCULK_SENSOR,
            Material.LIGHTNING_ROD);

    private static boolean isSource(Material t) {
        if (SOURCES.contains(t)) return true;
        String n = t.name();
        return n.endsWith("_BUTTON") || n.endsWith("_PRESSURE_PLATE");
    }

    private static boolean isRedstoneRelevant(Material t) {
        return WATCHED.contains(t) || isSource(t);
    }

    private final JavaPlugin plugin;

    private boolean enabled = true;
    private long periodTicks = 100L;          // every 5 seconds
    private int maxChunksPerSweep = 6;
    private int maxForceloadsPerSweep = 1;
    private int maxRepairsPerSweep = 64;
    private long rescanCooldownTicks = 3600L; // 5 min per chunk at 5s sweeps
    private Action action = Action.SYNC;
    private boolean logRepairs = false;

    private enum Action { SYNC, TURN_OFF }

    /** "world;cx;cz" of chunks known to contain redstone, oldest first. */
    private final Set<String> redstoneChunks = new LinkedHashSet<>();

    /** Round-robin cursor per world for the discovery walk. */
    private final Map<String, Integer> cursors = new LinkedHashMap<>();

    /** "world;cx;cz" -&gt; last sweep tick a full scan ran there. */
    private final Map<String, Long> scannedAt = new LinkedHashMap<>();

    private long tickCounter = 0L;
    private int sweepsSinceSave = 0;
    private SchedulerCompat.ManagedTask task;

    public RedstoneDestaler(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        readConfig();
        this.cursors.clear();
        this.scannedAt.clear();
        loadIndex();
        if (this.task != null) { this.task.cancel(); this.task = null; }
        if (!this.enabled) return;
        this.task = SchedulerCompat.timer(this.plugin, this::sweep,
                this.periodTicks, this.periodTicks);
        this.plugin.getLogger().info("[Redstone] Destaler enabled: sweep every "
                + (this.periodTicks / 20.0D) + "s, " + this.maxChunksPerSweep
                + " chunks/sweep (+" + this.maxForceloadsPerSweep + " force-loads), budget "
                + this.maxRepairsPerSweep + ", action "
                + this.action.name().toLowerCase(Locale.ROOT)
                + ", indexed chunks " + this.redstoneChunks.size() + ".");
    }

    public void reload() { enable(); }

    public void disable() {
        if (this.task != null) { this.task.cancel(); this.task = null; }
        saveIndex();
        this.cursors.clear();
        this.scannedAt.clear();
    }

    public boolean isEnabled() { return this.enabled; }

    private void readConfig() {
        ConfigurationSection c = this.plugin.getConfig().getConfigurationSection("redstone-destaler");
        if (c == null) {
            this.enabled = false;
            this.plugin.getLogger().warning("[Destaler] No redstone-destaler section in config.yml; module disabled.");
            return;
        }
        this.enabled = c.getBoolean("enabled", true);
        this.periodTicks = Math.max(20L, c.getLong("period-ticks", 100L));
        this.maxChunksPerSweep = Math.max(1, c.getInt("max-chunks-per-sweep", 6));
        this.maxForceloadsPerSweep = Math.max(0, c.getInt("max-forceloads-per-sweep", 1));
        this.maxRepairsPerSweep = Math.max(4, c.getInt("max-repairs-per-sweep", 64));
        this.rescanCooldownTicks = Math.max(100L, c.getLong("rescan-cooldown-ticks", 3600L));
        this.action = "turn-off".equalsIgnoreCase(c.getString("action", "sync"))
                ? Action.TURN_OFF : Action.SYNC;
        this.logRepairs = c.getBoolean("log-repairs", false);
    }

    /* ------------------------------------------------------------------ */
    /*  Index: listeners + persistence + prune-on-clean                     */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!this.enabled) return;
        Block b = event.getBlockPlaced();
        if (b == null) return;
        if (!isRedstoneRelevant(b.getType())) return;
        addToIndex(b.getWorld().getName(), b.getChunk().getX(), b.getChunk().getZ());
    }

    private void addToIndex(String world, int cx, int cz) {
        if (this.redstoneChunks.add(world + ';' + cx + ';' + cz)
                && this.redstoneChunks.size() > 65536) {
            Iterator<String> it = this.redstoneChunks.iterator();
            for (int i = 65536 / 5; i > 0 && it.hasNext(); i--) {
                it.next();
                it.remove();
            }
        }
    }

    private File indexFile() {
        return new File(this.plugin.getDataFolder(), "redstone-chunks.txt");
    }

    private void loadIndex() {
        this.redstoneChunks.clear();
        try {
            File f = indexFile();
            if (!f.exists()) return;
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                String key = line.trim();
                if (!key.isEmpty() && key.split(";").length == 3) {
                    this.redstoneChunks.add(key);
                }
                if (this.redstoneChunks.size() >= 65536) break;
            }
        } catch (Exception e) {
            this.plugin.getLogger().warning("[Destaler] Could not load redstone-chunks.txt: " + e.getMessage());
        }
    }

    private void saveIndex() {
        try {
            File f = indexFile();
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            StringBuilder sb = new StringBuilder();
            for (String key : this.redstoneChunks) {
                sb.append(key).append('\n');
            }
            Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            this.plugin.getLogger().warning("[Destaler] Could not save redstone-chunks.txt: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Sweep: indexed chunks first (force-loading when needed), then a     */
    /*  small discovery walk over loaded chunks to find what the index      */
    /*  missed and prune what no longer has redstone.                       */
    /* ------------------------------------------------------------------ */

    private void sweep() {
        this.tickCounter += this.periodTicks;
        int chunkBudget = this.maxChunksPerSweep;
        int forceBudget = this.maxForceloadsPerSweep;
        int repairBudget = this.maxRepairsPerSweep;
        int repairsBefore = repairBudget;
        int chunksWalked = 0;
        int forceLoaded = 0;
        pruneCooldowns();

        // Phase A: indexed chunks, force-loading the unloaded ones on budget.
        for (String key : new ArrayList<>(this.redstoneChunks)) {
            if (chunkBudget <= 0 || repairBudget <= 0) break;
            String[] p = key.split(";");
            if (p.length != 3) {
                this.redstoneChunks.remove(key);
                continue;
            }
            World world = Bukkit.getWorld(p[0]);
            if (world == null) {
                this.redstoneChunks.remove(key);
                continue;
            }
            int cx, cz;
            try {
                cx = Integer.parseInt(p[1]);
                cz = Integer.parseInt(p[2]);
            } catch (NumberFormatException e) {
                this.redstoneChunks.remove(key);
                continue;
            }
            if (!this.cooldownElapsed(key)) continue;
            boolean loaded = world.isChunkLoaded(cx, cz);
            if (!loaded) {
                if (forceBudget <= 0) continue;
                boolean ok;
                try {
                    // No generation: only chunks that already exist on disk.
                    ok = world.loadChunk(cx, cz, false);
                } catch (Exception e) {
                    continue;
                }
                if (!ok || !world.isChunkLoaded(cx, cz)) continue;
                forceBudget--;
                forceLoaded++;
            }
            chunkBudget--;
            Chunk chunk = world.getChunkAt(cx, cz);
            chunksWalked++;
            repairBudget = this.scanChunk(chunk, repairBudget, true);
            this.scannedAt.put(key, this.tickCounter);
        }

        // Phase B: discovery round-robin over loaded chunks.
        int discoveryBudget = Math.min(2, chunkBudget);
        for (World world : Bukkit.getWorlds()) {
            if (discoveryBudget <= 0 || repairBudget <= 0) break;
            Chunk[] loaded;
            try {
                loaded = world.getLoadedChunks();
            } catch (Exception e) {
                continue;
            }
            if (loaded.length == 0) continue;
            int cursor = this.cursors.getOrDefault(world.getName(), 0);
            int walked = 0;
            while (walked < loaded.length && discoveryBudget > 0 && repairBudget > 0) {
                Chunk chunk = loaded[(cursor + walked) % loaded.length];
                walked++;
                String key = chunkKey(chunk);
                if (this.redstoneChunks.contains(key) || !this.cooldownElapsed(key)) continue;
                discoveryBudget--;
                chunkBudget--;
                int before = repairBudget;
                chunksWalked++;
                repairBudget = this.scanChunk(chunk, repairBudget, false);
                this.scannedAt.put(key, this.tickCounter);
                if (before != repairBudget || this.chunkHasRedstone(chunk)) {
                    addToIndex(world.getName(), chunk.getX(), chunk.getZ());
                }
            }
            this.cursors.put(world.getName(), (cursor + walked) % Math.max(1, loaded.length));
        }

        if (++this.sweepsSinceSave >= 12) {
            this.sweepsSinceSave = 0;
            saveIndex();
        }

        if (this.logRepairs) {
            int repaired = repairsBefore - repairBudget;
            this.plugin.getLogger().info("[Destaler] sweep: walked " + chunksWalked
                    + " chunks (" + forceLoaded + " force-loaded), repaired " + repaired
                    + ", indexed " + this.redstoneChunks.size() + ".");
        }
    }

    private static String chunkKey(Chunk chunk) {
        return chunk.getWorld().getName() + ';' + chunk.getX() + ';' + chunk.getZ();
    }

    private boolean cooldownElapsed(String key) {
        Long last = this.scannedAt.get(key);
        return last == null || this.tickCounter - last >= this.rescanCooldownTicks;
    }

    private void pruneCooldowns() {
        if (this.scannedAt.size() < 8192) return;
        Iterator<Map.Entry<String, Long>> it = this.scannedAt.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (this.tickCounter - e.getValue() >= this.rescanCooldownTicks * 4L) it.remove();
        }
    }

    /**
     * Walks one chunk column. Repairs powered components with no source.
     * When {@code prune} is true and the column holds no redstone at all, its
     * index entry is dropped so the index converges to redstone-bearing
     * chunks. Returns the remaining repair budget.
     */
    private int scanChunk(Chunk chunk, int repairBudget, boolean prune) {
        World world = chunk.getWorld();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        boolean foundRedstone = false;
        for (int x = 0; x < 16 && repairBudget > 0; x++) {
            for (int z = 0; z < 16 && repairBudget > 0; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block b;
                    try {
                        b = world.getBlockAt(baseX + x, y, baseZ + z);
                    } catch (Exception e) {
                        continue;
                    }
                    Material type = b.getType();
                    if (type == Material.AIR) continue;
                    if (!isRedstoneRelevant(type)) continue;
                    foundRedstone = true;
                    if (!WATCHED.contains(type)) continue;
                    if (!isPoweredOn(b)) continue;
                    if (!isStale(b)) continue;
                    repair(b);
                    repairBudget--;
                    if (repairBudget <= 0) break;
                }
            }
        }
        if (prune && !foundRedstone) {
            this.redstoneChunks.remove(chunkKey(chunk));
        }
        return repairBudget;
    }

    /** Cheap presence check used by discovery before promoting to the index. */
    private boolean chunkHasRedstone(Chunk chunk) {
        World world = chunk.getWorld();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        // Sample every 2nd block in each axis: presence, not completeness.
        // A full pass already ran in scanChunk; this is only a fast re-check
        // for the prune/promote decision on partial-budget exits.
        for (int x = 0; x < 16; x += 2) {
            for (int z = 0; z < 16; z += 2) {
                for (int y = minY; y <= maxY; y += 2) {
                    try {
                        if (isRedstoneRelevant(world.getBlockAt(baseX + x, y, baseZ + z).getType())) {
                            return true;
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /*  Staleness - same conservative semantics as RedstoneUnstaler: when   */
    /*  in doubt a neighbour counts as a source, so live farms are never    */
    /*  condemned by an edge case the scan did not understand.              */
    /* ------------------------------------------------------------------ */

    private boolean isPoweredOn(Block b) {
        BlockData d;
        try {
            d = b.getBlockData();
        } catch (Exception e) {
            return false;
        }
        if (d instanceof AnaloguePowerable ap) return ap.getPower() > 0;
        if (d instanceof Lightable l) return l.isLit();
        if (d instanceof Powerable p) return p.isPowered();
        return false;
    }

    private boolean isStale(Block b) {
        try {
            return switch (b.getType()) {
                // Dust power only flows downhill: a neighbour dust at equal or
                // lower power cannot be the source, which is what stops two
                // ghost dusts vouching for each other. Weak power never feeds
                // dust either, so only strongly-powered solids count.
                case REDSTONE_WIRE -> !this.wireHasSource(b);
                case REDSTONE_LAMP -> !this.hasPowerSource(b);
                case REDSTONE_TORCH -> {
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
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Whether any neighbour can explain this dust's power level. Vanilla
     * rules, strictly applied: dust at power P needs a strong source or a
     * neighbour dust above P. Equal-or-lower dust and weakly-powered solids
     * are not sources, however suspicious that looks - it is precisely the
     * shape of a ghost.
     */
    private boolean wireHasSource(Block b) {
        int myPower;
        try {
            if (!(b.getBlockData() instanceof AnaloguePowerable ap)) return true;
            myPower = ap.getPower();
        } catch (Exception e) {
            return true;
        }
        if (myPower <= 0) return true;
        for (BlockFace f : BlockFace.values()) {
            if (f == BlockFace.SELF) continue;
            Block n;
            try {
                n = b.getRelative(f);
            } catch (Exception e) {
                continue;
            }
            Material t;
            try {
                t = n.getType();
            } catch (Exception e) {
                continue;
            }
            if (t == Material.REDSTONE_BLOCK) return true;
            BlockData d;
            try {
                d = n.getBlockData();
            } catch (Exception e) {
                continue;
            }
            if (d instanceof Switch sw && sw.isPowered()) return true;
            if (t == Material.OBSERVER && d instanceof Directional dir
                    && d instanceof Powerable pw && pw.isPowered()
                    && n.getRelative(dir.getFacing().getOppositeFace()).equals(b)) {
                return true;
            }
            if (d instanceof Directional dir && d instanceof Powerable pw && pw.isPowered()
                    && n.getRelative(dir.getFacing()).equals(b)) {
                return true;
            }
            if ((t == Material.REDSTONE_TORCH || t == Material.REDSTONE_WALL_TORCH)
                    && d instanceof Lightable l && l.isLit()
                    && n.getRelative(BlockFace.UP).equals(b)) {
                return true;
            }
            if (t == Material.REDSTONE_WIRE && d instanceof AnaloguePowerable ap
                    && ap.getPower() > myPower) {
                return true;
            }
            if (t.isSolid() && !WATCHED.contains(t)) {
                try {
                    // Strong only: weak power lights mechanisms, never dust.
                    if (n.isBlockPowered()) return true;
                } catch (Exception e) {
                    // ignore
                }
            }
        }
        return false;
    }

    /** True when ANY neighbour plausibly explains the component being powered. */
    private boolean hasPowerSource(Block b) {
        for (BlockFace f : BlockFace.values()) {
            if (f == BlockFace.SELF) continue;
            Block n;
            try {
                n = b.getRelative(f);
            } catch (Exception e) {
                continue;
            }
            if (this.providesPower(n, b)) return true;
            try {
                if (n.getType().isSolid() && !WATCHED.contains(n.getType())
                        && (n.isBlockPowered() || n.isBlockIndirectlyPowered())) {
                    return true;
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return false;
    }

    /**
     * Whether {@code src} can deliver current into {@code dst}. Deliberately
     * conservative - when in doubt we say yes.
     */
    private boolean providesPower(Block src, Block dst) {
        Material t;
        try {
            t = src.getType();
        } catch (Exception e) {
            return false;
        }
        if (t == Material.REDSTONE_BLOCK) return true;
        BlockData d;
        try {
            d = src.getBlockData();
        } catch (Exception e) {
            return false;
        }

        if (d instanceof Switch sw && sw.isPowered()) return true;

        if (t == Material.OBSERVER && d instanceof Directional dir
                && d instanceof Powerable pw && pw.isPowered()) {
            return src.getRelative(dir.getFacing().getOppositeFace()).equals(dst);
        }

        if (d instanceof Directional dir && d instanceof Powerable pw && pw.isPowered()) {
            return src.getRelative(dir.getFacing()).equals(dst);
        }

        if ((t == Material.REDSTONE_TORCH || t == Material.REDSTONE_WALL_TORCH)
                && d instanceof Lightable l && l.isLit()) {
            return src.getRelative(BlockFace.UP).equals(dst);
        }

        if (t == Material.REDSTONE_WIRE && d instanceof AnaloguePowerable ap) {
            return ap.getPower() > 0;
        }

        // Daylight detectors, trapped chests, pressure plates, target blocks,
        // sculk sensors and lightning rods all expose their output through
        // the analogue/powerable interfaces above when active.
        if (isSource(t) && (d instanceof AnaloguePowerable a2 ? a2.getPower() > 0
                : d instanceof Powerable p2 && p2.isPowered())) {
            return true;
        }

        if (t.isSolid()) {
            try {
                return src.isBlockPowered() || src.isBlockIndirectlyPowered();
            } catch (Exception e) {
                return false;
            }
        }

        return false;
    }

    private void repair(Block b) {
        Material type = b.getType();
        try {
            if (type == Material.COMPARATOR || this.action == Action.SYNC) {
                b.setBlockData(b.getBlockData(), true);
                for (BlockFace f : BlockFace.values()) {
                    if (f == BlockFace.SELF) continue;
                    Block n = b.getRelative(f);
                    if (WATCHED.contains(n.getType())) continue;
                    if (n.getType().isSolid()) {
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
        } catch (Exception e) {
            return;
        }
        if (this.logRepairs) {
            this.plugin.getLogger().info("[Destaler] " + b.getWorld().getName()
                    + ';' + b.getX() + ';' + b.getY() + ';' + b.getZ() + " (" + type + ") "
                    + (this.action == Action.SYNC ? "re-synced" : "turned off"));
        }
    }

    /* ------------------------------------------------------------------ */

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", this.enabled);
        out.put("action", this.action.name().toLowerCase(Locale.ROOT));
        out.put("period_ticks", this.periodTicks);
        out.put("max_chunks_per_sweep", this.maxChunksPerSweep);
        out.put("max_forceloads_per_sweep", this.maxForceloadsPerSweep);
        out.put("max_repairs_per_sweep", this.maxRepairsPerSweep);
        out.put("indexed_chunks", this.redstoneChunks.size());
        return out;
    }
}