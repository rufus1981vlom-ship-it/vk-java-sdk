package org.bukkit.scheduler;

import org.bukkit.plugin.java.JavaPlugin;

public interface BukkitScheduler {
    BukkitTask runTaskTimerAsynchronously(JavaPlugin plugin, Runnable task, long delay, long period);
}
