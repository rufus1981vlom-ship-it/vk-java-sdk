package com.orda.vkplugin.listener;

import com.orda.vkplugin.VkBridgeService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GameEventListener implements Listener {
    private static final Pattern LP_ADD_GROUP_PATTERN = Pattern.compile("^/?(?:luckperms|lp)\\s+user\\s+(\\S+)\\s+parent\\s+add\\s+(\\S+).*$", Pattern.CASE_INSENSITIVE);

    private final VkBridgeService vkBridgeService;

    public GameEventListener(VkBridgeService vkBridgeService) {
        this.vkBridgeService = vkBridgeService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        vkBridgeService.onPlayerJoin(event.getPlayer());
        vkBridgeService.notifyEvent("Игрок зашёл: " + event.getPlayer().getName());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        vkBridgeService.onPlayerQuit(event.getPlayer());
        vkBridgeService.notifyEvent("Игрок вышел: " + event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        vkBridgeService.onPlayerCommand(event.getPlayer().getName(), event.getMessage());
        notifyCommand(event.getPlayer().getName(), event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerCommand(ServerCommandEvent event) {
        notifyCommand("CONSOLE", event.getCommand());
    }

    private void notifyCommand(String actor, String commandRaw) {
        String command = commandRaw.startsWith("/") ? commandRaw.substring(1) : commandRaw;
        String lower = command.toLowerCase(Locale.ROOT);

        if (startsWithAny(lower, "ban ", "ban-ip ", "banip ", "mute ", "tempmute ", "kick ", "pardon ", "unban ")) {
            vkBridgeService.notifyEvent("Команда: " + actor + " -> /" + command);
            return;
        }

        Matcher matcher = LP_ADD_GROUP_PATTERN.matcher(command);
        if (matcher.matches()) {
            String target = matcher.group(1);
            String group = matcher.group(2);
            vkBridgeService.notifyEvent(actor + " выдал " + target + " группу " + group);
        }
    }

    private boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
