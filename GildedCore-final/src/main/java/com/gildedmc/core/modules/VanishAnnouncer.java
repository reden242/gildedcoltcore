package com.gildedmc.core.modules;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import com.gildedmc.core.SchedulerCompat;

/**
 * Self-contained vanish integration.
 *
 * <p>Design follows the compatibility model used by Custom Join Messages
 * ({@code net.insprill.cjm.compatibility}): rather than depend on a join-message
 * plugin, each vanish provider is observed directly and only the
 * quit/join-on-toggle half of the behaviour is taken.
 *
 * <p>Two observation strategies, in priority order:
 * <ol>
 *   <li><b>Event hooks.</b> Every provider that publishes a toggle event is
 *       hooked reflectively. Hooks are keyed by fully-qualified class name
 *       rather than simple name, because EssentialsX and VanishNoPacket both
 *       publish a class literally called {@code VanishStatusChangeEvent} in
 *       different packages and need different readers.</li>
 *   <li><b>Metadata polling.</b> Providers with no event we know (StaffSuite,
 *       SayanVanish) are covered by sampling {@link VanishSupport#isVanished}
 *       and emitting only on an actual transition. Polling runs at 1s by
 *       default, not every tick: state flickering inside the sample window is
 *       intentionally collapsed, and a player toggling twice in one window
 *       produces no spurious pair of broadcasts.</li>
 * </ol>
 *
 * <p>Nothing here is a compile-time or runtime dependency. Provider classes are
 * resolved by name; a provider that is absent, renamed or API-incompatible is
 * skipped and falls back to polling.
 */
public final class VanishAnnouncer implements AutoCloseable {
    private static final String ESSENTIALS_EVENT = "net.ess3.api.events.VanishStatusChangeEvent";
    private static final String MYZELYAM_EVENT = "de.myzelyam.api.vanish.PlayerVanishStateChangeEvent";
    private static final String KITTEH_EVENT = "org.kitteh.vanish.event.VanishStatusChangeEvent";
    private static final String CMI_VANISH_EVENT = "com.Zrips.CMI.events.CMIPlayerVanishEvent";
    private static final String CMI_UNVANISH_EVENT = "com.Zrips.CMI.events.CMIPlayerUnVanishEvent";
    private static final String ADV_VANISH_EVENT = "me.quantiom.advancedvanish.event.PlayerVanishEvent";
    private static final String ADV_UNVANISH_EVENT = "me.quantiom.advancedvanish.event.PlayerUnVanishEvent";

    private final Plugin plugin;
    private final RankContext ranks;
    private final Map<UUID, Boolean> known = new HashMap<>();

    private boolean enabled;
    private String leaveMessage;
    private String joinMessage;
    private String rankedJoinMessage;
    private String rankedViewerPermission;
    private long pollTicks = 20L;

    // AdvancedVanish clears its own vanish state as part of the quit, so a
    // quitting player reads as unvanished and would fire a spurious join
    // message. The state is snapshotted at LOWEST quit and held until MONITOR.
    private boolean advancedVanishPresent;
    private boolean isHandlingQuit;
    private boolean wasLastPlayerVanished;

    private record Toggle(Player player, boolean vanishing) {
    }

    private record Hook(String eventClass, Reader reader, EventPriority priority, boolean ignoreCancelled) {
    }

    @FunctionalInterface
    private interface Reader {
        Toggle read(Event event) throws ReflectiveOperationException;
    }

    /**
     * Rank lookups needed to route a join-on-unvanish. Supplied by the host
     * plugin so this class never has to know how ranks are stored.
     */
    public interface RankContext {
        /** Display prefix for a player, already colour-formatted. */
        String prefix(Player player);

        /** True when the player is not on the default rank. */
        boolean isRanked(Player player);
    }

    public VanishAnnouncer(Plugin plugin, RankContext ranks) {
        this.plugin = plugin;
        this.ranks = ranks;
    }

    /** Reads configuration and installs event hooks. Safe to call once. */
    public void enable() {
        var config = plugin.getConfig();
        this.enabled = config.getBoolean("vanish-announce.enabled", true);
        this.leaveMessage = config.getString("vanish-announce.leave-message", "&c[-] &f%player%");
        this.joinMessage = config.getString("vanish-announce.join-message", "&c[+] &f%player%");
        this.rankedJoinMessage = config.getString("vanish-announce.ranked-join-message", "%rank%%player%\n&7Has joined!");
        this.rankedViewerPermission = config.getString("vanish-announce.ranked-viewers", "");
        this.pollTicks = Math.max(1L, config.getLong("vanish-announce.poll-ticks", 20L));
        this.advancedVanishPresent = isPluginEnabled("AdvancedVanish");

        if (!enabled) return;
        List<Hook> hooks = List.of(
                new Hook(ESSENTIALS_EVENT, VanishAnnouncer::readEssentials, EventPriority.MONITOR, true),
                // SuperVanish, PremiumVanish and VelocityVanish all ship this
                // one event, so a single hook covers all three.
                new Hook(MYZELYAM_EVENT, VanishAnnouncer::readMyzelyam, EventPriority.MONITOR, true),
                new Hook(KITTEH_EVENT, VanishAnnouncer::readKitteh, EventPriority.MONITOR, false),
                // CMI publishes NORMAL-priority, non-cancellable-by-default
                // handlers upstream; mirrored here rather than promoted.
                new Hook(CMI_VANISH_EVENT, event -> new Toggle(player(event), true), EventPriority.NORMAL, false),
                new Hook(CMI_UNVANISH_EVENT, event -> new Toggle(player(event), false), EventPriority.NORMAL, false),
                new Hook(ADV_VANISH_EVENT, VanishAnnouncer::readAdvancedVanish, EventPriority.MONITOR, true),
                new Hook(ADV_UNVANISH_EVENT, VanishAnnouncer::readAdvancedUnvanish, EventPriority.MONITOR, true));

        int installed = 0;
        for (Hook hook : hooks) {
            if (install(hook)) installed++;
        }
        if (advancedVanishPresent) {
            installQuitGuard();
        }
        SchedulerCompat.timer(plugin, this::poll, pollTicks, pollTicks);
        plugin.getLogger().info("Vanish integration active: " + installed + "/" + hooks.size()
                + " event hook(s) installed, metadata fallback every " + pollTicks + " ticks");
    }

    // ---------------------------------------------------------------- hooks

    private boolean install(Hook hook) {
        try {
            Class<?> raw = Class.forName(hook.eventClass());
            if (!Event.class.isAssignableFrom(raw)) return false;
            @SuppressWarnings("unchecked")
            Class<? extends Event> eventClass = (Class<? extends Event>) raw;
            Bukkit.getPluginManager().registerEvent(
                    eventClass,
                    new Listener() {
                    },
                    hook.priority(),
                    (listener, event) -> onProviderEvent(hook, event),
                    plugin,
                    hook.ignoreCancelled());
            return true;
        } catch (Throwable ignored) {
            // Provider absent, renamed or incompatible. Polling still covers it.
            return false;
        }
    }

    private void installQuitGuard() {
        Listener noop = new Listener() {
        };
        Bukkit.getPluginManager().registerEvent(PlayerQuitEvent.class, noop, EventPriority.LOWEST,
                (listener, event) -> {
                    wasLastPlayerVanished = query(((PlayerQuitEvent) event).getPlayer());
                    isHandlingQuit = true;
                }, plugin, false);
        Bukkit.getPluginManager().registerEvent(PlayerQuitEvent.class, noop, EventPriority.MONITOR,
                (listener, event) -> isHandlingQuit = false, plugin, false);
    }

    private void onProviderEvent(Hook hook, Event event) {
        try {
            Toggle toggle = hook.reader().read(event);
            if (toggle == null || toggle.player() == null) return;
            if (SyntheticPlayerLoader.isSynthetic(toggle.player())) return;
            dispatch(toggle.player(), toggle.vanishing());
        } catch (Throwable ignored) {
            // Never let an optional provider break the broadcast path.
        }
    }

    // -------------------------------------------------------------- readers

    /** EssentialsX: {@code getValue()} for state, {@code getController().getBase()} for the user. */
    private static Toggle readEssentials(Event event) throws ReflectiveOperationException {
        Object base = call(call(event, "getController"), "getBase");
        Player player = null;
        Object name = call(base, "getName");
        if (name instanceof String exact) {
            player = Bukkit.getPlayerExact(exact);
        }
        if (player == null && call(base, "getUniqueId") instanceof UUID id) {
            player = Bukkit.getPlayer(id);
        }
        if (player == null) return null;
        Object value = call(event, "getValue");
        return value instanceof Boolean state ? new Toggle(player, state) : null;
    }

    /** SuperVanish / PremiumVanish / VelocityVanish: {@code de.myzelyam} facade. */
    private static Toggle readMyzelyam(Event event) throws ReflectiveOperationException {
        if (!(call(event, "getUuid") instanceof UUID id)) return null;
        Object value = call(event, "isVanishing");
        return value instanceof Boolean state ? new Toggle(Bukkit.getPlayer(id), state) : null;
    }

    /** VanishNoPacket: {@code org.kitteh.vanish}, a MyZelyam fork. */
    private static Toggle readKitteh(Event event) throws ReflectiveOperationException {
        Object value = call(event, "isVanishing");
        return value instanceof Boolean state ? new Toggle(player(event), state) : null;
    }

    /**
     * AdvancedVanish. {@code getOnJoin()} is true when the plugin itself
     * vanished the player as part of joining, which must not be announced.
     */
    private static Toggle readAdvancedVanish(Event event) throws ReflectiveOperationException {
        if (Boolean.TRUE.equals(call(event, "getOnJoin"))) return null;
        return new Toggle(player(event), true);
    }

    private static Toggle readAdvancedUnvanish(Event event) throws ReflectiveOperationException {
        return new Toggle(player(event), false);
    }

    private static Player player(Event event) throws ReflectiveOperationException {
        Object value = call(event, "getPlayer");
        return value instanceof Player found ? found : null;
    }

    private static Object call(Object target, String method) throws ReflectiveOperationException {
        if (target == null) throw new ReflectiveOperationException("null target for " + method);
        Method found = null;
        for (Method candidate : target.getClass().getMethods()) {
            if (candidate.getName().equals(method) && candidate.getParameterCount() == 0) {
                found = candidate;
                break;
            }
        }
        if (found == null) throw new ReflectiveOperationException("missing " + method);
        found.setAccessible(true);
        return found.invoke(target);
    }

    // ----------------------------------------------------------------- poll

    private boolean query(Player player) {
        if (advancedVanishPresent && isHandlingQuit) {
            return wasLastPlayerVanished;
        }
        return VanishSupport.isVanished(player);
    }

    private void poll() {
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (SyntheticPlayerLoader.isSynthetic(player)) continue;
                boolean now = query(player);
                Boolean before = known.put(player.getUniqueId(), now);
                if (before == null || before == now) continue;
                dispatch(player, now);
            }
        } catch (Throwable ignored) {
            // Polling is best-effort; event hooks remain authoritative.
        }
    }

    private boolean isPluginEnabled(String name) {
        var found = Bukkit.getPluginManager().getPlugin(name);
        return found != null && found.isEnabled();
    }

    // -------------------------------------------------------------- routing

    private void dispatch(Player player, boolean vanishing) {
        if (!enabled) return;
        if (vanishing) {
            broadcast(leaveMessage, player);
            return;
        }
        if (ranks.isRanked(player)) {
            String rendered = render(rankedJoinMessage, player);
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (ranks.isRanked(viewer) && canSeeRanked(viewer)) {
                    send(viewer, rendered);
                }
            }
            return;
        }
        broadcast(joinMessage, player);
    }

    private boolean canSeeRanked(Player viewer) {
        if (rankedViewerPermission == null || rankedViewerPermission.isEmpty()) return true;
        for (String permission : rankedViewerPermission.split(";")) {
            String trimmed = permission.trim();
            if (!trimmed.isEmpty() && viewer.hasPermission(trimmed)) return true;
        }
        return false;
    }

    private void broadcast(String template, Player subject) {
        send(null, render(template, subject));
    }

    private String render(String template, Player subject) {
        String prefix = ranks.prefix(subject);
        return (template == null ? "" : template)
                .replace("%player%", subject.getName())
                .replace("%rank%", prefix == null ? "" : prefix)
                .replace("%uuid%", subject.getUniqueId().toString());
    }

    private void send(Player viewer, String rendered) {
        if (rendered.isEmpty()) return;
        // Fill first so a leading "&c" on a multi-line template is honoured.
        for (String line : rendered.split("\\R")) {
            String message = ChatColor.translateAlternateColorCodes('&', line);
            if (viewer == null) {
                Bukkit.broadcastMessage(message);
            } else {
                viewer.sendMessage(message);
            }
        }
    }

    @Override
    public void close() {
        known.clear();
    }
}
