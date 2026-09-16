/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.papermc.paper.event.player.AsyncChatEvent
 *  net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
 *  net.luckperms.api.LuckPerms
 *  net.luckperms.api.model.user.User
 *  org.bukkit.Bukkit
 *  org.bukkit.ChatColor
 *  org.bukkit.entity.Player
 *  org.bukkit.event.EventHandler
 *  org.bukkit.event.EventPriority
 *  org.bukkit.event.Listener
 *  org.bukkit.plugin.RegisteredServiceProvider
 *  org.bukkit.plugin.java.JavaPlugin
 */
package com.gildedmc.core.modules;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChatLimiterModule
implements Listener {
    private final JavaPlugin plugin;
    private final Map<UUID, PlayerState> states = new HashMap<UUID, PlayerState>();
    private LuckPerms luckPerms;

    public ChatLimiterModule(JavaPlugin plugin) {
        this.plugin = plugin;
        RegisteredServiceProvider provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        this.luckPerms = provider == null ? null : (LuckPerms)provider.getProvider();
    }

    public void reload() {
        this.states.clear();
        RegisteredServiceProvider provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        this.luckPerms = provider == null ? null : (LuckPerms)provider.getProvider();
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onAsyncChat(AsyncChatEvent event) {
        if (!this.plugin.getConfig().getBoolean("chat-limiter.enabled", true)) {
            return;
        }
        Player player = event.getPlayer();
        String bypass = this.plugin.getConfig().getString("chat-limiter.bypass-permission", "gildedshield.chatlimit.bypass");
        if (bypass != null && !bypass.isBlank() && player.hasPermission(bypass)) {
            return;
        }
        String group = this.primaryGroup(player);
        if (this.plugin.getConfig().getStringList("chat-limiter.rank-scaling.unlimited-groups").stream().anyMatch(group::equalsIgnoreCase)) {
            return;
        }
        String normalized = this.normalize(PlainTextComponentSerializer.plainText().serialize(event.message()));
        if (normalized.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        PlayerState state = this.states.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerState());
        double multiplier = Math.max(1.0, this.plugin.getConfig().getDouble("chat-limiter.rank-scaling.multipliers." + group, 1.0));
        state.decay(now, this.plugin.getConfig().getDouble("chat-limiter.limits.dynamic-decay-per-second", 1.25) * multiplier);
        state.prune(now, this.plugin.getConfig().getLong("chat-limiter.limits.history-window-ms", 12000L));
        CheckResult result = this.checkMessage(state, normalized, now, multiplier);
        state.add(normalized, now, this.plugin.getConfig().getInt("chat-limiter.limits.history-size", 8));
        state.points += result.cost();
        double maxPoints = this.plugin.getConfig().getDouble("chat-limiter.limits.dynamic-points-max", 8.0) * multiplier;
        if (result.block() || state.points > maxPoints) {
            event.setCancelled(true);
            player.sendMessage(this.color(this.plugin.getConfig().getString("chat-limiter.messages.blocked", "&cPlease slow down.")));
        }
    }

    private CheckResult checkMessage(PlayerState state, String message, long now, double multiplier) {
        int exactRepeats = 0;
        int smallMessages = 0;
        long exactWindow = this.plugin.getConfig().getLong("chat-limiter.limits.exact-repeat-window-ms", 8000L);
        long smallWindow = this.plugin.getConfig().getLong("chat-limiter.limits.small-message-window-ms", 4000L);
        int smallLength = this.plugin.getConfig().getInt("chat-limiter.limits.small-message-max-length", 8);
        for (MessageEntry entry : state.history) {
            if (entry.message().equals(message) && now - entry.time() <= exactWindow) {
                ++exactRepeats;
            }
            if (entry.message().length() > smallLength || now - entry.time() > smallWindow) continue;
            ++smallMessages;
        }
        int exactMax = this.scaled(this.plugin.getConfig().getInt("chat-limiter.limits.exact-repeat-max", 2), multiplier);
        int smallMax = this.scaled(this.plugin.getConfig().getInt("chat-limiter.limits.small-message-max", 4), multiplier);
        if (exactRepeats >= exactMax) {
            return new CheckResult(true, this.plugin.getConfig().getDouble("chat-limiter.limits.cost-repeat", 4.0));
        }
        if (message.length() <= smallLength && smallMessages >= smallMax) {
            return new CheckResult(true, this.plugin.getConfig().getDouble("chat-limiter.limits.cost-small", 1.8));
        }
        if (this.hasRepeatedCharacterSpam(message)) {
            return new CheckResult(true, this.plugin.getConfig().getDouble("chat-limiter.limits.cost-repeat", 4.0));
        }
        int minLength = this.plugin.getConfig().getInt("chat-limiter.limits.similarity-min-length", 10);
        if (message.length() >= minLength) {
            for (MessageEntry entry : state.history) {
                if (entry.message().length() < minLength || !(this.similarity(message, entry.message()) >= this.plugin.getConfig().getDouble("chat-limiter.limits.similarity-threshold", 0.72)) && !(this.commonPartRatio(message, entry.message()) >= this.plugin.getConfig().getDouble("chat-limiter.limits.common-part-threshold", 0.65))) continue;
                return new CheckResult(true, this.plugin.getConfig().getDouble("chat-limiter.limits.cost-similar", 3.0));
            }
        }
        double cost = message.length() <= smallLength ? this.plugin.getConfig().getDouble("chat-limiter.limits.cost-small", 1.8) : this.plugin.getConfig().getDouble("chat-limiter.limits.cost-normal", 1.0);
        return new CheckResult(false, cost);
    }

    private int scaled(int value, double multiplier) {
        return Math.max(value, (int)Math.ceil((double)value * multiplier));
    }

    private boolean hasRepeatedCharacterSpam(String message) {
        int threshold = this.plugin.getConfig().getInt("chat-limiter.limits.repeated-character-min-run", 8);
        int longest = 1;
        int current = 1;
        for (int i = 1; i < message.length(); ++i) {
            if (message.charAt(i) == message.charAt(i - 1) && message.charAt(i) != ' ') {
                longest = Math.max(longest, ++current);
                continue;
            }
            current = 1;
        }
        return longest >= threshold;
    }

    private String normalize(String message) {
        return message.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").trim();
    }

    private double similarity(String left, String right) {
        int distance = this.levenshtein(left, right);
        int max = Math.max(left.length(), right.length());
        return max == 0 ? 1.0 : 1.0 - (double)distance / (double)max;
    }

    private int levenshtein(String left, String right) {
        int[] costs = new int[right.length() + 1];
        for (int j = 0; j < costs.length; ++j) {
            costs[j] = j;
        }
        for (int i = 1; i <= left.length(); ++i) {
            costs[0] = i;
            int previous = i - 1;
            for (int j = 1; j <= right.length(); ++j) {
                int old = costs[j];
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                costs[j] = Math.min(Math.min(costs[j] + 1, costs[j - 1] + 1), previous + cost);
                previous = old;
            }
        }
        return costs[right.length()];
    }

    private double commonPartRatio(String left, String right) {
        int longest = 0;
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int i = 1; i <= left.length(); ++i) {
            for (int j = 1; j <= right.length(); ++j) {
                if (left.charAt(i - 1) == right.charAt(j - 1)) {
                    current[j] = previous[j - 1] + 1;
                    longest = Math.max(longest, current[j]);
                    continue;
                }
                current[j] = 0;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return (double)longest / (double)Math.min(left.length(), right.length());
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes((char)'&', (String)(message == null ? "" : message));
    }

    private String primaryGroup(Player player) {
        if (this.luckPerms == null) {
            return "default";
        }
        User user = this.luckPerms.getUserManager().getUser(player.getUniqueId());
        return user == null ? "default" : user.getPrimaryGroup().toLowerCase(Locale.ROOT);
    }

    private static final class PlayerState {
        private final Deque<MessageEntry> history = new ArrayDeque<MessageEntry>();
        private double points;
        private long lastUpdate;

        private PlayerState() {
        }

        private void decay(long now, double decayPerSecond) {
            if (this.lastUpdate == 0L) {
                this.lastUpdate = now;
                return;
            }
            this.points = Math.max(0.0, this.points - Math.max(0.0, (double)(now - this.lastUpdate) / 1000.0) * decayPerSecond);
            this.lastUpdate = now;
        }

        private void prune(long now, long windowMs) {
            Iterator<MessageEntry> iterator = this.history.iterator();
            while (iterator.hasNext()) {
                if (now - iterator.next().time() <= windowMs) continue;
                iterator.remove();
            }
        }

        private void add(String message, long now, int maxSize) {
            this.history.addLast(new MessageEntry(message, now));
            while (this.history.size() > maxSize) {
                this.history.removeFirst();
            }
        }
    }

    private record CheckResult(boolean block, double cost) {
    }

    private record MessageEntry(String message, long time) {
    }
}

