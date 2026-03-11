package ordacraft.vk.listener;

import ordacraft.vk.service.EventRelayService;
import ordacraft.vk.service.LocalizationService;
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
    private final LocalizationService i18n;
    private final int deliveryDelayTicks;

    public JoinQuitListener(Plugin plugin, EventRelayService relay, PendingReplyService pending, LocalizationService i18n, int deliveryDelayTicks) {
        this.plugin = plugin;
        this.relay = relay;
        this.pending = pending;
        this.i18n = i18n;
        this.deliveryDelayTicks = deliveryDelayTicks;
    }

    @EventHandler public void join(PlayerJoinEvent e){
        relay.event("🟢 Join: " + e.getPlayer().getName());
        pending.get(e.getPlayer().getUniqueId()).ifPresent(pr -> {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                var player = plugin.getServer().getPlayer(e.getPlayer().getUniqueId());
                if (player == null || !player.isOnline()) {
                    return;
                }
                player.sendMessage(i18n.tr("player.support.offline_reply", Map.of("id", String.valueOf(pr.ticketId()), "text", pr.replyText())));
                pending.remove(player.getUniqueId());
                relay.event("📬 Offline reply for ticket #" + pr.ticketId() + " delivered to " + player.getName());
            }, deliveryDelayTicks);
        });
    }
    @EventHandler public void quit(PlayerQuitEvent e){ relay.event("🔴 Quit: " + e.getPlayer().getName()); }
}
