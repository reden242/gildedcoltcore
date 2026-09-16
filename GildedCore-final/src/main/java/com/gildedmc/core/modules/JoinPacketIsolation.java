package com.gildedmc.core.modules;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketContainer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class JoinPacketIsolation {
    private final JavaPlugin plugin;
    private final Set<UUID> locked = ConcurrentHashMap.newKeySet();
    private PacketAdapter listener;
    private PacketAdapter terrainListener;
    private final Map<UUID, List<PacketContainer>> heldTerrain = new ConcurrentHashMap<>();
    private final Set<UUID> replaying = ConcurrentHashMap.newKeySet();

    public JoinPacketIsolation(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enable() {
        if (plugin.getServer().getPluginManager().getPlugin("ProtocolLib") == null) {
            plugin.getLogger().warning("ProtocolLib is unavailable; join packet isolation cannot start.");
            return false;
        }
        Set<PacketType> blocked = blockedPacketTypes();
        listener = new PacketAdapter(plugin, ListenerPriority.HIGHEST, blocked) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                if (player != null && locked.contains(player.getUniqueId())) {
                    event.setCancelled(true);
                }
            }
        };
        ProtocolLibrary.getProtocolManager().addPacketListener(listener);
        Set<PacketType> terrainPackets = terrainPacketTypes();
        terrainListener = new PacketAdapter(plugin, ListenerPriority.HIGHEST, terrainPackets) {
            @Override
            public void onPacketSending(PacketEvent event) {
                Player player = event.getPlayer();
                if (player == null || replaying.contains(player.getUniqueId()) || !locked.contains(player.getUniqueId())) return;
                event.setCancelled(true);
                heldTerrain.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>()).add(event.getPacket().shallowClone());
            }
        };
        ProtocolLibrary.getProtocolManager().addPacketListener(terrainListener);
        return true;
    }

    public void disable() {
        locked.clear();
        heldTerrain.clear();
        replaying.clear();
        if (listener != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(listener);
            listener = null;
        }
        if (terrainListener != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(terrainListener);
            terrainListener = null;
        }
    }

    public void lock(Player player) {
        locked.add(player.getUniqueId());
        plugin.getLogger().info("Join packet lock enabled for " + player.getName() + ".");
    }

    public void unlock(Player player) {
        locked.remove(player.getUniqueId());
        List<PacketContainer> packets = heldTerrain.remove(player.getUniqueId());
        String packetSummary = packets == null ? "" : packets.stream()
                .collect(Collectors.groupingBy(packet -> packet.getType().name(), Collectors.counting()))
                .toString();
        plugin.getLogger().info("Join packet lock released for " + player.getName() + "; replaying " + (packets == null ? 0 : packets.size()) + " terrain packets " + packetSummary + ".");
        if (packets == null || packets.isEmpty()) return;
        replaying.add(player.getUniqueId());
        try {
            for (PacketContainer packet : packets) {
                ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet, false);
            }
        } finally {
            replaying.remove(player.getUniqueId());
        }
    }

    public boolean isLocked(Player player) {
        return locked.contains(player.getUniqueId());
    }

    private Set<PacketType> blockedPacketTypes() {
        Set<PacketType> types = new LinkedHashSet<>();
        add(types, "FLYING", "POSITION", "POSITION_LOOK", "LOOK", "VEHICLE_MOVE", "STEER_VEHICLE");
        add(types, "USE_ENTITY", "BLOCK_DIG", "BLOCK_PLACE", "USE_ITEM", "ARM_ANIMATION", "ENTITY_ACTION");
        add(types, "WINDOW_CLICK", "CLOSE_WINDOW", "CREATIVE_INVENTORY_ACTION", "HELD_ITEM_SLOT");
        return types;
    }

    private Set<PacketType> terrainPacketTypes() {
        Set<PacketType> types = new LinkedHashSet<>();
        // Keep the native loading screen open by withholding final position sync.
        // Chunk and light data must reach the client so CheatDetector's phantom sign can respond.
        addServer(types, "POSITION", "UNLOAD_CHUNK", "VIEW_CENTRE", "VIEW_DISTANCE");
        return types;
    }

    private void add(Set<PacketType> target, String... fieldNames) {
        for (String fieldName : fieldNames) {
            try {
                Object value = PacketType.Play.Client.class.getField(fieldName).get(null);
                if (value instanceof PacketType packetType && packetType.isSupported()) {
                    target.add(packetType);
                }
            } catch (ReflectiveOperationException ignored) {
                plugin.getLogger().fine("Packet type unavailable on this server: " + fieldName);
            }
        }
    }

    private void addServer(Set<PacketType> target, String... fieldNames) {
        for (String fieldName : fieldNames) {
            try {
                Object value = PacketType.Play.Server.class.getField(fieldName).get(null);
                if (value instanceof PacketType packetType && packetType.isSupported()) target.add(packetType);
            } catch (ReflectiveOperationException ignored) {
                plugin.getLogger().fine("Server packet type unavailable: " + fieldName);
            }
        }
    }
}
