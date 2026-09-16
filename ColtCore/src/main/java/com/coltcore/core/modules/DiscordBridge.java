package com.coltcore.core.modules;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

/**
 * Sends a message to a DiscordSRV channel, if DiscordSRV is installed.
 *
 * <h2>Why reflection</h2>
 * DiscordSRV is optional. Importing it would make it mandatory at class-load
 * time, and a server without it would fail to start rather than simply do
 * without Discord logging.
 *
 * <h2>Why the method is found by shape</h2>
 * {@code DiscordUtil.sendMessage} has several overloads, and the channel
 * parameter type has moved package twice across JDA versions. Matching on "two
 * parameters, the second is a String, the first accepts the object we are
 * holding" survives that; matching on an exact signature does not.
 */
public final class DiscordBridge {

    private DiscordBridge() { }

    /**
     * Posts to the DiscordSRV channel mapped to {@code channelName}.
     *
     * <p>Always off the main thread — this reaches the network.
     *
     * @param tag prefix for any warning logged, so the owner knows which
     *            feature failed to reach Discord
     */
    public static void send(JavaPlugin plugin, String tag, String channelName, String message) {
        if (channelName == null || channelName.isBlank() || message == null) return;
        com.coltcore.core.SchedulerCompat.runAsync(plugin, () -> sendNow(plugin, tag, channelName, message));
    }

    /** Same, on the calling thread. Only for callers already off the main one. */
    public static boolean sendNow(JavaPlugin plugin, String tag,
                                  String channelName, String message) {
        try {
            Class<?> srv = Class.forName("github.scarsz.discordsrv.DiscordSRV");
            Object instance = srv.getMethod("getPlugin").invoke(null);
            Object channel = srv.getMethod("getDestinationTextChannelForGameChannelName",
                    String.class).invoke(instance, channelName);
            if (channel == null) {
                plugin.getLogger().warning(tag + " DiscordSRV has no channel mapped to '"
                        + channelName + "'. Add it under channels in DiscordSRV's config.");
                return false;
            }
            Class<?> util = Class.forName("github.scarsz.discordsrv.util.DiscordUtil");
            for (Method m : util.getMethods()) {
                if (!m.getName().equals("sendMessage")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 2 || p[1] != String.class) continue;
                if (!p[0].isInstance(channel)) continue;
                m.invoke(null, channel, message);
                return true;
            }
            plugin.getLogger().warning(tag + " DiscordSRV is installed but no usable "
                    + "DiscordUtil.sendMessage(channel, String) was found.");
            return false;
        } catch (ClassNotFoundException ex) {
            // DiscordSRV not installed. Nothing worth saying.
            return false;
        } catch (Throwable ex) {
            plugin.getLogger().warning(tag + " Discord send failed: "
                    + ex.getClass().getSimpleName() + " " + ex.getMessage());
            return false;
        }
    }

    /** Whether DiscordSRV is present at all. */
    public static boolean available() {
        try {
            Class.forName("github.scarsz.discordsrv.DiscordSRV");
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
