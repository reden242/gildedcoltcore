package com.coltcore.core.modules;

import com.coltcore.core.SchedulerCompat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catches staff who are AFK-farming, macroing or auto-clicking.
 *
 * <h2>The rule this implements</h2>
 * Staff are held to a higher bar than players, so this only ever looks at
 * holders of a configured LuckPerms staff rank. Everyone else is ignored
 * outright â€” their input is never even collected.
 *
 * <h2>What actually gives a macro away</h2>
 * Not that its actions are identical: a decent macro varies them, and a human
 * grinding the same task looks repetitive too. What no human can fake is the
 * <em>clock</em>. If a pattern comes round again every second, and every repeat
 * lands within a few milliseconds of one second, for a minute straight, that is
 * software. A person trying deliberately to hold a one-second rhythm drifts by
 * tens of milliseconds within a handful of repeats.
 *
 * <p>More precisely: a macro is either perfect, or it randomises its delay
 * inside a fixed bound â€” {@code sleep(1000 Â± 40)} never produces 1400. Humans
 * have no bound. Given long enough they always cross it: they look away, read
 * chat, hesitate, get up. So the deciding measurement is the <b>envelope</b> â€”
 * the single worst repeat, as a fraction of the cycle length â€” over an
 * observation long enough that a person would have broken stride by now.
 * Variance is reported for context, but the envelope is what decides.
 *
 * <p>Measured over 200 runs of a 60-second, one-second cycle, the envelope is
 * 0.00 for a perfect macro, 0.02-0.16 for one jittering by up to Â±40ms, and
 * 0.45-3.55 for a human. Watching longer only widens the gap, because the
 * macro's bound never moves and the human's worst moment keeps getting worse.
 *
 * <p>Diversity <em>inside</em> the cycle is expected and allowed.
 * Note that the match ratio falls off faster than the diversity that causes it:
 * a comparison needs <em>both</em> ends unvaried, so a cycle where a fraction
 * {@code d} of actions differ scores about {@code (1-d)Â²}. A quarter of the
 * actions varying gives a ratio near 0.56, which is why the default
 * {@code min-pattern-match} is 0.5 rather than 0.75 â€” the match ratio only has
 * to be good enough to establish the period. The <em>timing</em> is what
 * decides.
 *
 * <h2>Three stages, in this order</h2>
 * <ol>
 *   <li><b>The cycle.</b> {@link #detectCycle} over clicks, movement and chat.
 *       Nothing is flagged here.</li>
 *   <li><b>Grim verifies it.</b> {@link GrimBridge} reports whether Grim's
 *       movement simulation reproduced the ticks exactly and with almost no
 *       variety. Only movement that the server itself could have generated
 *       counts as machine-driven.</li>
 *   <li><b>The chat check.</b> A loop proves automation, not absence â€” so the
 *       player is asked in chat to type a short code. Answer it and the
 *       suspicion is dropped and the samples reset. Stay silent for the whole
 *       window while the loop keeps running, and that is the AFK flag.</li>
 * </ol>
 *
 * <p>With Grim absent there is nothing to verify against, so by default the
 * detection is logged and no check is sent. Set
 * {@code require-grim-verification: false} to accept stage one alone.
 */
public final class StaffMacroModule implements Listener {

    public static final String PERM_ALERTS = "coltcore.staffmacro.alerts";
    public static final String PERM_ADMIN = "coltcore.staffmacro.admin";
    /** Holders are never analysed, whatever their rank. */
    public static final String PERM_EXEMPT = "coltcore.staffmacro.exempt";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final JavaPlugin plugin;
    private final GrimBridge grim;

    private boolean enabled;
    private final Set<String> staffRanks = new HashSet<>();
    private boolean requireGrim = true;

    // cycle thresholds
    private int minSamples = 24;
    private int minRepeats = 6;
    private double minPatternMatch = 0.5D;
    private long periodMinMs = 400L;
    private long periodMaxMs = 5_000L;
    /** Worst repeat, as a fraction of the cycle length. The deciding number. */
    private double maxEnvelope = 0.25D;
    /** A loop must be watched this long before "never crossed" means anything. */
    private long minDurationMs = 90_000L;

    // grim thresholds
    private double minPerfectFraction = 0.9D;
    private double maxDistinctFraction = 0.25D;
    private int grimSamples = 40;

    // chat check
    private boolean checkEnabled = true;
    private long checkTimeoutMs = 60_000L;
    private int codeLength = 4;
    private String checkMessage =
            "&8[&cAFK check&8] &fType &e%code% &fin chat within &e%seconds%s &for you will be "
            + "marked AFK.";
    private String checkPassed = "&aAFK check passed. Thanks.";
    private String checkAnnounce = "&8[&cStaffMacro&8] &7AFK check sent to &f%player%&7.";

    private long windowMs = 120_000L;
    private long moveSampleMs = 250L;
    private long alertCooldownMs = 300_000L;
    private final List<String> flagCommands = new ArrayList<>();

    private final Map<UUID, Tracker> tracked = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAlert = new ConcurrentHashMap<>();
    /** Staff currently believed to be AFK or macroing, and since when. */
    private final Map<UUID, Long> flaggedAfk = new ConcurrentHashMap<>();
    private SchedulerCompat.ManagedTask task;
    private Object luckPerms;

    public StaffMacroModule(JavaPlugin plugin, GrimBridge grim) {
        this.plugin = plugin;
        this.grim = grim;
    }

    /* ------------------------------------------------------------------ */
    /*  Per-player state                                                  */
    /* ------------------------------------------------------------------ */

    /** One input stream: what happened, and exactly when. */
    private static final class Stream {
        final Deque<String> samples = new ArrayDeque<>();
        final Deque<Long> times = new ArrayDeque<>();
        final int max;

        Stream(int max) { this.max = max; }

        void push(String sample, long ts, long windowMs) {
            synchronized (this) {
                this.samples.addLast(sample);
                this.times.addLast(ts);
                while (!this.times.isEmpty() && ts - this.times.peekFirst() > windowMs) {
                    this.times.pollFirst();
                    this.samples.pollFirst();
                }
                while (this.samples.size() > this.max) {
                    this.samples.pollFirst();
                    this.times.pollFirst();
                }
            }
        }

        Snapshot snapshot() {
            synchronized (this) {
                return new Snapshot(this.samples.toArray(new String[0]),
                        this.times.stream().mapToLong(Long::longValue).toArray());
            }
        }

        void clear() {
            synchronized (this) { this.samples.clear(); this.times.clear(); }
        }
    }

    private record Snapshot(String[] samples, long[] times) { }

    private static final class Tracker {
        final Stream clicks = new Stream(400);
        final Stream moves = new Stream(400);
        final Stream chat = new Stream(64);
        long lastMoveSample;
        long lastClick;
        Location lastLocation;

        /** Outstanding chat check, if any. */
        volatile String pendingCode;
        volatile long checkDeadline;
        volatile String suspicion;
        volatile GrimBridge.Verdict verdict;

        void clearStreams() { this.clicks.clear(); this.moves.clear(); this.chat.clear(); }
    }

    private Tracker tracker(Player p) {
        return this.tracked.computeIfAbsent(p.getUniqueId(), k -> new Tracker());
    }

    /* ------------------------------------------------------------------ */
    /*  Lifecycle                                                         */
    /* ------------------------------------------------------------------ */

    public void enable() {
        readConfig();
        if (!this.enabled) return;
        RegisteredServiceProvider<?> rsp = null;
        try {
            Class<?> lp = Class.forName("net.luckperms.api.LuckPerms");
            rsp = Bukkit.getServicesManager().getRegistration(lp);
        } catch (Throwable ignored) { }
        this.luckPerms = rsp == null ? null : rsp.getProvider();
        if (this.grim != null) this.grim.enable(this.grimSamples);
        // Sweep often enough that a pending chat check times out on schedule.
        if (this.task != null) this.task.cancel();
        this.task = SchedulerCompat.timer(this.plugin, this::sweep, 100L, 40L);
        this.plugin.getLogger().info("[StaffMacro] enabled for " + this.staffRanks.size()
                + " rank(s). Grim verification: "
                + (false ? "available" : "UNAVAILABLE")
                + (this.requireGrim ? " (required)" : " (optional)")
                + ". Chat check: " + (this.checkEnabled ? this.checkTimeoutMs / 1000 + "s" : "off"));
    }

    public void reload() {
        boolean was = this.enabled;
        readConfig();
        if (this.enabled && !was) enable();
        if (!this.enabled && was) disable();
    }

    public void disable() {
        if (this.task != null) { this.task.cancel(); this.task = null; }
        this.tracked.clear();
        this.lastAlert.clear();
    }

    private void readConfig() {
        ConfigurationSection c = this.plugin.getConfig().getConfigurationSection("staff-macro");
        if (c == null) { this.enabled = false; return; }
        this.enabled = c.getBoolean("enabled", false);
        this.requireGrim = c.getBoolean("require-grim-verification", true);
        this.windowMs = Math.max(30L, c.getLong("window-seconds", 120L)) * 1000L;
        this.moveSampleMs = Math.max(50L, c.getLong("move-sample-ms", 250L));
        this.alertCooldownMs = Math.max(30L, c.getLong("alert-cooldown-seconds", 300L)) * 1000L;

        this.staffRanks.clear();
        for (String r : c.getStringList("staff-ranks")) {
            if (r != null && !r.isBlank()) this.staffRanks.add(r.toLowerCase(Locale.ROOT).trim());
        }
        if (this.staffRanks.isEmpty()) {
            // Mirrors ColtCorePlugin.STAFF_ROLES. Kept as a literal because
            // this is a static fallback read before the plugin field is used;
            // executive and dev were missing, so a Developer was never
            // analysed for macro behaviour.
            this.staffRanks.addAll(List.of("helper", "jrmod", "mod", "srmod", "jradmin",
                    "admin", "sradmin", "manager", "executive", "dev", "developer",
                    "co-owner", "owner"));
        }

        ConfigurationSection cy = c.getConfigurationSection("cycle");
        if (cy != null) {
            this.minSamples = Math.max(8, cy.getInt("min-samples", 24));
            this.minRepeats = Math.max(3, cy.getInt("min-repeats", 6));
            this.minPatternMatch = cy.getDouble("min-pattern-match", 0.5D);
            this.periodMinMs = Math.max(50L, cy.getLong("period-min-ms", 400L));
            this.periodMaxMs = Math.max(this.periodMinMs + 1, cy.getLong("period-max-ms", 5000L));
            this.maxEnvelope = cy.getDouble("max-envelope", 0.25D);
            this.minDurationMs = Math.max(10L, cy.getLong("min-duration-seconds", 90L)) * 1000L;
        }

        ConfigurationSection g = c.getConfigurationSection("grim");
        if (g != null) {
            this.minPerfectFraction = g.getDouble("min-perfect-fraction", 0.9D);
            this.maxDistinctFraction = g.getDouble("max-distinct-fraction", 0.25D);
            this.grimSamples = Math.max(10, g.getInt("samples", 40));
        }

        ConfigurationSection cc = c.getConfigurationSection("chat-check");
        if (cc != null) {
            this.checkEnabled = cc.getBoolean("enabled", true);
            this.checkTimeoutMs = Math.max(10L, cc.getLong("timeout-seconds", 60L)) * 1000L;
            this.codeLength = Math.max(3, Math.min(8, cc.getInt("code-length", 4)));
            this.checkMessage = cc.getString("message", this.checkMessage);
            this.checkPassed = cc.getString("passed-message", this.checkPassed);
            this.checkAnnounce = cc.getString("announce", this.checkAnnounce);
        }

        this.flagCommands.clear();
        for (String s : c.getStringList("flag-commands")) {
            if (s != null && !s.isBlank()) this.flagCommands.add(s.trim());
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Who is staff                                                      */
    /* ------------------------------------------------------------------ */

    public boolean isStaff(Player p) {
        if (p.hasPermission(PERM_EXEMPT)) return false;
        String group = primaryGroup(p);
        if (group != null && this.staffRanks.contains(group)) return true;
        // Fallback for servers not on LuckPerms: the existing staff node.
        return p.hasPermission("coltcore.staff");
    }

    /** Null when LuckPerms is not installed or the user is not cached. */
    public String primaryGroup(Player p) {
        if (this.luckPerms == null) return null;
        try {
            Object userManager = this.luckPerms.getClass()
                    .getMethod("getUserManager").invoke(this.luckPerms);
            Object user = userManager.getClass()
                    .getMethod("getUser", UUID.class).invoke(userManager, p.getUniqueId());
            if (user == null) return null;
            Object group = user.getClass().getMethod("getPrimaryGroup").invoke(user);
            return group == null ? null : String.valueOf(group).toLowerCase(Locale.ROOT);
        } catch (Throwable ex) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Capture                                                           */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent event) {
        if (!this.enabled) return;
        Player p = event.getPlayer();
        if (!isStaff(p)) return;
        click(tracker(p));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!this.enabled) return;
        Action a = event.getAction();
        if (a != Action.LEFT_CLICK_AIR && a != Action.LEFT_CLICK_BLOCK
                && a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = event.getPlayer();
        if (!isStaff(p)) return;
        click(tracker(p));
    }

    /**
     * A click is recorded as the gap since the previous one, quantised to 10ms.
     *
     * The gap is what repeats in a macro, not the absolute time, so this is the
     * sequence the cycle detector needs. 10ms buckets keep normal network and
     * scheduler noise from splitting one pattern into many.
     */
    private void click(Tracker t) {
        long now = System.currentTimeMillis();
        long gap = t.lastClick == 0L ? 0L : now - t.lastClick;
        t.lastClick = now;
        if (gap <= 0L) return;
        t.clicks.push(String.valueOf(Math.round(gap / 10.0D) * 10), now, this.windowMs);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!this.enabled) return;
        Player p = event.getPlayer();
        if (!isStaff(p)) return;
        Tracker t = tracker(p);
        long now = System.currentTimeMillis();
        if (now - t.lastMoveSample < this.moveSampleMs) return;
        t.lastMoveSample = now;

        Location to = event.getTo();
        if (to == null) return;
        Location from = t.lastLocation == null ? event.getFrom() : t.lastLocation;
        t.lastLocation = to.clone();
        // Quantised delta plus rounded look angle. Two samples only collide when
        // the motion was repeated, not merely when the player passed nearby.
        String sample = String.format(Locale.ROOT, "%.2f/%.2f/%.2f/%d/%d",
                to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ(),
                Math.round(to.getYaw()), Math.round(to.getPitch()));
        t.moves.push(sample, now, this.windowMs);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChatSample(AsyncPlayerChatEvent event) {
        if (!this.enabled) return;
        Player p = event.getPlayer();
        if (!isStaff(p)) return;
        tracker(p).chat.push(ChatGuardModule.normalise(event.getMessage()),
                System.currentTimeMillis(), this.windowMs);
    }

    /** Answers to an outstanding AFK check, checked before anything else. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCheckAnswer(AsyncPlayerChatEvent event) {
        if (!this.enabled) return;
        Tracker t = this.tracked.get(event.getPlayer().getUniqueId());
        if (t == null || t.pendingCode == null) return;
        if (!event.getMessage().trim().equalsIgnoreCase(t.pendingCode)) return;
        event.setCancelled(true);
        passed(event.getPlayer(), t);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.flaggedAfk.remove(event.getPlayer().getUniqueId());
        Tracker t = this.tracked.remove(event.getPlayer().getUniqueId());
        if (this.grim != null) this.grim.forget(event.getPlayer().getUniqueId());
        // Logging out mid-check is not an answer, but it is also not proof they
        // were AFK â€” they are gone either way, so drop it.
        if (t != null) t.pendingCode = null;
    }

    /* ------------------------------------------------------------------ */
    /*  Stage one: the repeating cycle                                    */
    /* ------------------------------------------------------------------ */

    /**
     * A repeating cycle found in one input stream.
     *
     * @param period       length of the cycle in samples
     * @param repeats      how many times it came round
     * @param matchRatio   0-1, how much of the pattern repeated exactly
     * @param meanPeriodMs average wall-clock length of one repeat
     * @param variation    coefficient of variation of that length
     */
    public record Cycle(int period, int repeats, double matchRatio,
                        double meanPeriodMs, double variation,
                        double maxDeviationMs, long spanMs) {

        /**
         * The worst repeat as a fraction of the cycle length.
         *
         * This is the bound the macro never crosses. 0.0 is a perfect loop;
         * 0.16 is a macro jittering by Â±40ms on a one-second cycle; a human
         * sits far above both because sooner or later they stop for a moment.
         */
        public double envelope() {
            return this.meanPeriodMs <= 0 ? Double.MAX_VALUE
                    : this.maxDeviationMs / this.meanPeriodMs;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "period=%d samples (%.0fms), envelope %.3f (worst repeat %.0fms off), "
                    + "variation %.4f, %d repeats over %ds, %.0f%% identical",
                    this.period, this.meanPeriodMs, envelope(), this.maxDeviationMs,
                    this.variation, this.repeats, this.spanMs / 1000, this.matchRatio * 100);
        }
    }

    /**
     * Finds the tightest repeating cycle in a sample sequence.
     *
     * <p>For every candidate period the samples are compared against the ones
     * one period earlier; the best match ratio wins. A ratio of 1.0 is an exact
     * loop, and {@code minMatch} below 1.0 is what allows a macro that varies
     * part of its cycle to still be recognised as the same loop.
     *
     * <p>The returned cycle then has to survive the timing test in
     * {@link #cycleReason}: matching content is common (mining the same block,
     * walking the same corridor), matching content on a metronome is not.
     *
     * @return null when nothing repeats often enough
     */
    public static Cycle detectCycle(String[] samples, long[] times,
                                    int minSamples, int minRepeats, double minMatch) {
        int n = Math.min(samples.length, times.length);
        if (n < minSamples) return null;
        int maxPeriod = n / Math.max(2, minRepeats);
        if (maxPeriod < 1) return null;

        double[] ratios = new double[maxPeriod + 1];
        double bestRatio = 0.0D;
        for (int p = 1; p <= maxPeriod; p++) {
            int compared = n - p;
            if (compared <= 0) break;
            int hits = 0;
            for (int i = p; i < n; i++) {
                if (samples[i].equals(samples[i - p])) hits++;
            }
            ratios[p] = hits / (double) compared;
            if (ratios[p] > bestRatio) bestRatio = ratios[p];
        }
        if (bestRatio < minMatch) return null;

        // Take the SHORTEST period that scores near the best, not the highest
        // scorer. A cycle with varied actions often scores marginally higher at
        // two or three times its true length, and reporting 2000ms for a
        // one-second loop both misplaces it in the period window and flatters
        // its timing variance, because jitter averages out over more steps.
        int bestPeriod = -1;
        for (int p = 1; p <= maxPeriod; p++) {
            if (ratios[p] >= minMatch && ratios[p] >= bestRatio - 0.05D) { bestPeriod = p; break; }
        }
        if (bestPeriod < 1) return null;
        bestRatio = ratios[bestPeriod];

        int repeats = n / bestPeriod;
        if (repeats < minRepeats) return null;

        // Wall-clock length of each repeat.
        double sum = 0.0D;
        int count = 0;
        for (int i = bestPeriod; i < n; i++) {
            sum += times[i] - times[i - bestPeriod];
            count++;
        }
        if (count == 0) return null;
        double mean = sum / count;
        if (mean <= 0.0D) return null;
        double var = 0.0D;
        double maxDev = 0.0D;
        for (int i = bestPeriod; i < n; i++) {
            double d = (times[i] - times[i - bestPeriod]) - mean;
            var += d * d;
            maxDev = Math.max(maxDev, Math.abs(d));
        }
        double sd = Math.sqrt(var / count);
        long span = times[n - 1] - times[0];
        return new Cycle(bestPeriod, repeats, bestRatio, mean, sd / mean, maxDev, span);
    }

    /** The cycle that trips the thresholds, described, or null. */
    private String cycleReason(Tracker t) {
        String[] names = { "clicks", "movement", "chat" };
        Snapshot[] snaps = { t.clicks.snapshot(), t.moves.snapshot(), t.chat.snapshot() };
        for (int i = 0; i < names.length; i++) {
            Cycle c = detectCycle(snaps[i].samples(), snaps[i].times(),
                    this.minSamples, this.minRepeats, this.minPatternMatch);
            if (c == null) continue;
            if (c.meanPeriodMs() < this.periodMinMs || c.meanPeriodMs() > this.periodMaxMs) continue;
            // Watched long enough that a person would have broken stride.
            if (c.spanMs() < this.minDurationMs) continue;
            // The bound was never crossed. A macro is perfect or bounded; a
            // human is neither, given this much time.
            if (c.envelope() > this.maxEnvelope) continue;
            return names[i] + " repeat inside a bound it never crosses: " + c;
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /*  Stages two and three                                              */
    /* ------------------------------------------------------------------ */

    private void sweep() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tracker t = this.tracked.get(p.getUniqueId());
            if (t == null) continue;

            // An outstanding check runs to its deadline before anything else.
            if (t.pendingCode != null) {
                if (now >= t.checkDeadline) {
                    t.pendingCode = null;
                    flag(p, t.suspicion, t.verdict);
                    t.clearStreams();
                }
                continue;
            }
            if (!isStaff(p)) continue;

            String reason = cycleReason(t);
            if (reason == null) continue;

            GrimBridge.Verdict v = (this.grim != null && this.grim.available()) ? this.grim.verdict(p.getUniqueId()) : null;
            if (this.requireGrim) {
                if (v == null) {
                    log(p, reason, null, false
                            ? "not enough Grim samples yet" : "GrimAC unavailable");
                    continue;
                }
                if (!v.machineLike(this.minPerfectFraction, this.maxDistinctFraction)) {
                    log(p, reason, v, "Grim simulation does not agree");
                    continue;
                }
            }

            if (this.checkEnabled) {
                challenge(p, t, reason, v);
            } else {
                flag(p, reason, v);
                t.clearStreams();
            }
        }
    }

    /**
     * Asks the player to prove they are there.
     *
     * The code is random per check, so it cannot be pre-programmed into the
     * macro that triggered the suspicion in the first place.
     */
    private void challenge(Player p, Tracker t, String reason, GrimBridge.Verdict v) {
        long now = System.currentTimeMillis();
        Long last = this.lastAlert.get(p.getUniqueId());
        if (last != null && now - last < this.alertCooldownMs) return;
        this.lastAlert.put(p.getUniqueId(), now);

        StringBuilder code = new StringBuilder(this.codeLength);
        for (int i = 0; i < this.codeLength; i++) {
            code.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        t.pendingCode = code.toString();
        t.checkDeadline = now + this.checkTimeoutMs;
        t.suspicion = reason;
        t.verdict = v;

        p.sendMessage(UiKit.colour(this.checkMessage
                .replace("%code%", t.pendingCode)
                .replace("%seconds%", String.valueOf(this.checkTimeoutMs / 1000))
                .replace("%player%", p.getName())));
        UiKit.sound(p, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.6F);

        String line = UiKit.colour(this.checkAnnounce.replace("%player%", p.getName()));
        for (Player s : Bukkit.getOnlinePlayers()) {
            if (s.hasPermission(PERM_ALERTS) && !s.getUniqueId().equals(p.getUniqueId())) {
                s.sendMessage(line);
            }
        }
        this.plugin.getLogger().info("[StaffMacro] AFK check sent to " + p.getName()
                + " :: " + reason);
    }

    /** They answered. Drop the suspicion and start the samples over. */
    private void passed(Player p, Tracker t) {
        this.flaggedAfk.remove(p.getUniqueId());
        t.pendingCode = null;
        t.suspicion = null;
        t.verdict = null;
        t.clearStreams();
        if (this.grim != null) this.grim.forget(p.getUniqueId());
        p.sendMessage(UiKit.colour(this.checkPassed));
        this.plugin.getLogger().info("[StaffMacro] " + p.getName() + " passed the AFK check.");
        for (Player s : Bukkit.getOnlinePlayers()) {
            if (s.hasPermission(PERM_ALERTS) && !s.getUniqueId().equals(p.getUniqueId())) {
                s.sendMessage(UiKit.colour("&8[&cStaffMacro&8] &f" + p.getName()
                        + " &7answered the AFK check."));
            }
        }
    }

    private void log(Player p, String reason, GrimBridge.Verdict v, String why) {
        long now = System.currentTimeMillis();
        Long last = this.lastAlert.get(p.getUniqueId());
        if (last != null && now - last < this.alertCooldownMs) return;
        this.lastAlert.put(p.getUniqueId(), now);
        this.plugin.getLogger().info("[StaffMacro] " + p.getName() + " shows a fixed-clock cycle ("
                + reason + ") but no check was sent: " + why
                + (v == null ? "" : " [" + v + "]"));
    }

    /**
     * Whether this player is currently believed to be AFK.
     *
     * <p>Used to decide who is fit to review a punishment. Somebody whose input
     * is a loop is, by definition, not reading chat, and handing them an
     * approve/deny prompt would either stall the queue or collect a rubber
     * stamp from a macro. Cleared the moment they answer a check.
     */
    public boolean isFlaggedAfk(Player p) {
        return p != null && this.flaggedAfk.containsKey(p.getUniqueId());
    }

    /** How long they have been flagged, in milliseconds. Zero when not flagged. */
    public long afkFor(Player p) {
        Long since = p == null ? null : this.flaggedAfk.get(p.getUniqueId());
        return since == null ? 0L : System.currentTimeMillis() - since;
    }

    /** Clears the flag, e.g. when a human has plainly interacted. */
    public void clearAfk(Player p) {
        if (p != null) this.flaggedAfk.remove(p.getUniqueId());
    }

    private void flag(Player p, String reason, GrimBridge.Verdict v) {
        this.flaggedAfk.put(p.getUniqueId(), System.currentTimeMillis());
        String group = primaryGroup(p);
        String line = UiKit.colour("&8[&cStaffMacro&8] &e" + p.getName()
                + " &7(&f" + (group == null ? "staff" : group) + "&7) flagged &cAFK / MACRO");
        String detail = UiKit.colour("&8  " + (reason == null ? "unknown pattern" : reason));
        String verified = UiKit.colour("&8  Grim: &7"
                + (v == null ? "not verified" : v.toString()));
        String silent = UiKit.colour("&8  Did not answer the AFK check within "
                + (this.checkTimeoutMs / 1000) + "s.");

        for (Player s : Bukkit.getOnlinePlayers()) {
            if (s.hasPermission(PERM_ALERTS)) {
                s.sendMessage(line);
                s.sendMessage(detail);
                s.sendMessage(verified);
                if (this.checkEnabled) s.sendMessage(silent);
            }
        }
        this.plugin.getLogger().warning(ChatColor.stripColor(line + " :: " + reason
                + " :: " + (v == null ? "unverified" : v.toString())));

        for (String template : this.flagCommands) {
            String cmd = CommandTemplate.expand(template, p.getName())
                    .replace("%reason%", ChatColor.stripColor(reason == null ? "afk macro" : reason));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Command                                                           */
    /* ------------------------------------------------------------------ */

    public boolean command(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> { reload(); sender.sendMessage(UiKit.colour("&aStaff macro detection reloaded.")); }
            case "check" -> {
                if (args.length < 2) { sender.sendMessage(UiKit.colour("&e/staffmacro check <player>")); return true; }
                Player p = Bukkit.getPlayerExact(args[1]);
                if (p == null) { sender.sendMessage(UiKit.colour("&cNot online.")); return true; }
                Tracker t = this.tracked.get(p.getUniqueId());
                sender.sendMessage(UiKit.colour("&8&m----------------------------------------"));
                sender.sendMessage(UiKit.colour("&c&lSTAFF MACRO &7- &f" + p.getName()));
                sender.sendMessage(UiKit.colour("&7rank: &f" + String.valueOf(primaryGroup(p))
                        + "  &7tracked: &f" + (t != null)));
                if (t != null) {
                    String reason = cycleReason(t);
                    sender.sendMessage(UiKit.colour("&7cycle: &f"
                            + (reason == null ? "none inside a fixed bound" : reason)));
                    sender.sendMessage(UiKit.colour("&7pending check: &f"
                            + (t.pendingCode == null ? "no"
                               : Math.max(0L, (t.checkDeadline - System.currentTimeMillis()) / 1000)
                                 + "s left")));
                }
                GrimBridge.Verdict v = this.grim == null ? null : this.grim.verdict(p.getUniqueId());
                sender.sendMessage(UiKit.colour("&7grim: &f"
                        + (v == null ? "no verdict (" + this.grim.sampleCount(p.getUniqueId())
                                       + " samples)" : v.toString())));
                sender.sendMessage(UiKit.colour("&8&m----------------------------------------"));
            }
            case "test" -> {
                if (args.length < 2) { sender.sendMessage(UiKit.colour("&e/staffmacro test <player>")); return true; }
                Player p = Bukkit.getPlayerExact(args[1]);
                if (p == null) { sender.sendMessage(UiKit.colour("&cNot online.")); return true; }
                this.lastAlert.remove(p.getUniqueId());
                challenge(p, tracker(p), "manual test by " + sender.getName(), null);
                sender.sendMessage(UiKit.colour("&aAFK check sent."));
            }
            default -> {
                sender.sendMessage(UiKit.colour("&#00ff00StaffMacro: "
                        + (this.enabled ? "&aon" : "&coff")
                        + " &7grim=" + (false
                            ? "&a" + this.grim.grimVersion() : "&cunavailable")
                        + " &7required=&f" + this.requireGrim));
                sender.sendMessage(UiKit.colour("&7cycle: period &f" + this.periodMinMs + "-"
                        + this.periodMaxMs + "ms&7, envelope <= &f" + this.maxEnvelope
                        + "&7, watched >= &f" + (this.minDurationMs / 1000) + "s"
                        + "&7, match >= &f" + this.minPatternMatch));
                sender.sendMessage(UiKit.colour("&7tracking &f" + this.tracked.size()
                        + " &7staff member(s), ranks: &f" + String.join(", ", this.staffRanks)));
                sender.sendMessage(UiKit.colour("&8/staffmacro <status|check <player>|test <player>|reload>"));
            }
        }
        return true;
    }

    /** Exposed so other modules can present the same numbers. */
    public Map<String, Object> snapshot(Player p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("staff", isStaff(p));
        out.put("rank", primaryGroup(p));
        Tracker t = this.tracked.get(p.getUniqueId());
        out.put("cycle", t == null ? null : cycleReason(t));
        out.put("pending_check", t != null && t.pendingCode != null);
        Object v = this.grim == null ? null : null;
        out.put("grim", v == null ? null : v.toString());
        return out;
    }
}
