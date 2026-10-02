package com.coltcore.core.modules;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;

/**
 * Checks spatial proximity between books and signs.
 *
 * <p>A book placed within {@link #PROXIMITY_RADIUS} blocks of a sign
 * is flagged as contextually suspicious — signs are permanent and
 * visible, books near them may be part of an advertising setup.</p>
 *
 * <p>Scoring only: this class is deliberately NOT a listener. A previous
 * revision cancelled book edits on sign proximity with no advert present,
 * which violates advertising-only enforcement, so the handler was removed
 * and must not be re-added without an evidence gate.
 */
public final class BookSignProximity {

    static final int PROXIMITY_RADIUS = 5;

    private final SignContextTracker signTracker;

    public BookSignProximity(SignContextTracker signTracker) {
        this.signTracker = signTracker;
    }

    /**
     * Checks whether a book is being placed near an address-like sign.
     */
    public boolean bookNearSign(Location bookLoc, UUID playerId) {
        return signTracker.hasNearbyBrokenAddressSign(bookLoc, playerId);
    }

    /**
     * Returns the number of signs within proximity of the given location.
     *
     * <p>Type-first scan: {@code getType()} is a chunk-array lookup while
     * {@code getState()} allocates a block-state snapshot per call, so the
     * snapshot (and the instanceof) only happens for blocks that are
     * actually sign material.
     *
     * <p>Thread safety: chat screening runs on the async chat thread, where
     * touching block states throws {@code IllegalStateException} and an
     * 11x11x11 live scan per message would stall regardless. Off the main
     * thread this returns 0 and Layer 3 scores purely from the tracker-based
     * {@link #bookNearSign} check, which is async-safe.
     */
    public int signProximityCount(Location loc) {
        if (loc == null || loc.getWorld() == null) return 0;
        if (!Bukkit.isPrimaryThread()) return 0;
        World world = loc.getWorld();
        int baseX = loc.getBlockX();
        int baseY = loc.getBlockY();
        int baseZ = loc.getBlockZ();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        int count = 0;
        for (int x = -PROXIMITY_RADIUS; x <= PROXIMITY_RADIUS; x++) {
            for (int y = -PROXIMITY_RADIUS; y <= PROXIMITY_RADIUS; y++) {
                int by = baseY + y;
                if (by < minY || by > maxY) continue;
                for (int z = -PROXIMITY_RADIUS; z <= PROXIMITY_RADIUS; z++) {
                    int bx = baseX + x;
                    int bz = baseZ + z;
                    if (!world.isChunkLoaded(bx >> 4, bz >> 4)) continue;
                    Block check;
                    try {
                        check = world.getBlockAt(bx, by, bz);
                    } catch (Exception e) {
                        continue;
                    }
                    if (!org.bukkit.Tag.SIGNS.isTagged(check.getType())) continue;
                    try {
                        if (check.getState() instanceof Sign) count++;
                    } catch (Exception e) {
                        // Block entity gone (broken sign, unloaded chunk edge).
                    }
                }
            }
        }
        return count;
    }
}
