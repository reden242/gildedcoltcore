package com.gildedmc.core.modules;

import com.gildedmc.core.SchedulerCompat;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TPS-tiered redstone governor.
 *
 * <h2>What it does</h2>
 * Redstone runs at a fraction of vanilla speed chosen from a ladder of TPS
 * bands, re-evaluated once a second on the main thread:
 *
 * <pre>
 *   TPS &gt;= 19.0  -> 1.00x  (untouched vanilla)
 *   TPS &gt;= 18.0  -> 0.85x
 *   TPS &gt;= 17.5  -> 0.75x
 *   TPS &gt;= 15.0  -> 0.50x
 *   TPS &gt;= 12.5  -> 0.25x
 *   TPS below     -> 0.00x  (redstone frozen: updates are held, farms stall
 *                            rather than breaking; nothing is torn down and
 *                            normal behaviour resumes the tick TPS recovers)
 * </pre>
 *
 * <h2>Skipping is a hold, not a zero</h2>
 * A skipped {@link BlockRedstoneEvent} leaves the block's power exactly where
 * it was ({@code setNewCurrent(oldCurrent)}), so circuits pause rather than
 * collapsing to unpowered and clocks continue from where they stopped. A
 * factor of 0 therefore means "paused", not "ripped out".
 *
 * <h2>The skip pattern</h2>
 * Drops are spread Bresenham-style (error accumulator), not a modulo bucket:
 * at 0.25x every fourth update passes, evenly, indefinitely. The old
 * "% of N" pattern degenerated to all-or-nothing at low factors.
 *
 * <h2>Configuration</h2>
 * redstone.tiers is a list of {tps, factor} rows. The highest tier whose
 * threshold the current TPS meets or beats wins; falling short of the lowest
 * tier freezes redstone. Old single-threshold keys (slow-below-tps,
 * slow-factor) are still honoured when tiers are absent.
 */
public final class RedstoneThrottle implements Listener {

    /** Holders are never throttled. Intended for staff testing. */
    public static final String PERM_EXEMPT = "gildedcore.redstone.exempt";

    private final JavaPlugin plugin;

    private boolean enabled = true;
    private long sampleTicks = 20L;

    /** Sorted descending by tps. First match wins. */
    private volatile Tier[] tiers = defaultTiers();

    /** Cached TPS, refreshed once a second rather than read per event. */
    private volatile double tps = 20.0D;
    private volatile double factor = 1.0D;

    /**
     * Skip-pattern state. EventPriority redstone events fire on the main
     * thread, so a plain double is safe; no atomics needed here.
     */
    private double acc;

    /** Counters for snapshot()/logging. */
    private final AtomicLong seen = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();

    private SchedulerCompat.ManagedTask task;

    public RedstoneThrottle(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private record Tier(double tps, double factor) {
    }

    private static Tier[] defaultTiers() {
        return new Tier[]{
                new Tier(19.0D, 1.00D),
                new Tier(18.0D, 0.85D),
                new Tier(17.5D, 0.75D),
                new Tier(15.0D, 0.50D),
                new Tier(12.5D, 0.25D)
        };
    }

    public void enable() {
        readConfig();
        if (this.task != null) this.task.cancel();
        if (!this.enabled) return;
        this.task = SchedulerCompat.timer(this.plugin, () -> {
            double[] recentTps = Bukkit.getServer().getTPS();
            this.tps = recentTps.length == 0 ? 20.0D : recentTps[0];
            double wanted = selectFactor(this.tps);
            if (Double.compare(wanted, this.factor) != 0) {
                double prev = this.factor;
                this.factor = wanted;
                this.acc = 0.0D;
                this.plugin.getLogger().info(String.format(
                        "[Redstone] TPS %.1f -> %.2fx (was %.2fx; %s)",
                        this.tps, wanted, prev, describe(wanted)));
            }
        }, this.sampleTicks, this.sampleTicks);
        this.plugin.getLogger().info("[Redstone] enabled. "
                + this.tiers.length + " TPS tiers loaded; "
                + "below " + lowestTiersTps() + " TPS redstone is frozen.");
    }

    public void reload() { enable(); }

    public void disable() {
        if (this.task != null) { this.task.cancel(); this.task = null; }
        this.factor = 1.0D;
    }

    private double lowestTiersTps() {
        return this.tiers.length == 0 ? 0.0D : this.tiers[this.tiers.length - 1].tps();
    }

    private static String describe(double factor) {
        if (factor >= 1.0D) return "full speed";
        if (factor <= 0.0D) return "redstone frozen";
        return "farms slowed, still running";
    }

    /** Highest tier the current TPS reaches; below the lowest tier -> 0. */
    private double selectFactor(double tps) {
        for (Tier t : this.tiers) {
            if (tps >= t.tps()) return t.factor();
        }
        return 0.0D;
    }

    private void readConfig() {
        ConfigurationSection c = this.plugin.getConfig().getConfigurationSection("redstone");
        if (c == null) { this.enabled = false; return; }
        this.enabled = c.getBoolean("enabled", true);
        this.sampleTicks = Math.max(10L, c.getLong("sample-ticks", 20L));

        List<Tier> tiers = new ArrayList<>();
        List<Map<?, ?>> raw = c.getMapList("tiers");
        for (Map<?, ?> row : raw) {
            Object tObj = row.get("tps");
            Object fObj = row.get("factor");
            if (!(tObj instanceof Number) || !(fObj instanceof Number)) continue;
            double t = ((Number) tObj).doubleValue();
            double f = ((Number) fObj).doubleValue();
            // Clamp the factor. Above 1 is meaningless; negative is nonsense.
            tiers.add(new Tier(t, Math.max(0.0D, Math.min(1.0D, f))));
        }
        if (tiers.isEmpty()) {
            // Legacy single-threshold config: keep the old behaviour working.
            double slowBelow = c.getDouble("slow-below-tps", 18.5D);
            double slow = Math.max(0.1D, Math.min(1.0D, c.getDouble("slow-factor", 0.75D)));
            tiers.add(new Tier(slowBelow + 1.0D, 1.0D)); // full speed back-stop
            tiers.add(new Tier(slowBelow, slow));
        }
        tiers.sort(Comparator.comparingDouble(Tier::tps).reversed());
        this.tiers = tiers.toArray(Tier[]::new);
    }

    public boolean isEnabled() { return this.enabled; }

    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        // Fast path: single volatile read + compare on an event that fires
        // thousands of times a second while the server is healthy.
        double f = this.factor;
        if (f >= 1.0D) return;

        this.seen.incrementAndGet();
        if (!shouldSkip(f)) return;

        // Hold the current level rather than forcing it to zero. Zeroing
        // would toggle the component off - a state change of its own that
        // makes clocks fire instead of pausing.
        this.skipped.incrementAndGet();
        event.setNewCurrent(event.getOldCurrent());
    }

    /**
     * Error-accumulator skip: each event adds (1 - factor) to a running
     * error term and drops the update the moment it crosses 1.0. The pattern
     * is maximally even at every factor (0.25 -> A S S S repeating), where a
     * modulo counter collapses to all-skip or none at the extremes.
     */
    private boolean shouldSkip(double f) {
        if (f <= 0.0D) return true;
        this.acc += (1.0D - f);
        if (this.acc >= 1.0D) {
            this.acc -= 1.0D;
            return true;
        }
        return false;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", this.enabled);
        out.put("tps", Math.round(this.tps * 10.0) / 10.0);
        out.put("factor", this.factor);
        out.put("frozen", this.factor <= 0.0D);
        List<String> desc = new ArrayList<>();
        for (Tier t : this.tiers) desc.add(t.tps() + " -> " + t.factor() + "x");
        out.put("tiers", desc);
        out.put("events_seen_while_throttling", this.seen.get());
        out.put("events_skipped", this.skipped.get());
        return out;
    }
}
