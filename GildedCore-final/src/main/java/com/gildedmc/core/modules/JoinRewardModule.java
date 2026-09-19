package com.gildedmc.core.modules;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * First-join welcome grant, kept deliberately separate from the daily and
 * hourly ladders: it fires once per player ever, on their first join, and
 * has no streak, no thresholds and no GUI.
 */
public final class JoinRewardModule implements Listener {

    private final JavaPlugin plugin;

    public JoinRewardModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {}

    public void disable() {}

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPlayedBefore()) return;
        if (!this.plugin.getConfig().getBoolean("join-rewards.enabled", true)) return;
        List<String> commands = this.plugin.getConfig().getStringList("join-rewards.commands");
        for (String command : commands) {
            if (command == null || command.isBlank()) continue;
            String expanded = CommandTemplate.expand(command, player.getName())
                    .replace("%uuid%", player.getUniqueId().toString());
            try {
                org.bukkit.Bukkit.dispatchCommand(
                        org.bukkit.Bukkit.getConsoleSender(), expanded);
            } catch (Throwable ignored) {
                // A welcome gift must never break a join.
            }
        }
        String message = this.plugin.getConfig().getString("join-rewards.message", "");
        if (message != null && !message.isBlank()) {
            player.sendMessage(UiKit.colour(message));
        }
        this.plugin.getLogger().info("[JoinReward] first join grant for " + player.getName());
    }
}
