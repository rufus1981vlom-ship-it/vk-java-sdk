package com.orda.vkplugin.piaro;

import com.orda.vkplugin.PiarOrdaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.List;

public class PiaroCommand implements CommandExecutor, TabCompleter {
    private final PiarOrdaPlugin plugin;
    private final AutoPrService autoPrService;

    public PiaroCommand(PiarOrdaPlugin plugin, AutoPrService autoPrService) {
        this.plugin = plugin;
        this.autoPrService = autoPrService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("/piaro <start|stop|reload>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "start" -> {
                autoPrService.start();
                sender.sendMessage("PiarOrda AutoPR: запущен.");
            }
            case "stop" -> {
                autoPrService.stop();
                sender.sendMessage("PiarOrda AutoPR: остановлен.");
            }
            case "reload" -> {
                plugin.reloadPiaro();
                sender.sendMessage("PiarOrda AutoPR: настройки перезагружены.");
            }
            default -> sender.sendMessage("/piaro <start|stop|reload>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("start", "stop", "reload");
        }
        return List.of();
    }
}
