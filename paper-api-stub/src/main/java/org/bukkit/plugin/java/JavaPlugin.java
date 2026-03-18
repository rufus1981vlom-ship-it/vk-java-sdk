package org.bukkit.plugin.java;

import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.logging.Logger;

public class JavaPlugin {
    public void onEnable() {
    }

    public void onDisable() {
    }

    public void saveDefaultConfig() {
    }

    public void saveConfig() {
    }

    public void saveResource(String resourcePath, boolean replace) {
    }

    public void reloadConfig() {
    }

    public FileConfiguration getConfig() {
        return new FileConfiguration();
    }

    public PluginCommand getCommand(String name) {
        return null;
    }

    public File getDataFolder() {
        return new File(".");
    }

    public Server getServer() {
        return null;
    }

    public Logger getLogger() {
        return Logger.getLogger("JavaPlugin");
    }
}
