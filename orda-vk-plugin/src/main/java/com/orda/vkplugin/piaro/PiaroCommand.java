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
    private final AutoPromoService autoPromoService;

    public PiaroCommand(PiarOrdaPlugin plugin, AutoPrService autoPrService, AutoPromoService autoPromoService) {
        this.plugin = plugin;
        this.autoPrService = autoPrService;
        this.autoPromoService = autoPromoService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("/piaro <start|stop|reload|status>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "start" -> {
                autoPrService.start();
                autoPromoService.start();
                sender.sendMessage("PROrda: авто-пиар и авто-SMM запущены.");
            }
            case "stop" -> {
                autoPrService.stop();
                autoPromoService.stop();
                sender.sendMessage("PROrda: авто-пиар и авто-SMM остановлены.");
            }
            case "reload" -> {
                plugin.reloadPiaro();
                sender.sendMessage("PROrda: настройки перезагружены.");
            }
            case "status" -> {
                sender.sendMessage("PROrda status:");
                sender.sendMessage("- auto-pr.auto-enable=" + plugin.getConfig().getBoolean("auto-pr.auto-enable", true));
                sender.sendMessage("- auto-promo.enabled=" + plugin.getConfig().getBoolean("auto-promo.enabled", true));
                sender.sendMessage("- auto-promo.interval-minutes=" + plugin.getConfig().getLong("auto-promo.interval-minutes", 20));
                sender.sendMessage("- auto-pr.smm-group.period-hours=" + plugin.getConfig().getLong("auto-pr.smm-group.period-hours", 8));
            }
            default -> sender.sendMessage("/piaro <start|stop|reload|status>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("start", "stop", "reload", "status");
        }
        return List.of();
    }
}
