package com.gildedmc.core.modules;

import org.bukkit.plugin.java.JavaPlugin;
import java.util.UUID;

public final class GrimBridge {
    public GrimBridge(JavaPlugin plugin) {}
    public void enable() {}
    public void disable() {}
    public boolean available() { return false; }
    public Verdict verdict(UUID id) { return null; }
    public void enable(int i) {}
    public void forget(UUID id) {}
    public int sampleCount(UUID id) { return 0; }
    public String grimVersion() { return "unknown"; }
    public static class Verdict {
        public boolean machineLike(double a, double b) { return false; }
    }
}
