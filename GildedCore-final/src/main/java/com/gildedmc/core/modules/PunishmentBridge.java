package com.gildedmc.core.modules;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Where punishments actually land.
 *
 * <h2>LiteBans first</h2>
 * A server running LiteBans already has the ban screens, the appeal links, the
 * cross-server sync and the staff history that everyone is used to. Issuing
 * mutes and bans anywhere else would split the record in two, so when LiteBans
 * is installed every punishment this plugin decides on is dispatched as a
 * LiteBans command and LiteBans owns the enforcement.
 *
 * <h2>Internal fallback</h2>
 * Without LiteBans the punishment still has to stick, so {@link MuteStore}
 * enforces it — mutes by cancelling chat, bans at login. The fallback exists so
 * the text guard's ladder is never a ladder to nothing; it is not the intended
 * setup on a server that has LiteBans.
 *
 * <h2>What stays ours either way</h2>
 * The offence ledger. LiteBans counts punishments per account; the ladder here
 * escalates per <em>address</em> and per reason, which LiteBans has no concept
 * of. So offences are always recorded in {@link MuteStore} regardless of which
 * backend enforces, and the backend only ever receives the already-decided
 * action and duration.
 */
public final class PunishmentBridge {

    public enum Backend { LITEBANS, INTERNAL }

    /** What actually happened, so callers can report it honestly. */
    public record Result(boolean applied, Backend backend, String command, String detail) { }

    private final JavaPlugin plugin;
    private final MuteStore store;

    private String configured = "auto";
    private Backend backend = Backend.INTERNAL;
    private Object liteBansDatabase;
    private Method isBannedMethod;
    private Method isMutedMethod;
    private final Map<String, String> templates = new LinkedHashMap<>();

    public PunishmentBridge(JavaPlugin plugin, MuteStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    /* ------------------------------------------------------------------ */
    /*  Lifecycle                                                         */
    /* ------------------------------------------------------------------ */

    public void enable() {
        readConfig();
        boolean present = liteBansInstalled();
        this.backend = switch (this.configured) {
            case "litebans" -> Backend.LITEBANS;
            case "internal" -> Backend.INTERNAL;
            default -> present ? Backend.LITEBANS : Backend.INTERNAL;
        };
        if (this.backend == Backend.LITEBANS) {
            if (!present) {
                this.plugin.getLogger().warning("[Punish] backend is set to litebans but "
                        + "LiteBans is not installed. Punishments would go nowhere; "
                        + "falling back to internal enforcement.");
                this.backend = Backend.INTERNAL;
            } else {
                bindLiteBansApi();
                this.plugin.getLogger().info("[Punish] using LiteBans. Mutes and bans are "
                        + "dispatched as LiteBans commands and LiteBans enforces them."
                        + (this.liteBansDatabase == null
                            ? " (status API unavailable; commands still work.)" : ""));
            }
        }
        if (this.backend == Backend.INTERNAL) {
            this.plugin.getLogger().info("[Punish] using internal enforcement "
                    + "(textguard.db). Install LiteBans to hand this over to it.");
        }
    }

    public void reload() { enable(); }

    private void readConfig() {
        ConfigurationSection c = this.plugin.getConfig().getConfigurationSection("punishments");
        this.templates.clear();
        this.configured = c == null ? "auto"
                : c.getString("backend", "auto").toLowerCase(Locale.ROOT).trim();
        ConfigurationSection lb = c == null ? null : c.getConfigurationSection("litebans");
        if (lb != null) for (String k : lb.getKeys(false)) {
            this.templates.put(k.toUpperCase(Locale.ROOT), lb.getString(k, ""));
        }
        // LiteBans defaults, so a config that predates this section still works.
        this.templates.putIfAbsent("WARN", "warn %player% %reason%");
        this.templates.putIfAbsent("MUTE", "mute %player% %duration% %reason%");
        this.templates.putIfAbsent("MUTE-PERMANENT", "mute %player% %reason%");
        this.templates.putIfAbsent("IPMUTE", "ipmute %player% %duration% %reason%");
        this.templates.putIfAbsent("IPMUTE-PERMANENT", "ipmute %player% %reason%");
        this.templates.putIfAbsent("BAN", "ban %player% %duration% %reason%");
        this.templates.putIfAbsent("BAN-PERMANENT", "ban %player% %reason%");
        this.templates.putIfAbsent("IPBAN", "ipban %player% %duration% %reason%");
        this.templates.putIfAbsent("IPBAN-PERMANENT", "ipban %player% %reason%");
        this.templates.putIfAbsent("UNMUTE", "unmute %player%");
        this.templates.putIfAbsent("UNBAN", "unban %player%");
    }

    private static boolean liteBansInstalled() {
        Plugin p = Bukkit.getPluginManager().getPlugin("LiteBans");
        return p != null && p.isEnabled();
    }

    /**
     * Binds litebans.api.Database for status lookups.
     *
     * Reflection because LiteBans is optional and its API jar is not a compile
     * dependency here. Failure is not fatal: the commands are what apply a
     * punishment, the API only answers "is this player already muted".
     */
    private void bindLiteBansApi() {
        try {
            Class<?> db = Class.forName("litebans.api.Database");
            this.liteBansDatabase = db.getMethod("get").invoke(null);
            this.isBannedMethod = db.getMethod("isPlayerBanned", UUID.class, String.class);
            this.isMutedMethod = db.getMethod("isPlayerMuted", UUID.class, String.class);
        } catch (Throwable ex) {
            this.liteBansDatabase = null;
            this.isBannedMethod = null;
            this.isMutedMethod = null;
        }
    }

    public Backend backend() { return this.backend; }

    public boolean usingLiteBans() { return this.backend == Backend.LITEBANS; }

    /* ------------------------------------------------------------------ */
    /*  Applying                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Applies an already-decided punishment.
     *
     * @param action     WARN, MUTE, IPMUTE, BAN or IPBAN
     * @param durationMs negative for permanent; ignored for WARN
     * @return what happened, including the exact command when one was run
     */
    public Result apply(String action, String player, String ip, long durationMs,
                        String reason, String staff) {
        String act = action == null ? "" : action.toUpperCase(Locale.ROOT);
        if (act.equals("NONE")) return new Result(false, this.backend, null, "no action");

        if (this.backend == Backend.LITEBANS) {
            String key = act.equals("WARN") || durationMs >= 0 ? act : act + "-PERMANENT";
            String template = this.templates.get(key);
            if (template == null || template.isBlank()) {
                return new Result(false, Backend.LITEBANS, null,
                        "no LiteBans command configured for " + key);
            }
            String cmd = CommandTemplate.expand(template, player)
                    .replace("%duration%", liteBansDuration(durationMs))
                    .replace("%reason%", reason == null ? "Unspecified" : reason)
                    .replace("%staff%", staff == null ? "Console" : CommandTemplate.safeName(staff))
                    .replace("%ip%", ip == null ? "" : ip)
                    .replaceAll("\\s{2,}", " ")
                    .trim();
            dispatch(cmd);
            return new Result(true, Backend.LITEBANS, cmd, null);
        }

        // Internal fallback.
        switch (act) {
            case "WARN" -> {
                Player online = Bukkit.getPlayerExact(player);
                if (online != null) {
                    online.sendMessage(UiKit.colour("&c&lWARNING &7- &f"
                            + (reason == null ? "Unspecified" : reason)));
                }
                return new Result(true, Backend.INTERNAL, null, "warned in chat");
            }
            case "MUTE" -> {
                this.store.mute(MuteStore.uuidOf(player), player, null, durationMs, reason, "gildedcore");
                return new Result(true, Backend.INTERNAL, null, "muted internally");
            }
            case "IPMUTE" -> {
                this.store.mute(MuteStore.uuidOf(player), player, ip, durationMs, reason, "gildedcore");
                return new Result(true, Backend.INTERNAL, null, "IP muted internally");
            }
            case "BAN" -> {
                this.store.ban(MuteStore.uuidOf(player), player, null, durationMs, reason,
                        "gildedcore", staff);
                kick(player, reason, durationMs);
                return new Result(true, Backend.INTERNAL, null, "banned internally");
            }
            case "IPBAN" -> {
                this.store.ban(MuteStore.uuidOf(player), player, ip, durationMs, reason,
                        "gildedcore", staff);
                kick(player, reason, durationMs);
                return new Result(true, Backend.INTERNAL, null, "IP banned internally");
            }
            default -> {
                return new Result(false, Backend.INTERNAL, null, "unknown action " + act);
            }
        }
    }

    /** Lifts a punishment. action is MUTE or BAN; IP variants are covered too. */
    public int lift(String action, String player) {
        String act = action == null ? "" : action.toUpperCase(Locale.ROOT);
        if (this.backend == Backend.LITEBANS) {
            String template = this.templates.get(act.startsWith("BAN") ? "UNBAN" : "UNMUTE");
            if (template == null || template.isBlank()) return 0;
            dispatch(CommandTemplate.expand(template, player));
            return 1;
        }
        return act.startsWith("BAN") ? this.store.unban(player) : this.store.unmute(player);
    }

    private void kick(String player, String reason, long durationMs) {
        Player online = Bukkit.getPlayerExact(player);
        if (online == null) return;
        String screen = UiKit.colour("&c&lYou are banned\n\n&7Reason: &f"
                + (reason == null ? "Unspecified" : reason)
                + "\n&7Expires: &f" + (durationMs < 0 ? "never" : MuteStore.humanise(durationMs)));
        com.gildedmc.core.SchedulerCompat.run(this.plugin, () -> online.kickPlayer(screen));
    }

    private void dispatch(String command) {
        if (Bukkit.isPrimaryThread()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } else {
            com.gildedmc.core.SchedulerCompat.run(this.plugin,
                    () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Status                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Whether this player is muted right now.
     *
     * Under LiteBans this asks LiteBans, so a mute issued through /mute by hand
     * counts exactly the same as one this plugin issued.
     */
    public boolean isMuted(UUID uuid, String ip, String name) {
        if (this.backend == Backend.LITEBANS && this.isMutedMethod != null) {
            try {
                Object v = this.isMutedMethod.invoke(this.liteBansDatabase, uuid, ip);
                return Boolean.TRUE.equals(v);
            } catch (Throwable ignored) { }
        }
        if (this.backend == Backend.LITEBANS) return false;   // LiteBans enforces it
        return name != null && this.store.activeFor(name) != null;
    }

    public boolean isBanned(UUID uuid, String ip, String name) {
        if (this.backend == Backend.LITEBANS && this.isBannedMethod != null) {
            try {
                Object v = this.isBannedMethod.invoke(this.liteBansDatabase, uuid, ip);
                return Boolean.TRUE.equals(v);
            } catch (Throwable ignored) { }
        }
        if (this.backend == Backend.LITEBANS) return false;
        return this.store.activeBan(uuid, name, ip) != null;
    }

    /** True when this plugin, not LiteBans, has to cancel the chat event. */
    public boolean enforcesLocally() { return this.backend == Backend.INTERNAL; }

    /* ------------------------------------------------------------------ */
    /*  Durations                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * Milliseconds to the LiteBans time format.
     *
     * Permanent returns an empty string on purpose: LiteBans treats
     * {@code /ban player reason} with no time argument as permanent, and the
     * template collapses the resulting double space.
     */
    public static String liteBansDuration(long ms) {
        if (ms < 0) return "";
        if (ms % 604_800_000L == 0 && ms >= 604_800_000L) return (ms / 604_800_000L) + "w";
        if (ms % 86_400_000L == 0 && ms >= 86_400_000L) return (ms / 86_400_000L) + "d";
        if (ms % 3_600_000L == 0 && ms >= 3_600_000L) return (ms / 3_600_000L) + "h";
        return Math.max(1L, ms / 60_000L) + "m";
    }
}
