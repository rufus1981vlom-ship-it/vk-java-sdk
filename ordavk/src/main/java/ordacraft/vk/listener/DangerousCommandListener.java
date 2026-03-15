package ordacraft.vk.listener;

import ordacraft.vk.command.DangerousCommandInspector;
import ordacraft.vk.service.EventRelayService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

public class DangerousCommandListener implements Listener {
    private final EventRelayService relay;
    private final DangerousCommandInspector inspector = new DangerousCommandInspector();

    public DangerousCommandListener(EventRelayService relay) {
        this.relay = relay;
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        logCommand(event.getPlayer(), event.getMessage());
    }

    @EventHandler
    public void onServerCommand(ServerCommandEvent event) {
        logCommand(event.getSender(), event.getCommand());
    }

    private void logCommand(CommandSender sender, String raw) {
        String normalized = inspector.normalize(raw);
        if (normalized.isBlank()) {
            return;
        }

        DangerousCommandInspector.LpGroupChange lp = inspector.parseLpGroupChange(normalized);
        if (lp != null) {
            switch (lp.action()) {
                case SET -> relay.event("👑 Группа: " + lp.user() + " -> " + lp.group() + " | Инициатор: " + actor(sender));
                case ADD -> relay.event("👑 Группа добавлена: " + lp.user() + " + " + lp.group() + " | Инициатор: " + actor(sender));
                case REMOVE -> relay.event("👑 Группа снята: " + lp.user() + " - " + lp.group() + " | Инициатор: " + actor(sender));
            }
            return;
        }

        if (!inspector.isDangerous(normalized)) {
            return;
        }

        relay.event("⚠️ Raw command: " + normalized + " | Инициатор: " + actor(sender));
    }

    private String actor(CommandSender sender) {
        if (sender == null) {
            return "Console";
        }
        if (sender instanceof Player p) {
            return p.getName();
        }
        return sender.getName();
    }
}
