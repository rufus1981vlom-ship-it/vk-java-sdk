package ordacraft.vk.listener;

import ordacraft.vk.service.EventRelayService;
import ordacraft.vk.support.PendingReplyService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class JoinQuitListener implements Listener {
    private final EventRelayService relay;
    private final PendingReplyService pending;

    public JoinQuitListener(EventRelayService relay, PendingReplyService pending) { this.relay = relay; this.pending = pending; }

    @EventHandler public void join(PlayerJoinEvent e){
        relay.event("🟢 Join: " + e.getPlayer().getName());
        pending.take(e.getPlayer().getUniqueId()).ifPresent(pr -> {
            e.getPlayer().sendMessage("[Support] You have a stored reply for ticket #" + pr.ticketId() + ": " + pr.replyText());
            relay.event("📬 Offline reply for ticket #" + pr.ticketId() + " delivered to " + e.getPlayer().getName());
        });
    }
    @EventHandler public void quit(PlayerQuitEvent e){ relay.event("🔴 Quit: " + e.getPlayer().getName()); }
}
