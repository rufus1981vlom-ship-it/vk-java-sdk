package org.bukkit.plugin;

import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public interface PluginManager {
    void registerEvents(Listener listener, JavaPlugin plugin);
}
