package com.orda.vkplugin;

import com.orda.vkplugin.piaro.CampaignScheduler;
import com.orda.vkplugin.piaro.PiaroCommand;
import com.orda.vkplugin.piaro.PromoComposer;
import com.orda.vkplugin.piaro.SenderService;
import com.orda.vkplugin.piaro.Storage;
import com.orda.vkplugin.piaro.TargetQueueService;
import com.orda.vkplugin.piaro.VkClient;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public class PiarOrdaPlugin extends JavaPlugin {
    private Storage storage;
    private TargetQueueService queueService;
    private SenderService senderService;
    private CampaignScheduler scheduler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureGroupConfig();

        File dbFile = new File(getDataFolder(), "piaro.db");
        storage = new Storage(dbFile, getLogger());
        storage.initSchema();

        PromoComposer composer = new PromoComposer(this);
        VkClient vkClient = new VkClient(this, getLogger());
        queueService = new TargetQueueService(this, storage);
        senderService = new SenderService(this, storage, queueService, vkClient);
        scheduler = new CampaignScheduler(this, storage, queueService, senderService, composer);

        PiaroCommand command = new PiaroCommand(this, scheduler);
        PluginCommand piaro = getCommand("piaro");
        if (piaro != null) {
            piaro.setExecutor(command);
            piaro.setTabCompleter(command);
        }

        if (getConfig().getBoolean("piaro.auto-start", true)) {
            scheduler.start();
        }
        getLogger().info("PiarOrda enabled");
    }

    @Override
    public void onDisable() {
        if (scheduler != null) {
            scheduler.stop();
        }
        if (storage != null) {
            try {
                storage.close();
            } catch (IOException ignored) {
            }
        }
    }

    public void reloadPiaro() {
        reloadConfig();
        ensureGroupConfig();
        queueService.reloadTargets();
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
