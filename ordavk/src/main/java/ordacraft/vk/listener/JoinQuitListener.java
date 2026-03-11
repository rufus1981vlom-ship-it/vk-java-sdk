package ordacraft.vk.listener;

import ordacraft.vk.service.EventRelayService;
import ordacraft.vk.service.LocalizationService;
import ordacraft.vk.service.AuditService;
import ordacraft.vk.service.ConsoleDispatchService;
import ordacraft.vk.support.PendingActionService;
import ordacraft.vk.support.PendingReplyService;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;

public class JoinQuitListener implements Listener {
    private final Plugin plugin;
    private final EventRelayService relay;
    private final PendingReplyService pending;
    private final PendingActionService pendingActions;
    private final ConsoleDispatchService console;
    private final AuditService audit;
    private final LocalizationService i18n;
    private final int deliveryDelayTicks;

    public JoinQuitListener(Plugin plugin, EventRelayService relay, PendingReplyService pending,
                            PendingActionService pendingActions, ConsoleDispatchService console,
                            AuditService audit, LocalizationService i18n, int deliveryDelayTicks) {
        this.plugin = plugin;
        this.relay = relay;
        this.pending = pending;
        this.pendingActions = pendingActions;
        this.console = console;
        this.audit = audit;
        this.i18n = i18n;
        this.deliveryDelayTicks = deliveryDelayTicks;
    }

    @EventHandler public void join(PlayerJoinEvent e){
        relay.event("🟢 Вход: " + e.getPlayer().getName());

        pendingActions.pendingFor(e.getPlayer().getUniqueId()).forEach(action -> {
            boolean applied = false;
            try {
                applied = console.dispatchImmediate(action.command());
            } catch (Exception ignored) {
            }
            if (applied) {
                pendingActions.markApplied(action.id());
                audit.log("pending_action_applied", "id=" + action.id() + ", type=" + action.actionType() + ", nick=" + action.playerNick());
                relay.event("🧾 Отложенное действие применено: " + action.actionType() + " для " + action.playerNick());
            } else {
                pendingActions.markFailed(action.id(), "dispatch_failed");
                audit.log("pending_action_failed", "id=" + action.id() + ", type=" + action.actionType() + ", nick=" + action.playerNick());
                relay.event("⚠️ Не удалось применить отложенное действие: " + action.actionType() + " для " + action.playerNick());
            }
            pendingActions.save();
        });

        pending.get(e.getPlayer().getUniqueId()).ifPresent(pr -> {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                var player = plugin.getServer().getPlayer(e.getPlayer().getUniqueId());
                if (player == null || !player.isOnline()) {
                    return;
                }
                player.sendMessage(i18n.tr("player.support.offline_reply", Map.of("id", String.valueOf(pr.ticketId()), "text", pr.replyText())));
                pending.remove(player.getUniqueId());
                pending.save();
                relay.event("📬 Offline reply for ticket #" + pr.ticketId() + " delivered to " + player.getName());
            }, deliveryDelayTicks);
        });
    }
    @EventHandler public void quit(PlayerQuitEvent e){ relay.event("🔴 Выход: " + e.getPlayer().getName()); }
}
