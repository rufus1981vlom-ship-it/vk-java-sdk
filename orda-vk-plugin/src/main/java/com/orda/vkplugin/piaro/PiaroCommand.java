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
            sender.sendMessage("/piaro <start|stop|reload|status|debug [on|off]>");
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
                sender.sendMessage("- debug.enabled=" + plugin.getConfig().getBoolean("debug.enabled", false));
                sender.sendMessage("- auto-pr.auto-enable=" + plugin.getConfig().getBoolean("auto-pr.auto-enable", true));
                sender.sendMessage("- auto-promo.enabled=" + plugin.getConfig().getBoolean("auto-promo.enabled", true));
                sender.sendMessage("- auto-promo.interval-minutes=" + plugin.getConfig().getLong("auto-promo.interval-minutes", 20));
                sender.sendMessage("- auto-pr.smm-group.period-hours=" + plugin.getConfig().getLong("auto-pr.smm-group.period-hours", 8));
                if (plugin.getStorage() != null) {
                    sender.sendMessage("- last-stage=" + plugin.getStorage().getStateString("debug.last_stage", "unknown"));
                }
            }
            case "debug" -> {
                boolean current = plugin.getConfig().getBoolean("debug.enabled", false);
                boolean target = args.length >= 2 ? parseBoolean(args[1], current) : !current;
                plugin.setDebugEnabled(target);
                sender.sendMessage("PROrda: debug mode " + (target ? "ON" : "OFF") + ".");
            }
            default -> sender.sendMessage("/piaro <start|stop|reload|status|debug [on|off]>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("start", "stop", "reload", "status", "debug");
        }
        if (args.length == 2 && "debug".equalsIgnoreCase(args[0])) {
            return Arrays.asList("on", "off");
        }
        return List.of();
    }

    private boolean parseBoolean(String value, boolean fallback) {
        String v = value.toLowerCase();
        if (v.equals("on") || v.equals("true") || v.equals("1")) return true;
        if (v.equals("off") || v.equals("false") || v.equals("0")) return false;
        return fallback;
    }
}
