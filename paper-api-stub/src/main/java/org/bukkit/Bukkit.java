package org.bukkit;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;

import java.util.Collection;
import java.util.Collections;

public final class Bukkit {
    private static BukkitScheduler scheduler;

    private Bukkit() {
    }

    public static BukkitScheduler getScheduler() {
        return scheduler;
    }

    public static void setScheduler(BukkitScheduler scheduler) {
        Bukkit.scheduler = scheduler;
    }

    public static Collection<? extends Player> getOnlinePlayers() {
        return Collections.emptyList();
    }
}
