package com.orda.vkplugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.orda.vkplugin.listener.GameEventListener;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class OrdaVkPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private VkBridgeService vkBridgeService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Gson gson = new GsonBuilder().create();

        vkBridgeService = new VkBridgeService(this, gson);
        getServer().getPluginManager().registerEvents(new GameEventListener(vkBridgeService), this);
        registerCommands();
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

    private void registerCommands() {
        register("ordavk");
        register("ovk");
        register("helpop");
        register("ac");
        register("report");
        register("rep");
    }

    private void register(String name) {
        if (getCommand(name) != null) {
            getCommand(name).setExecutor(this);
            getCommand(name).setTabCompleter(this);
        } else {
            getLogger().warning("Команда не найдена в plugin.yml: /" + name);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);

        if (cmd.equals("ordavk") || cmd.equals("ovk")) {
            if (args.length == 0) {
                sender.sendMessage("§aOrdaVK 2.0Pro §7— команды: /" + label + " reload");
                return true;
            }
            if (args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("ordavk.reload")) {
                    sender.sendMessage("§cНедостаточно прав: ordavk.reload");
                    return true;
                }
                reloadConfig();
                if (vkBridgeService != null) vkBridgeService.reloadState();
                sender.sendMessage("§aOrdaVK конфиг и состояние перезагружены.");
                return true;
            }
            sender.sendMessage("§cНеизвестная подкоманда. Используй: /" + label + " reload");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cЭта команда доступна только игроку.");
            return true;
        }

        if (vkBridgeService != null && vkBridgeService.isManagedTicketCommand(cmd)) {
            return vkBridgeService.handleManagedTicketCommand(player, cmd, args);
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if ((cmd.equals("ordavk") || cmd.equals("ovk")) && args.length == 1) {
            List<String> options = Collections.singletonList("reload");
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String opt : options) {
                if (opt.startsWith(prefix)) out.add(opt);
            }
            return out;
        }
        return Collections.emptyList();
    }
}
