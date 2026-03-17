package com.orda.vkplugin;

import com.orda.vkplugin.piaro.AutoPrService;
import com.orda.vkplugin.piaro.AutoPromoService;
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
    private AutoPromoService autoPromoService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureGroupsConfig();

        storage = new Storage(new File(getDataFolder(), "piaro.db"), getLogger());
        storage.initSchema();

        factCollector = new RuntimeFactCollector(this);
        getServer().getPluginManager().registerEvents(factCollector, this);

        autoPrService = new AutoPrService(this, storage, factCollector);
        autoPromoService = new AutoPromoService(this, storage);

        PiaroCommand command = new PiaroCommand(this, autoPrService, autoPromoService);
        PluginCommand piaro = getCommand("piaro");
        if (piaro != null) {
            piaro.setExecutor(command);
            piaro.setTabCompleter(command);
        }

        if (getConfig().getBoolean("auto-pr.auto-enable", true)) {
            autoPrService.start();
        }
        if (getConfig().getBoolean("auto-promo.enabled", true)) {
            autoPromoService.start();
        }
        getLogger().info("PROrda enabled (auto-promo + auto-smm)");
    }

    @Override
    public void onDisable() {
        if (autoPrService != null) autoPrService.stop();
        if (autoPromoService != null) autoPromoService.stop();
        if (storage != null) storage.closeSilently();
    }

    public void reloadPiaro() {
        reloadConfig();
        ensureGroupsConfig();
        if (autoPrService != null) autoPrService.reload();
        if (autoPromoService != null) autoPromoService.reload();
    }

    private void ensureGroupsConfig() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        File groups = new File(getDataFolder(), "groups.yml");
        if (!groups.exists()) {
            saveResource("groups.yml", false);
        }
    }
}
