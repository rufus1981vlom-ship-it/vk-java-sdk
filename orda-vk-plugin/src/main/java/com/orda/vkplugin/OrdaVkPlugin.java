package com.orda.vkplugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.orda.vkplugin.listener.GameEventListener;
import org.bukkit.plugin.java.JavaPlugin;

public class OrdaVkPlugin extends JavaPlugin {
    private VkBridgeService vkBridgeService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Gson gson = new GsonBuilder().create();

        vkBridgeService = new VkBridgeService(this, gson);
        getServer().getPluginManager().registerEvents(new GameEventListener(vkBridgeService), this);
        vkBridgeService.start();
        getLogger().info("Orda VK запущен.");
    }

    @Override
    public void onDisable() {
        if (vkBridgeService != null) {
            vkBridgeService.stop();
        }
        getLogger().info("Orda VK остановлен.");
    }
}
