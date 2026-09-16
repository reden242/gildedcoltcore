package com.gildedmc.core;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.atomic.AtomicReference;

/** Scheduler calls that use the Consumer overload present on modern Leaf/Paper. */
public final class SchedulerCompat {

    public static final class ManagedTask {
        private final AtomicReference<BukkitTask> task = new AtomicReference<>();
        private volatile boolean cancelled;

        private void accept(BukkitTask bukkitTask) {
            this.task.set(bukkitTask);
            if (this.cancelled) bukkitTask.cancel();
        }

        public void cancel() {
            this.cancelled = true;
            BukkitTask current = this.task.get();
            if (current != null) current.cancel();
        }
    }

    private SchedulerCompat() { }

    public static void run(Plugin plugin, Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, task -> runnable.run());
    }

    public static void runAsync(Plugin plugin, Runnable runnable) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task -> runnable.run());
    }

    public static void runLater(Plugin plugin, Runnable runnable, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, task -> runnable.run(), delayTicks);
    }

    public static void runLaterAsync(Plugin plugin, Runnable runnable, long delayTicks) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task -> runnable.run(), delayTicks);
    }

    public static ManagedTask timer(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        ManagedTask managed = new ManagedTask();
        Bukkit.getScheduler().runTaskTimer(plugin, managed::accept, delayTicks, periodTicks);
        return managed;
    }

    public static ManagedTask timerAsync(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        ManagedTask managed = new ManagedTask();
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, managed::accept, delayTicks, periodTicks);
        return managed;
    }
}
