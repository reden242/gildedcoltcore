package com.coltcore.core.modules;

import com.coltcore.core.SchedulerCompat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Human sign-off on automated punishments, and the training loop it feeds.
 *
 * <h2>Two problems, one answer</h2>
 * The detector's weak axis was never precision — it was recall, because the
 * classifier only ever saw as much varied labelled data as the server happened
 * to generate. Meanwhile the thing nobody wants is software muting players
 * unsupervised while staff are sitting right there.
 *
 * <p>Both are solved by asking. When a detection fires and an eligible reviewer
 * is online, the punishment is held and offered to them as an approve or deny
 * prompt. Their answer decides what happens — and is recorded as a training
 * example carrying the highest-quality label the system can obtain: a human who
 * looked at the actual message in its actual context. Every review makes the
 * next detection better, which is the opposite of a model marking its own work.
 *
 * <h2>Who is eligible</h2>
 * Holders of {@code coltcore.review} — meant for admin and above. If nobody
 * is eligible, the configured fallback decides, and by default that is to apply
 * the punishment exactly as it would have been without this module.
 *
 * <h2>Nothing waits forever</h2>
 * A pending review has a deadline. Past it the fallback applies, so a queue
 * nobody answers cannot become a silent amnesty.
 */
public final class ReviewModule {

    public static final String PERM = "coltcore.review";

    /** What happens when no eligible reviewer answers. */
    public enum Fallback { APPLY, DROP, ESCALATE }

    private final JavaPlugin plugin;
    private final LocalAiModule local;

    private boolean enabled;
    private long timeoutMs = 120_000L;
    private Fallback fallback = Fallback.APPLY;
    private final List<String> reviewedCategories = new ArrayList<>();
    private boolean requireForAll;
    private int maxPending = 20;

    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private SchedulerCompat.ManagedTask task;

    public ReviewModule(JavaPlugin plugin, LocalAiModule local) {
        this.plugin = plugin;
        this.local = local;
    }

    /* ------------------------------------------------------------------ */
    /*  Model                                                             */
    /* ------------------------------------------------------------------ */

    /** One punishment awaiting a human answer. */
    public static final class Pending {
        final int id;
        final String player;
        final String category;
        final String text;
        final String source;
        final String proposed;
        final long deadline;
        final Runnable onApprove;
        volatile boolean settled;

        Pending(int id, String player, String category, String text, String source,
                String proposed, long deadline, Runnable onApprove) {
            this.id = id;
            this.player = player;
            this.category = category;
            this.text = text;
            this.source = source;
            this.proposed = proposed;
            this.deadline = deadline;
            this.onApprove = onApprove;
        }

        public int id() { return this.id; }

        public String player() { return this.player; }

        public String category() { return this.category; }
    }

    /* ------------------------------------------------------------------ */
    /*  Lifecycle                                                         */
    /* ------------------------------------------------------------------ */

    public void enable() {
        readConfig();
        if (this.task != null) this.task.cancel();
        if (!this.enabled) return;
        this.task = SchedulerCompat.timer(this.plugin, this::sweep, 40L, 40L);
        this.plugin.getLogger().info("[Review] enabled. Reviewed categories: "
                + (this.requireForAll ? "all" : String.join(", ", this.reviewedCategories))
                + ". No reviewer -> " + this.fallback.name().toLowerCase(Locale.ROOT) + ".");
    }

    public void reload() { enable(); }

    public void disable() {
        if (this.task != null) { this.task.cancel(); this.task = null; }
        this.pending.clear();
    }

    private void readConfig() {
        ConfigurationSection c = this.plugin.getConfig().getConfigurationSection("review");
        if (c == null) { this.enabled = false; return; }
        this.enabled = c.getBoolean("enabled", false);
        this.timeoutMs = Math.max(10L, c.getLong("timeout-seconds", 120L)) * 1000L;
        this.maxPending = Math.max(1, c.getInt("max-pending", 20));
        try {
            this.fallback = Fallback.valueOf(
                    c.getString("no-reviewer", "APPLY").toUpperCase(Locale.ROOT).trim());
        } catch (IllegalArgumentException ex) {
            this.fallback = Fallback.APPLY;
        }
        this.reviewedCategories.clear();
        for (String s : c.getStringList("categories")) {
            if (s != null && !s.isBlank()) {
                this.reviewedCategories.add(s.toLowerCase(Locale.ROOT).trim());
            }
        }
        this.requireForAll = this.reviewedCategories.contains("*");
    }

    public boolean isEnabled() { return this.enabled; }

    /* ------------------------------------------------------------------ */
    /*  Eligibility                                                       */
    /* ------------------------------------------------------------------ */

    /** Reviewers who could answer right now: permitted and online. */
    public List<Player> availableReviewers() {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.hasPermission(PERM)) continue;
            out.add(p);
        }
        return out;
    }

    /** Whether this category is held for a human at all. */
    public boolean reviewed(String category) {
        if (!this.enabled) return false;
        if (this.requireForAll) return true;
        return this.reviewedCategories.contains(category.toLowerCase(Locale.ROOT));
    }

    /* ------------------------------------------------------------------ */
    /*  Submitting                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Offers a punishment for review.
     *
     * @param onApprove what to run if a human says yes, or if the fallback is
     *                  to apply. Never run more than once.
     * @return true when the decision was handed to a human, so the caller must
     *         not apply anything itself
     */
    public boolean submit(String player, String category, String text, String source,
                          String proposed, Runnable onApprove) {
        if (!reviewed(category)) return false;
        List<Player> reviewers = availableReviewers();
        if (reviewers.isEmpty()) {
            // Nobody to ask. Say so plainly in the log rather than silently
            // choosing, because "the AI muted somebody at 4am" should be
            // traceable to this decision.
            this.plugin.getLogger().info("[Review] no eligible reviewer for " + category
                    + " on " + player + " -> " + this.fallback.name().toLowerCase(Locale.ROOT));
            return switch (this.fallback) {
                case APPLY -> false;                    // caller applies as normal
                case DROP -> true;                      // swallowed, nothing happens
                case ESCALATE -> { alertStaff(player, category, text); yield true; }
            };
        }
        if (this.pending.size() >= this.maxPending) {
            this.plugin.getLogger().warning("[Review] queue is full (" + this.maxPending
                    + "); applying " + category + " on " + player + " without review.");
            return false;
        }

        int id = this.nextId.getAndIncrement();
        Pending p = new Pending(id, player, category, text, source, proposed,
                System.currentTimeMillis() + this.timeoutMs, onApprove);
        this.pending.put(id, p);
        for (Player reviewer : reviewers) prompt(reviewer, p);
        return true;
    }

    private void alertStaff(String player, String category, String text) {
        String line = UiKit.colour("&8[&eReview&8] &7unreviewed &f" + category
                + " &7by &f" + player + "&7: &f" + text);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(PERM) || p.hasPermission(ChatGuardModule.PERM_ALERTS)) {
                p.sendMessage(line);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Prompting                                                         */
    /* ------------------------------------------------------------------ */

    private void prompt(Player reviewer, Pending p) {
        reviewer.sendMessage(UiKit.colour("&8&m----------------------------------------"));
        reviewer.sendMessage(UiKit.colour("&e&lREVIEW &8#" + p.id + " &7- &f" + p.player));
        reviewer.sendMessage(UiKit.colour("&7detected: &f" + p.category
                + " &8(via " + p.source + ")"));
        reviewer.sendMessage(UiKit.colour("&7message: &f" + p.text));
        reviewer.sendMessage(UiKit.colour("&7proposed: &c" + p.proposed));

        Component approve = legacy("&a&l[ APPROVE ]")
                .clickEvent(ClickEvent.runCommand("/review approve " + p.id))
                .hoverEvent(HoverEvent.showText(legacy("&7Apply &c" + p.proposed
                        + " &7to &f" + p.player + "&7.\n&8Also teaches the model this is "
                        + p.category + ".")));
        Component deny = legacy("&c&l[ DENY ]")
                .clickEvent(ClickEvent.runCommand("/review deny " + p.id))
                .hoverEvent(HoverEvent.showText(legacy("&7Punish nobody.\n&8Also teaches "
                        + "the model this message was fine.")));
        reviewer.sendMessage(approve.append(legacy("   ")).append(deny));
        reviewer.sendMessage(UiKit.colour("&8auto-" + this.fallback.name().toLowerCase(Locale.ROOT)
                + " in " + (this.timeoutMs / 1000) + "s if nobody answers"));
        reviewer.sendMessage(UiKit.colour("&8&m----------------------------------------"));
        UiKit.sound(reviewer, org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, 1.4F);
    }

    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(UiKit.colour(text));
    }

    /* ------------------------------------------------------------------ */
    /*  Deciding                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * A human said yes.
     *
     * <p>The message is recorded as an example of its category. This is the
     * best label the system will ever get, and the reason the review queue is
     * worth having beyond the sign-off itself.
     */
    public void approve(CommandSender reviewer, int id) {
        Pending p = take(reviewer, id);
        if (p == null) return;
        if (this.local != null) this.local.learn(p.text, p.category, "admin-review");
        try {
            p.onApprove.run();
        } catch (Throwable ex) {
            reviewer.sendMessage(UiKit.colour("&cThe punishment failed to apply: "
                    + ex.getClass().getSimpleName()));
            return;
        }
        broadcast("&a" + reviewer.getName() + " &7approved &8#" + p.id + " &7- &f"
                + p.category + " &7on &f" + p.player);
    }

    /**
     * A human said no.
     *
     * <p>Recorded as {@code clean}, which is the more valuable of the two
     * labels: false positives are exactly what a classifier trained on its own
     * successes never learns to avoid.
     */
    public void deny(CommandSender reviewer, int id) {
        Pending p = take(reviewer, id);
        if (p == null) return;
        if (this.local != null) this.local.learn(p.text, LocalAiModule.LABEL_CLEAN, "admin-review");
        broadcast("&c" + reviewer.getName() + " &7denied &8#" + p.id + " &7- &f"
                + p.player + " &7was not punished");
        this.plugin.getLogger().info("[Review] " + reviewer.getName() + " denied #" + p.id
                + " (" + p.category + "): " + p.text);

        // FALSE POSITIVE -> remember the flagged word(s).
        if (("advertising".equals(p.category) || "light-advertising".equals(p.category))
                && ((com.coltcore.core.ColtCorePlugin) this.plugin).chatGuard() != null && p.text != null) {
            java.util.regex.Matcher tm = java.util.regex.Pattern
                    .compile("[A-Za-z0-9][A-Za-z0-9.\\-]{2,}").matcher(p.text);
            int added = 0;
            while (tm.find() && added < 3) {
                String tok = tm.group();
                boolean domainish = tok.contains(".")
                        || (tok.matches(".*\\d.*") && tok.matches(".*[a-zA-Z].*"));
                if (domainish && ((com.coltcore.core.ColtCorePlugin) this.plugin).chatGuard().whitelistAdvertToken(tok)) added++;
            }
            if (added > 0) broadcast("&8#" + p.id + " &7whitelisted &f" + added
                    + "&7 flagged token(s)");
        }
    }

    private Pending take(CommandSender reviewer, int id) {
        Pending p = this.pending.get(id);
        if (p == null) {
            reviewer.sendMessage(UiKit.colour("&cReview #" + id + " is not open."));
            return null;
        }
        if (p.settled) {
            reviewer.sendMessage(UiKit.colour("&eReview #" + id + " was already decided."));
            return null;
        }
        p.settled = true;
        this.pending.remove(id);
        return p;
    }

    private void broadcast(String message) {
        String line = UiKit.colour("&8[&eReview&8] " + message);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(PERM)) p.sendMessage(line);
        }
        this.plugin.getLogger().info(org.bukkit.ChatColor.stripColor(line));
    }

    /* ------------------------------------------------------------------ */
    /*  Timeouts                                                          */
    /* ------------------------------------------------------------------ */

    private void sweep() {
        long now = System.currentTimeMillis();
        for (Pending p : new ArrayList<>(this.pending.values())) {
            if (now < p.deadline || p.settled) continue;
            p.settled = true;
            this.pending.remove(p.id);
            switch (this.fallback) {
                case APPLY -> {
                    try { p.onApprove.run(); } catch (Throwable ignored) { }
                    broadcast("&8#" + p.id + " &7timed out - applied &f" + p.proposed
                            + " &7to &f" + p.player);
                }
                case DROP -> broadcast("&8#" + p.id + " &7timed out - dropped, &f"
                        + p.player + " &7was not punished");
                case ESCALATE -> {
                    alertStaff(p.player, p.category, p.text);
                    broadcast("&8#" + p.id + " &7timed out - left for staff");
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Command                                                           */
    /* ------------------------------------------------------------------ */

    public boolean command(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM)) {
            sender.sendMessage(UiKit.colour("&cNo permission."));
            return true;
        }
        if (!this.enabled) {
            sender.sendMessage(UiKit.colour("&cReview is disabled in config."));
            return true;
        }
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "approve", "deny" -> {
                if (args.length < 2) {
                    sender.sendMessage(UiKit.colour("&e/review " + sub + " <id>"));
                    return true;
                }
                int id;
                try { id = Integer.parseInt(args[1].trim()); }
                catch (NumberFormatException ex) {
                    sender.sendMessage(UiKit.colour("&cThat is not an id."));
                    return true;
                }
                if (sub.equals("approve")) approve(sender, id); else deny(sender, id);
            }
            case "reload" -> { reload(); sender.sendMessage(UiKit.colour("&aReview reloaded.")); }
            default -> {
                List<Player> reviewers = availableReviewers();
                sender.sendMessage(UiKit.colour("&8&m----------------------------------------"));
                sender.sendMessage(UiKit.colour("&e&lREVIEW QUEUE &7- &f"
                        + this.pending.size() + " &7pending"));
                sender.sendMessage(UiKit.colour("&7eligible reviewers online: &f"
                        + reviewers.size() + " &8(not AFK-flagged)"));
                long now = System.currentTimeMillis();
                for (Pending p : this.pending.values()) {
                    sender.sendMessage(UiKit.colour("&8 #" + p.id + " &f" + p.player
                            + " &7" + p.category + " &8- " + Math.max(0L, (p.deadline - now) / 1000)
                            + "s left"));
                    sender.sendMessage(UiKit.colour("&8    " + p.text));
                }
                if (this.pending.isEmpty()) {
                    sender.sendMessage(UiKit.colour("&7Nothing waiting."));
                }
                sender.sendMessage(UiKit.colour("&8/review <list|approve <id>|deny <id>|reload>"));
                sender.sendMessage(UiKit.colour("&8&m----------------------------------------"));
            }
        }
        return true;
    }

    /** For the status page. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", this.enabled);
        out.put("pending", this.pending.size());
        out.put("reviewers_available", availableReviewers().size());
        out.put("fallback", this.fallback.name().toLowerCase(Locale.ROOT));
        return out;
    }

    public List<String> subCommands() {
        return List.of("list", "approve", "deny", "reload");
    }

    /** Open review ids, for tab completion. */
    public List<String> openIds() {
        List<String> out = new ArrayList<>();
        for (Integer id : this.pending.keySet()) out.add(String.valueOf(id));
        return out;
    }
}



