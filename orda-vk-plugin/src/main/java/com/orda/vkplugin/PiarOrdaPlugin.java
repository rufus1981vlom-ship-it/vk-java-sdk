package com.orda.vkplugin;

import com.orda.vkplugin.piaro.AutoPrService;
import com.orda.vkplugin.piaro.PiaroCommand;
import com.orda.vkplugin.piaro.RuntimeFactCollector;
import com.orda.vkplugin.piaro.Storage;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class PiarOrdaPlugin extends JavaPlugin {
    private Storage storage;
    private RuntimeFactCollector factCollector;
    private AutoPrService autoPrService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureGroupConfig();

        storage = new Storage(new File(getDataFolder(), "piaro.db"), getLogger());
        storage.initSchema();

        factCollector = new RuntimeFactCollector(this);
        getServer().getPluginManager().registerEvents(factCollector, this);

        autoPrService = new AutoPrService(this, storage, factCollector);

        PiaroCommand command = new PiaroCommand(this, autoPrService);
        PluginCommand piaro = getCommand("piaro");
        if (piaro != null) {
            piaro.setExecutor(command);
            piaro.setTabCompleter(command);
        }

        if (getConfig().getBoolean("auto-pr.auto-enable", true)) {
            autoPrService.start();
        }
        getLogger().info("PiarOrda AutoPR enabled");
    }

    @Override
    public void onDisable() {
        if (autoPrService != null) {
            autoPrService.stop();
        }
        if (storage != null) {
            storage.closeSilently();
        }
    }

    public void reloadPiaro() {
        reloadConfig();
        ensureGroupConfig();
        if (autoPrService != null) {
            autoPrService.reload();
        }
    }

    private void ensureGroupConfig() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        File groups = new File(getDataFolder(), "group.yml");
        if (!groups.exists()) {
            saveResource("group.yml", false);
        }
    }
}
