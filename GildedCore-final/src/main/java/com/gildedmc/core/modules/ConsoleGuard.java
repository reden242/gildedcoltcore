package com.gildedmc.core.modules;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Screens the server's own console output, so text that never reached the chat
 * event is still checked.
 *
 * <h2>Why the chat listener is not enough</h2>
 * A surprising amount of player text never passes through
 * {@code AsyncPlayerChatEvent}. Chat plugins that render messages themselves,
 * cross-server bridges, staff channels, {@code /msg} handled by another plugin,
 * command arguments, book and sign contents logged by an audit plugin — all of
 * it lands in the console and none of it in the event. Anything a plugin
 * prints, this sees.
 *
 * <h2>What it does with it</h2>
 * Exactly what the chat path does: the same term list, the same phonetic
 * engine, the same advertising patterns, the same ladder. A slur is a slur
 * whether it arrived through chat or through a private message a plugin
 * decided to log.
 *
 * <h2>Not a punishment path of its own</h2>
 * It cannot cancel anything — by the time a line is in the console the message
 * has already been sent. So it reports and escalates through the ladder,
 * and the deletion half is handled by the live listeners.
 *
 * <h2>Loop safety</h2>
 * The guard's own alerts go to the console too. Without care, one detection
 * would print a line containing the offending text, which would be detected,
 * which would print another line. Lines from this plugin are ignored outright,
 * and there is a hard recursion guard on top.
 */
public final class ConsoleGuard {

    private final JavaPlugin plugin;
    private final ChatGuardModule guard;

    private boolean enabled;
    private boolean scanCommands = true;
    private boolean scanChat = true;
    private final List<Pattern> chatPatterns = new ArrayList<>();
    private final List<Pattern> commandPatterns = new ArrayList<>();
    private final List<String> ignore = new ArrayList<>();

    private Handler handler;
    private final ThreadLocal<Boolean> inside = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final AtomicLong scanned = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();

    public ConsoleGuard(JavaPlugin plugin, ChatGuardModule guard) {
        this.plugin = plugin;
        this.guard = guard;
    }

    /* ------------------------------------------------------------------ */
    /*  Lifecycle                                                         */
    /* ------------------------------------------------------------------ */

    public void enable() {
        readConfig();
        detach();
        if (!this.enabled) return;
        this.handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record == null || record.getMessage() == null) return;
                scan(record.getMessage());
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger.getLogger("").addHandler(this.handler);
        this.plugin.getLogger().info("[ConsoleGuard] watching console output ("
                + this.chatPatterns.size() + " chat pattern(s), "
                + this.commandPatterns.size() + " command pattern(s)).");
    }

    public void reload() { enable(); }

    public void disable() { detach(); }

    private void detach() {
        if (this.handler != null) {
            Logger.getLogger("").removeHandler(this.handler);
            this.handler = null;
        }
    }

    private void readConfig() {
        ConfigurationSection c = this.plugin.getConfig().getConfigurationSection("console-guard");
        if (c == null) { this.enabled = false; return; }
        this.enabled = c.getBoolean("enabled", false);
        this.scanChat = c.getBoolean("scan-chat", true);
        this.scanCommands = c.getBoolean("scan-commands", true);

        this.chatPatterns.clear();
        for (String p : c.getStringList("chat-patterns")) compile(p, this.chatPatterns);
        if (this.chatPatterns.isEmpty()) {
            // Covers vanilla, most chat plugins, and the common bridge formats.
            compile("^<(\\w{1,16})>\\s*(.+)$", this.chatPatterns);
            compile("^\\[.*?\\]\\s*<(\\w{1,16})>\\s*(.+)$", this.chatPatterns);
            compile("^(\\w{1,16})\\s*:\\s*(.+)$", this.chatPatterns);
            compile("^\\[CHAT\\]\\s*(\\w{1,16})\\s*[:>]\\s*(.+)$", this.chatPatterns);
        }
        this.commandPatterns.clear();
        for (String p : c.getStringList("command-patterns")) compile(p, this.commandPatterns);
        if (this.commandPatterns.isEmpty()) {
            compile("^(\\w{1,16}) issued server command: /(.+)$", this.commandPatterns);
            compile("^\\[.*?\\]\\s*(\\w{1,16}) ran command /(.+)$", this.commandPatterns);
        }
        this.ignore.clear();
        for (String s : c.getStringList("ignore-containing")) {
            if (s != null && !s.isBlank()) this.ignore.add(s.toLowerCase(Locale.ROOT));
        }
        // Never read our own output back in.
        this.ignore.add("[textguard]");
        this.ignore.add("[consoleguard]");
        this.ignore.add("[localai]");
        this.ignore.add("[offend]");
        this.ignore.add("[review]");
        this.ignore.add("[staffmacro]");
    }

    private void compile(String regex, List<Pattern> into) {
        if (regex == null || regex.isBlank()) return;
        try {
            into.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
        } catch (Exception ex) {
            this.plugin.getLogger().warning("[ConsoleGuard] bad pattern '" + regex
                    + "': " + ex.getMessage());
        }
    }

    public boolean isEnabled() { return this.enabled; }

    /* ------------------------------------------------------------------ */
    /*  Scanning                                                          */
    /* ------------------------------------------------------------------ */

    void scan(String rawLine) {
        if (!this.enabled || this.guard == null) return;
        if (Boolean.TRUE.equals(this.inside.get())) return;      // recursion guard
        String line = org.bukkit.ChatColor.stripColor(rawLine);
        if (line == null || line.isBlank()) return;
        String lower = line.toLowerCase(Locale.ROOT);
        for (String skip : this.ignore) {
            if (lower.contains(skip)) return;
        }
        this.scanned.incrementAndGet();

        String player = null;
        String text = null;
        if (this.scanChat) {
            for (Pattern p : this.chatPatterns) {
                Matcher m = p.matcher(line);
                if (m.find() && m.groupCount() >= 2) {
                    player = m.group(1);
                    text = m.group(2);
                    break;
                }
            }
        }
        if (text == null && this.scanCommands) {
            for (Pattern p : this.commandPatterns) {
                Matcher m = p.matcher(line);
                if (m.find() && m.groupCount() >= 2) {
                    player = m.group(1);
                    // Only the arguments. The command name itself is not chat.
                    String command = m.group(2);
                    int space = command.indexOf(' ');
                    text = space < 0 ? null : command.substring(space + 1);
                    break;
                }
            }
        }
        if (player == null || text == null || text.isBlank()) return;

        final String who = player;
        final String said = text;
        this.inside.set(Boolean.TRUE);
        try {
            String category = this.guard.offends(said);
            if (category == null) {
                String heard = this.guard.phoneticHit(said);
                if (heard != null) category = ChatGuardModule.CAT_HATE;
            }
            if (category == null) {
                PatternPack.Hit structural = PatternPack.match(said);
                if (structural != null) category = structural.category();
            }
            if (category == null) return;
            this.hits.incrementAndGet();
            final String cat = category;
            // Back to the main thread: the ladder dispatches commands.
            com.gildedmc.core.SchedulerCompat.run(this.plugin, () -> {
                this.plugin.getLogger().warning("[ConsoleGuard] " + who + " -> " + cat
                        + " in console output: " + said);
                this.plugin.getLogger().info("[ConsoleGuard] console output is recorded only;"
                        + " it never trains or punishes through the chat filter.");
            });
        } catch (Throwable ex) {
            // A logging handler that throws would take the logger down with it.
            this.plugin.getLogger().warning("[ConsoleGuard] scan failed: "
                    + ex.getClass().getSimpleName());
        } finally {
            this.inside.set(Boolean.FALSE);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Reporting                                                         */
    /* ------------------------------------------------------------------ */

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", this.enabled);
        out.put("lines_scanned", this.scanned.get());
        out.put("detections", this.hits.get());
        out.put("chat_patterns", this.chatPatterns.size());
        out.put("command_patterns", this.commandPatterns.size());
        return out;
    }
}
