package com.coltcore.core.modules;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads an offline account into the server briefly so code that only works on an
 * online player can act on it, then removes it so the data is written back.
 *
 * <p>This exists because the server's own player-data store will only
 * <em>save</em> through a {@code Player} entity. There is no API to edit a
 * stored record in place, so a load/save cycle needs a real player object. The
 * vanilla {@code PlayerList} is the supported way to get one: construct a
 * server player, place it, then remove it. No network connection is involved and
 * nothing external connects - the account simply exists for the duration of the
 * call.
 *
 * <p>Everything is resolved reflectively so this compiles against the plain
 * Paper API and carries no NMS dependency. If any internal signature does not
 * match, {@link #isSupported()} reports false and callers fall back to the
 * pending-wipe queue rather than failing.
 *
 * <p>Targets Paper 1.21.11 (Mojang-mapped internals).
 */
public final class SyntheticPlayerLoader {

    private static final String SERVER_PLAYER = "net.minecraft.server.level.ServerPlayer";
    private static final String MINECRAFT_SERVER = "net.minecraft.server.MinecraftServer";
    private static final String SERVER_LEVEL = "net.minecraft.server.level.ServerLevel";
    private static final String PLAYER_LIST = "net.minecraft.server.players.PlayerList";
    private static final String GAME_PROFILE = "com.mojang.authlib.GameProfile";
    private static final String CLIENT_INFO = "net.minecraft.server.level.ClientInformation";
    private static final String PACKET_FLOW = "net.minecraft.network.protocol.PacketFlow";
    private static final String CONNECTION = "net.minecraft.network.Connection";
    private static final String COOKIE = "net.minecraft.server.network.CommonListenerCookie";

    private static Boolean supported;
    private static Method getServer;
    private static Method getPlayerList;
    private static Method overworld;
    private static Method placeNewPlayer;
    private static Method removePlayer;
    private static Constructor<?> gameProfileCtor;
    private static Constructor<?> serverPlayerCtor;
    private static Constructor<?> connectionCtor;
    private static Constructor<?> embeddedChannelCtor;
    private static Method createDefaultClientInfo;
    private static Method createInitialCookie;
    private static Field serverbound;
    private static Field connectionChannel;

    /** Embedded channels held open per synthetic player, released on unload. */
    private static final java.util.Map<Object, Object> CHANNELS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * UUIDs currently being processed as a synthetic load.
     *
     * <p>Registered <em>before</em> the player is placed, because placing them is
     * what fires the join and quit events. Anything that would announce a join or
     * leave can check {@link #isSynthetic} and stay silent.
     */
    private static final java.util.Set<UUID> SYNTHETIC = ConcurrentHashMap.newKeySet();

    /** True while this account is a temporary load, not a real player. */
    public static boolean isSynthetic(Player player) {
        return player != null && SYNTHETIC.contains(player.getUniqueId());
    }

    /** True while any synthetic load is in progress on this thread. */
    public static boolean syntheticInProgress() {
        return IN_PROGRESS.get();
    }

    private static final ThreadLocal<Boolean> IN_PROGRESS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private SyntheticPlayerLoader() { }

    /** True when every internal signature this needs was found. */
    public static synchronized boolean isSupported() {
        if (supported != null) return supported;
        try {
            Class<?> msClass = Class.forName(MINECRAFT_SERVER);
            Class<?> levelClass = Class.forName(SERVER_LEVEL);
            Class<?> spClass = Class.forName(SERVER_PLAYER);
            Class<?> plClass = Class.forName(PLAYER_LIST);
            Class<?> gpClass = Class.forName(GAME_PROFILE);
            Class<?> ciClass = Class.forName(CLIENT_INFO);
            Class<?> pfClass = Class.forName(PACKET_FLOW);
            Class<?> connClass = Class.forName(CONNECTION);
            Class<?> cookieClass = Class.forName(COOKIE);

            getServer = Bukkit.getServer().getClass().getMethod("getServer");
            getPlayerList = msClass.getMethod("getPlayerList");
            overworld = msClass.getMethod("overworld");
            placeNewPlayer = plClass.getMethod("placeNewPlayer", connClass, spClass, cookieClass);
            removePlayer = plClass.getMethod("remove", spClass);
            gameProfileCtor = gpClass.getConstructor(UUID.class, String.class);
            serverPlayerCtor = spClass.getConstructor(msClass, levelClass, gpClass, ciClass);
            connectionCtor = connClass.getConstructor(pfClass);
            createDefaultClientInfo = ciClass.getMethod("createDefault");
            createInitialCookie = cookieClass.getMethod("createInitial", gpClass, boolean.class);
            serverbound = pfClass.getField("SERVERBOUND");
            // Connection.channel is a public non-final field on 1.21.11. It has to be
            // set or placeNewPlayer NPEs the moment it writes the protocol handshake.
            connectionChannel = connClass.getField("channel");
            // An EmbeddedChannel accepts those writes and discards them, so nothing
            // is ever sent to a real socket and no client is broadcast to.
            embeddedChannelCtor = Class.forName("io.netty.channel.embedded.EmbeddedChannel")
                    .getConstructor();

            // Prove the chain actually works before claiming support.
            Object mcServer = getServer.invoke(Bukkit.getServer());
            getPlayerList.invoke(mcServer);
            overworld.invoke(mcServer);

            supported = Boolean.TRUE;
        } catch (Throwable t) {
            supported = Boolean.FALSE;
        }
        return supported;
    }

    /** Why support is unavailable, for the log. Never null when supported. */
    public static synchronized String unavailableReason() {
        if (isSupported()) return null;
        return "could not resolve Paper 1.21.11 player-list internals";
    }

    /**
     * Places the account into the player list. Their stored data is loaded as a
     * side effect. Must be called on the main server thread.
     *
     * @return the server player handle, or null if it could not be placed
     */
    public static Object load(UUID uuid, String name) {
        if (uuid == null || !isSupported()) return null;
        if (Bukkit.getPlayer(uuid) != null) return null;
        Object placed = null;
        SYNTHETIC.add(uuid);
        try {
            Object mcServer = getServer.invoke(Bukkit.getServer());
            Object playerList = getPlayerList.invoke(mcServer);
            Object level = overworld.invoke(mcServer);
            Object profile = gameProfileCtor.newInstance(uuid, name);
            Object clientInfo = createDefaultClientInfo.invoke(null);
            Object serverPlayer = serverPlayerCtor.newInstance(mcServer, level, profile, clientInfo);
            Object flow = serverbound.get(null);
            Object connection = connectionCtor.newInstance(flow);
            Object channel = embeddedChannelCtor.newInstance();
            connectionChannel.set(connection, channel);
            Object cookie = createInitialCookie.invoke(null, profile, Boolean.FALSE);
            placeNewPlayer.invoke(playerList, connection, serverPlayer, cookie);
            placed = serverPlayer;
            CHANNELS.put(serverPlayer, channel);
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[SyntheticPlayer] could not place " + name
                    + " (" + uuid + "): " + t);
            placed = null;
        } finally {
            if (placed == null) SYNTHETIC.remove(uuid);
        }
        return placed;
    }

    /**
     * Removes a previously placed player, which is what writes their data back.
     * Must be called on the main server thread.
     */
    public static boolean unload(Object serverPlayer) {
        if (serverPlayer == null || !isSupported()) return false;
        try {
            Object mcServer = getServer.invoke(Bukkit.getServer());
            Object playerList = getPlayerList.invoke(mcServer);
            removePlayer.invoke(playerList, serverPlayer);
            return true;
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[SyntheticPlayer] could not remove synthetic player: " + t);
            return false;
        } finally {
            try {
                UUID id = (UUID) serverPlayer.getClass().getMethod("getUUID").invoke(serverPlayer);
                SYNTHETIC.remove(id);
            } catch (Throwable ignored) {
            }
            Object channel = CHANNELS.remove(serverPlayer);
            if (channel != null) {
                try {
                    channel.getClass().getMethod("close").invoke(channel);
                } catch (Throwable ignored) {
                }
            }
        }
    }

/**
     * Loads the account now, holds it for the given ticks so its stored data
     * finishes loading, then runs the work and removes it.
     *
     * <p>5 seconds (100 ticks) is enough for the data to load and short enough
     * that the name never sits in the list. Running the work in the same tick
     * as the load reads a half-loaded player, which is why the immediate
     * {@link #withLoadedPlayer} form is wrong for wipes. Must be called on the
     * main server thread; the work also runs on it.
     *
     * @return true when the account was placed and the work was scheduled
     */
    public static boolean withLoadedPlayerHeld(org.bukkit.plugin.java.JavaPlugin plugin,
            UUID uuid, String name, long holdTicks, Runnable work) {
        if (uuid == null || work == null || plugin == null) return false;
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getLogger().warning("[SyntheticPlayer] called off the main thread.");
            return false;
        }
        Object handle = load(uuid, name);
        if (handle == null) return false;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            IN_PROGRESS.set(Boolean.TRUE);
            try {
                work.run();
            } catch (Throwable t) {
                Bukkit.getLogger().warning("[SyntheticPlayer] work failed for " + name + ": " + t);
            } finally {
                unload(handle);
                IN_PROGRESS.set(Boolean.FALSE);
            }
        }, Math.max(1L, holdTicks));
        return true;
    }

    /**
     * Loads the account, runs the work, then removes it again - all on the
     * calling main-thread tick, so the account is only ever in the player list
     * for the duration of one tick and is never broadcast to real clients.
     *
     * @return true when the cycle completed and the data was written back
     */
    public static boolean withLoadedPlayer(UUID uuid, String name, Runnable work) {
        if (uuid == null || work == null) return false;
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getLogger().warning("[SyntheticPlayer] withLoadedPlayer called off the main thread.");
            return false;
        }
        IN_PROGRESS.set(Boolean.TRUE);
        try {
            Object handle = load(uuid, name);
            if (handle == null) return false;
            boolean ok = false;
            try {
                work.run();
                ok = true;
            } catch (Throwable t) {
                Bukkit.getLogger().warning("[SyntheticPlayer] work failed for " + name + ": " + t);
            } finally {
                if (!unload(handle)) ok = false;
            }
            return ok;
        } finally {
            IN_PROGRESS.set(Boolean.FALSE);
        }
    }
}
