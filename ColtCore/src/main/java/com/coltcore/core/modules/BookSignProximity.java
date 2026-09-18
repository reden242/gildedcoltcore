package com.coltcore.core.modules;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

/**
 * Checks spatial proximity between books and signs.
 *
 * <p>A book placed within {@link #PROXIMITY_RADIUS} blocks of a sign
 * is flagged as contextually suspicious — signs are permanent and
 * visible, books near them may be part of an advertising setup.</p>
 */
public final class BookSignProximity implements Listener {

    static final int PROXIMITY_RADIUS = 5;

    private final SignContextTracker signTracker;

    public BookSignProximity(SignContextTracker signTracker) {
        this.signTracker = signTracker;
    }

    public void enable() {}

    public void disable() {}

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBookEdit(PlayerEditBookEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Location loc = player.getLocation();
        if (signTracker.nearPlayerSign(loc, id)) {
            event.setCancelled(true);
            player.sendMessage(ChatGuardModule.normalise(
                    "&cCannot edit a book near a sign — this is flagged as advertising setup."));
        }
    }

    /**
     * Checks whether a book is being placed near an address-like sign.
     */
    public boolean bookNearSign(Location bookLoc, UUID playerId) {
        return signTracker.hasNearbyBrokenAddressSign(bookLoc, playerId);
    }

    /**
     * Returns the number of signs within proximity of the given location.
     */
    public int signProximityCount(Location loc) {
        if (loc == null) return 0;
        int count = 0;
        Block block = loc.getBlock();
        for (int x = -PROXIMITY_RADIUS; x <= PROXIMITY_RADIUS; x++) {
            for (int y = -PROXIMITY_RADIUS; y <= PROXIMITY_RADIUS; y++) {
                for (int z = -PROXIMITY_RADIUS; z <= PROXIMITY_RADIUS; z++) {
                    Block check = block.getWorld().getBlockAt(block.getX() + x,
                            block.getY() + y, block.getZ() + z);
                    if (check.getState() instanceof Sign) count++;
                }
            }
        }
        return count;
    }
}
