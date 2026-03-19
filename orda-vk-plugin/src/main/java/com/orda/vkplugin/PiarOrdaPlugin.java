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
        debug("onEnable:start");
        saveDefaultConfig();
        ensureGroupsConfig();

        storage = new Storage(new File(getDataFolder(), "piaro.db"), getLogger());
        storage.initSchema();
        storage.setStateString("debug.last_stage", "onEnable:db_ready");

        factCollector = new RuntimeFactCollector(this);
        getServer().getPluginManager().registerEvents(factCollector, this);
        storage.setStateString("debug.last_stage", "onEnable:listeners_ready");

        autoPrService = new AutoPrService(this, storage, factCollector);
        autoPromoService = new AutoPromoService(this, storage);
        storage.setStateString("debug.last_stage", "onEnable:services_ready");

        PiaroCommand command = new PiaroCommand(this, autoPrService, autoPromoService);
        PluginCommand piaro = getCommand("piaro");
        if (piaro != null) {
            piaro.setExecutor(command);
            piaro.setTabCompleter(command);
        }

        if (getConfig().getBoolean("auto-pr.auto-enable", true)) {
            autoPrService.start();
            debug("auto-pr started");
        }
        if (getConfig().getBoolean("auto-promo.enabled", true)) {
            autoPromoService.start();
            debug("auto-promo started");
        }
        storage.setStateString("debug.last_stage", "onEnable:ready");
        getLogger().info("PROrda enabled (auto-promo + auto-smm)");
    }

    @Override
    public void onDisable() {
        debug("onDisable:start");
        if (autoPrService != null) autoPrService.stop();
        if (autoPromoService != null) autoPromoService.stop();
        if (storage != null) {
            storage.setStateString("debug.last_stage", "onDisable:stopped");
            storage.closeSilently();
        }
    }

    public Storage getStorage() {
        return storage;
    }

    public void reloadPiaro() {
        debug("reload:start");
        reloadConfig();
        ensureGroupsConfig();
        if (autoPrService != null) autoPrService.reload();
        if (autoPromoService != null) autoPromoService.reload();
        if (storage != null) {
            storage.setStateString("debug.last_stage", "reload:done");
        }
    }

    public void debug(String message) {
        if (getConfig().getBoolean("debug.enabled", false)) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    public void setDebugEnabled(boolean enabled) {
        getConfig().set("debug.enabled", enabled);
        saveConfig();
        getLogger().info("PROrda debug mode: " + (enabled ? "ON" : "OFF"));
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
