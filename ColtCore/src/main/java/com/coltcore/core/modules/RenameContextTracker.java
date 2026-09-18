package com.coltcore.core.modules;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks player rename history for context-aware anti-advert filtering.
 *
 * <p>Records every anvil rename (old name, new name, timestamp).
 * If a player repeatedly renames items with address-like or
 * advertising-relevant names, subsequent renames get a higher
 * context score.</p>
 */
public final class RenameContextTracker implements Listener {

    private static final int MAX_RENAME_HISTORY = 30;
    private static final long RENAME_TTL_MS = 600_000L;

    private final Map<UUID, Deque<RenameEntry>> renameHistory = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> renamePatterns = new ConcurrentHashMap<>();

    record RenameEntry(String oldName, String newName, long timestamp) {
        RenameEntry(String oldName, String newName) {
            this(oldName, newName, System.currentTimeMillis());
        }
    }

    public RenameContextTracker() {}

    public void enable() {}

    public void disable() {
        renameHistory.clear();
        renamePatterns.clear();
    }

    public void clear(UUID playerId) {
        renameHistory.remove(playerId);
        renamePatterns.remove(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnvilRename(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null) return;
        ItemMeta meta = result.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;
        String newName = ChatGuardModule.normalise(meta.getDisplayName());
        if (newName.isEmpty()) return;

        Player player = (Player) event.getView().getPlayer();
        UUID id = player.getUniqueId();
        Deque<RenameEntry> history = renameHistory.computeIfAbsent(id, k -> new ArrayDeque<>());

        String oldName = "";
        if (event.getView().getItem(0) != null
                && event.getView().getItem(0).hasItemMeta()
                && event.getView().getItem(0).getItemMeta().hasDisplayName()) {
            oldName = ChatGuardModule.normalise(
                    event.getView().getItem(0).getItemMeta().getDisplayName());
        }

        synchronized (history) {
            history.addLast(new RenameEntry(oldName, newName));
            while (history.size() > MAX_RENAME_HISTORY) history.removeFirst();
        }

        if (looksLikeAddress(newName) || looksLikeRenameAdvertising(newName)) {
            renamePatterns.computeIfAbsent(id, k -> new HashSet<>()).add(newName);
        }
    }

    /**
     * Returns the number of address-like renames in the player's history.
     */
    public int addressRenameCount(UUID playerId) {
        Deque<RenameEntry> history = renameHistory.get(playerId);
        if (history == null) return 0;
        synchronized (history) {
            int count = 0;
            for (RenameEntry e : history) {
                if (looksLikeAddress(e.newName)) count++;
            }
            return count;
        }
    }

    /**
     * Returns the context score for a rename: 0.0 to 1.0.
     * Higher if the player has a history of address-like renames.
     */
    public double renameContextScore(UUID playerId, String newName) {
        Deque<RenameEntry> history = renameHistory.get(playerId);
        if (history == null) return 0.0D;
        synchronized (history) {
            int addressCount = 0;
            int totalCount = 0;
            long now = System.currentTimeMillis();
            for (RenameEntry e : history) {
                if (now - e.timestamp < RENAME_TTL_MS) {
                    totalCount++;
                    if (looksLikeAddress(e.newName)) addressCount++;
                }
            }
            if (totalCount == 0) return 0.0D;
            double ratio = (double) addressCount / totalCount;
            if (ratio >= 0.6) return 0.9D;
            if (ratio >= 0.4) return 0.7D;
            if (ratio >= 0.2) return 0.5D;
            return ratio * 0.5D;
        }
    }

    /**
     * Returns whether the new name is similar to a previous rename.
     */
    public boolean isRepeatedRename(UUID playerId, String newName) {
        Set<String> patterns = renamePatterns.get(playerId);
        if (patterns == null) return false;
        synchronized (patterns) {
            for (String p : patterns) {
                if (similarity(p, newName) >= 0.75D) return true;
            }
        }
        return false;
    }

    /**
     * Returns the player's recent rename texts.
     */
    public Set<String> recentRenames(UUID playerId) {
        Set<String> result = new HashSet<>();
        Deque<RenameEntry> history = renameHistory.get(playerId);
        if (history == null) return result;
        synchronized (history) {
            for (RenameEntry e : history) {
                result.add(e.newName);
            }
        }
        return result;
    }

    private static boolean looksLikeAddress(String text) {
        if (text == null || text.length() < 3) return false;
        return text.contains(".") || text.contains("/") || text.contains(":")
                || text.matches(".*\\b(ip|server|mc|join|visit|come|play)\\b.*");
    }

    private static boolean looksLikeRenameAdvertising(String text) {
        if (text == null || text.length() < 3) return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("shop") || lower.contains("sell") || lower.contains("buy")
                || lower.contains("price") || lower.contains("free")
                || lower.contains("rank") || lower.contains("kit")
                || lower.contains("store") || lower.contains("trade");
    }

    private static double similarity(String a, String b) {
        Set<String> agrams = trigrams(a);
        Set<String> bgrams = trigrams(b);
        if (agrams.isEmpty() || bgrams.isEmpty()) return 0.0D;
        Set<String> intersection = new HashSet<>(agrams);
        intersection.retainAll(bgrams);
        return (2.0D * intersection.size()) / (agrams.size() + bgrams.size());
    }

    private static Set<String> trigrams(String text) {
        Set<String> out = new HashSet<>();
        String n = text.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        for (int i = 0; i + 3 <= n.length(); i++) {
            out.add(n.substring(i, i + 3));
        }
        return out;
    }
}
