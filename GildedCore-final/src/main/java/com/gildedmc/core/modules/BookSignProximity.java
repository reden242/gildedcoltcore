package com.gildedmc.core.modules;

import java.util.UUID;

import org.bukkit.Location;
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
     */
    public int signProximityCount(Location loc) {
        if (loc == null || loc.getWorld() == null) return 0;
        int count = 0;
        Block block = loc.getBlock();
        for (int x = -PROXIMITY_RADIUS; x <= PROXIMITY_RADIUS; x++) {
            for (int y = -PROXIMITY_RADIUS; y <= PROXIMITY_RADIUS; y++) {
                for (int z = -PROXIMITY_RADIUS; z <= PROXIMITY_RADIUS; z++) {
                    Block check = block.getWorld().getBlockAt(block.getX() + x,
                            block.getY() + y, block.getZ() + z);
                    if (!org.bukkit.Tag.SIGNS.isTagged(check.getType())) continue;
                    if (check.getState() instanceof Sign) count++;
                }
            }
        }
        return count;
    }
}
