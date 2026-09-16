package com.coltcore.core.modules;

import org.bukkit.plugin.java.JavaPlugin;

public final class MaintenanceModule {
    public MaintenanceModule(JavaPlugin plugin, Object ai, Object guard) {}
    public MaintenanceModule(JavaPlugin plugin, Object guard) {}
    public void enable() {}
    public void disable() {}
    public void reload() {}
    public boolean isEnabled() { return false; }
    public java.util.Map<String, Integer> counts() { return new java.util.HashMap<>(); }
    public int intervalHours() { return 24; }
    public String lastResult() { return "none"; }
    public String runFor(org.bukkit.command.CommandSender sender) { return "disabled"; }
    public String runFor(org.bukkit.entity.Player player) { return "disabled"; }
}
