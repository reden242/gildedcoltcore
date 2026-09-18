package com.coltcore.core.modules;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks sign content per player and detects nearby/broken signs.
 *
 * <p>Signals a player as advertising-relevant when they have broken or
 * placed signs containing address-like text in their recent history,
 * and checks whether a message is written near those signs.</p>
 */
public final class SignContextTracker implements Listener {

    private static final int MAX_SIGN_HISTORY = 20;
    private static final int MAX_BROKEN_SIGNS = 30;
    private static final int NEARBY_SIGN_RADIUS = 10;
    private static final long SIGN_TTL_MS = 600_000L;

    private final Map<UUID, Deque<SignEntry>> signHistory = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<BrokenSignEntry>> brokenSigns = new ConcurrentHashMap<>();
    private final Set<String> trackedSignTexts = ConcurrentHashMap.newKeySet();

    record SignEntry(String text, Location location, long timestamp) {
        SignEntry(String text, Location location) {
            this(text, location, System.currentTimeMillis());
        }
    }

    record BrokenSignEntry(String text, Location location, long timestamp) {
        BrokenSignEntry(String text, Location location) {
            this(text, location, System.currentTimeMillis());
        }
    }

    public SignContextTracker() {}

    public void enable() {}

    public void disable() {
        signHistory.clear();
        brokenSigns.clear();
        trackedSignTexts.clear();
    }

    public void clear(UUID playerId) {
        signHistory.remove(playerId);
        brokenSigns.remove(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        String[] lines = event.getLines();
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            if (line != null && !line.isBlank()) text.append(line).append(" ");
        }
        if (text.length() == 0) return;
        String normalized = ChatGuardModule.normalise(text.toString().trim());
        UUID id = player.getUniqueId();
        Deque<SignEntry> history = signHistory.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(new SignEntry(normalized, event.getBlock().getLocation()));
            while (history.size() > MAX_SIGN_HISTORY) history.removeFirst();
        }
        if (looksLikeAddress(normalized)) {
            trackedSignTexts.add(normalized);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!(block.getState() instanceof Sign)) return;
        Sign sign = (Sign) block.getState();
        String text = sign.getLine(0) + " " + sign.getLine(1) + " " + sign.getLine(2) + " " + sign.getLine(3);
        String normalized = ChatGuardModule.normalise(text.trim());
        UUID id = event.getPlayer().getUniqueId();
        Deque<BrokenSignEntry> broken = brokenSigns.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (broken) {
            broken.addLast(new BrokenSignEntry(normalized, block.getLocation()));
            while (broken.size() > MAX_BROKEN_SIGNS) broken.removeFirst();
        }
    }

    /**
     * Checks whether the player has a history of address-like signs.
     */
    public boolean hasAddressSignHistory(UUID playerId) {
        Deque<SignEntry> history = signHistory.get(playerId);
        if (history == null) return false;
        synchronized (history) {
            for (SignEntry e : history) {
                if (looksLikeAddress(e.text)) return true;
            }
        }
        return false;
    }

    /**
     * Checks whether the player has broken signs recently.
     */
    public boolean hasBrokenAddressSigns(UUID playerId) {
        Deque<BrokenSignEntry> broken = brokenSigns.get(playerId);
        if (broken == null) return false;
        synchronized (broken) {
            long now = System.currentTimeMillis();
            for (BrokenSignEntry e : broken) {
                if (now - e.timestamp < SIGN_TTL_MS && looksLikeAddress(e.text)) return true;
            }
        }
        return false;
    }

    /**
     * Returns the number of address-like signs within radius of the given location.
     */
    public int nearbyAddressSigns(Location loc) {
        if (loc == null) return 0;
        int count = 0;
        String worldName = loc.getWorld().getName();
        synchronized (trackedSignTexts) {
            for (String text : trackedSignTexts) {
                if (!looksLikeAddress(text)) continue;
            }
        }
        return count;
    }

    /**
     * Checks if a book is being placed near a sign that the player controls.
     */
    public boolean nearPlayerSign(Location loc, UUID playerId) {
        Deque<SignEntry> history = signHistory.get(playerId);
        if (history == null || loc == null) return false;
        synchronized (history) {
            for (SignEntry e : history) {
                if (e.location.getWorld().getName().equals(loc.getWorld().getName())
                        && e.location.distance(loc) < NEARBY_SIGN_RADIUS) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the recent sign texts that look like addresses.
     */
    public Set<String> recentAddressSigns(UUID playerId) {
        Set<String> result = new HashSet<>();
        Deque<SignEntry> history = signHistory.get(playerId);
        if (history == null) return result;
        synchronized (history) {
            for (SignEntry e : history) {
                if (looksLikeAddress(e.text)) result.add(e.text);
            }
        }
        return result;
    }

    /**
     * Returns whether a broken sign with address-like text exists nearby.
     */
    public boolean hasNearbyBrokenAddressSign(Location loc, UUID playerId) {
        Deque<BrokenSignEntry> broken = brokenSigns.get(playerId);
        if (broken == null || loc == null) return false;
        synchronized (broken) {
            long now = System.currentTimeMillis();
            for (BrokenSignEntry e : broken) {
                if (now - e.timestamp < SIGN_TTL_MS
                        && e.location.getWorld().getName().equals(loc.getWorld().getName())
                        && e.location.distance(loc) < NEARBY_SIGN_RADIUS
                        && looksLikeAddress(e.text)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean looksLikeAddress(String text) {
        if (text == null || text.length() < 3) return false;
        return text.contains(".") || text.contains("/") || text.contains(":")
                || text.matches(".*\\b(ip|server|mc|join|visit|come|play)\\b.*");
    }

    /**
     * Returns count of tracked signs (placed + broken) near location.
     */
    public int signProximityCount(Location loc) {
        if (loc == null || loc.getWorld() == null) return 0;
        int count = 0;
        long now = System.currentTimeMillis();
        String world = loc.getWorld().getName();
        for (Deque<SignEntry> history : signHistory.values()) {
            synchronized (history) {
                for (SignEntry e : history) {
                    if (now - e.timestamp < SIGN_TTL_MS
                            && e.location != null && e.location.getWorld() != null
                            && e.location.getWorld().getName().equals(world)
                            && e.location.distance(loc) < NEARBY_SIGN_RADIUS) {
                        count++;
                    }
                }
            }
        }
        for (Deque<BrokenSignEntry> broken : brokenSigns.values()) {
            synchronized (broken) {
                for (BrokenSignEntry e : broken) {
                    if (now - e.timestamp < SIGN_TTL_MS
                            && e.location != null && e.location.getWorld() != null
                            && e.location.getWorld().getName().equals(world)
                            && e.location.distance(loc) < NEARBY_SIGN_RADIUS) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
