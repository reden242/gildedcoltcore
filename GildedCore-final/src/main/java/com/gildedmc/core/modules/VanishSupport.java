package com.gildedmc.core.modules;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * Optional vanish integration for public-facing join, quit and custom join
 * messages. No vanish plugin is a hard dependency: integrations are queried by
 * reflection and metadata is used as the portable fallback.
 */
public final class VanishSupport {
    private static final String[] METADATA_KEYS = {
            "vanished", "vanish", "supervanish", "premiumvanish", "essentials:vanished", "cmiVanished"
    };

    private VanishSupport() {
    }

    /** Returns true when an installed vanish provider says the player is hidden. */
    public static boolean isVanished(Player player) {
        if (player == null) return false;
        for (String key : METADATA_KEYS) {
            if (player.hasMetadata(key) && player.getMetadata(key).stream().anyMatch(value -> value.asBoolean())) {
                return true;
            }
        }
        return premiumVanish(player) || essentialsVanish(player) || cmi(player);
    }

    /**
     * SuperVanish / PremiumVanish.
     *
     * <p>Both ship the same {@code de.myzelyam.api.vanish.VanishAPI} facade with
     * a static {@code isInvisible(Player)}, so one call covers both plugins.
     * PremiumVanish (2.x) also exposes {@code getAllInvisiblePlayers()}, which is
     * checked as a fallback for forks that renamed or dropped {@code isInvisible}.
     */
    private static boolean premiumVanish(Player player) {
        try {
            if (!pluginEnabled(player, "PremiumVanish", "SuperVanish")) return false;
            Class<?> api = Class.forName("de.myzelyam.api.vanish.VanishAPI");
            for (Method method : api.getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                if (method.getParameterCount() != 1) continue;
                if (!Player.class.isAssignableFrom(method.getParameterTypes()[0])) continue;
                if (method.getName().equals("isInvisible")
                        && Boolean.TRUE.equals(method.invoke(null, player))) {
                    return true;
                }
            }
            Method all = api.getMethod("getAllInvisiblePlayers");
            Object result = all.invoke(null);
            if (result instanceof Collection<?> hidden) {
                UUID uuid = player.getUniqueId();
                return hidden.stream().anyMatch(value -> uuid.equals(value) || player.equals(value));
            }
        } catch (Throwable ignored) {
            // SuperVanish/PremiumVanish is optional.
        }
        return false;
    }

    private static boolean essentialsVanish(Player player) {
        try {
            Class<?> essentialsClass = Class.forName("com.earth2me.essentials.Essentials");
            Plugin essentials = enabledPlugin(player, "Essentials", "EssentialsX");
            if (essentials == null || !essentialsClass.isInstance(essentials)) return false;
            Method getUser = essentialsClass.getMethod("getUser", Player.class);
            Object user = getUser.invoke(essentials, player);
            if (user == null) return false;
            Method isVanished = user.getClass().getMethod("isVanished");
            return Boolean.TRUE.equals(isVanished.invoke(user));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean cmi(Player player) {
        try {
            Object cmi = player.getServer().getPluginManager().getPlugin("CMI");
            if (cmi == null) return false;
            Method getPlayerManager = cmi.getClass().getMethod("getPlayerManager");
            Object manager = getPlayerManager.invoke(cmi);
            Method getUser = manager.getClass().getMethod("getUser", Player.class);
            Object user = getUser.invoke(manager, player);
            if (user == null) return false;
            for (Method method : user.getClass().getMethods()) {
                if (method.getName().toLowerCase(Locale.ROOT).equals("isvanished")
                        && method.getParameterCount() == 0) {
                    return Boolean.TRUE.equals(method.invoke(user));
                }
            }
        } catch (Throwable ignored) {
            // CMI is optional and its API differs across releases.
        }
        return false;
    }

    private static boolean pluginEnabled(Player player, String... names) {
        return enabledPlugin(player, names) != null;
    }

    private static Plugin enabledPlugin(Player player, String... names) {
        PluginManager manager = player.getServer().getPluginManager();
        for (String name : names) {
            Plugin plugin = manager.getPlugin(name);
            if (plugin != null && plugin.isEnabled()) return plugin;
        }
        return null;
    }
}
