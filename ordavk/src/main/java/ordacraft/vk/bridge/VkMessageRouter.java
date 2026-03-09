package ordacraft.vk.bridge;

import ordacraft.vk.admin.AdminRegistry;
import ordacraft.vk.admin.Role;
import ordacraft.vk.command.CommandPolicyService;
import ordacraft.vk.config.ChatMode;
import ordacraft.vk.config.PluginSettings;
import ordacraft.vk.governance.GovernanceService;
import ordacraft.vk.service.ConsoleDispatchService;
import ordacraft.vk.service.EventRelayService;
import ordacraft.vk.support.PendingReply;
import ordacraft.vk.support.PendingReplyService;
import ordacraft.vk.support.SupportTicketService;
import ordacraft.vk.vk.api.VkApiClient;
import ordacraft.vk.vk.model.VkIncomingMessage;
import ordacraft.vk.vk.parser.BanCommandParser;
import ordacraft.vk.vk.parser.SupportCommandParser;
import org.bukkit.Bukkit;

import java.time.Instant;
import java.util.Locale;

public class VkMessageRouter {
    private final PluginSettings settings;
    private final VkApiClient api;
    private final AdminRegistry admins;
    private final SupportTicketService tickets;
    private final PendingReplyService pending;
    private final CommandPolicyService cmdPolicy;
    private final ConsoleDispatchService console;
    private final GovernanceService governance;
    private final EventRelayService relay;

    public VkMessageRouter(PluginSettings settings, VkApiClient api, AdminRegistry admins, SupportTicketService tickets,
                           PendingReplyService pending, CommandPolicyService cmdPolicy, ConsoleDispatchService console,
                           GovernanceService governance, EventRelayService relay) {
        this.settings = settings; this.api = api; this.admins = admins; this.tickets = tickets; this.pending = pending;
        this.cmdPolicy = cmdPolicy; this.console = console; this.governance = governance; this.relay = relay;
    }

    public void onMessage(VkIncomingMessage msg) {
        ChatMode mode = settings.chats().stream().filter(c -> c.id() == msg.peerId()).map(c -> c.mode()).findFirst().orElse(ChatMode.IGNORE);
        if (mode == ChatMode.IGNORE || mode == ChatMode.EVENTS) return;

        var actor = admins.find(msg.fromId());
        if (mode == ChatMode.MANAGE) handleManage(msg, actor.map(a -> a.role()).orElse(null));
        if (mode == ChatMode.SUPPORT) handleSupport(msg, actor.map(a -> a.role()).orElse(null));
    }

    private void handleManage(VkIncomingMessage msg, Role role) {
        String t = msg.text().trim();
        if ("!help".equalsIgnoreCase(t)) { reply(msg.peerId(), "✅ Done"); return; }
        if (role == null) { reply(msg.peerId(), "❌ Not enough permission"); return; }

        if (t.equalsIgnoreCase("!admins")) {
            StringBuilder sb = new StringBuilder("Admins:\n");
            admins.all().forEach(a -> sb.append(a.vkId()).append(" -> ").append(a.mcNick()).append(" (" + a.role().name().toLowerCase() + ")\n"));
            reply(msg.peerId(), sb.toString()); return;
        }

        if (t.startsWith("!kick ")) {
            String[] p = t.split("\\s+", 3); if (p.length < 3) { reply(msg.peerId(), "❌ Usage: !kick <player> <reason>"); return; }
            console.dispatch("kick " + p[1] + " " + p[2]); relay.event("⛔ Kick: " + p[1] + " | Reason: " + p[2]); reply(msg.peerId(), "✅ Done"); return;
        }
        if (t.startsWith("!mute ")) {
            String[] p = t.split("\\s+", 4); if (p.length < 4) { reply(msg.peerId(), "❌ Usage: !mute <player> <time> <reason>"); return; }
            console.dispatch("tempmute " + p[1] + " " + p[2] + " " + p[3]); reply(msg.peerId(), "✅ Done"); return;
        }
        if (t.startsWith("!ban ")) {
            String[] p = t.split("\\s+", 4); if (p.length < 3) { reply(msg.peerId(), "❌ Usage: !ban <player> [time] <reason>"); return; }
            String cmd = p.length == 3 ? BanCommandParser.toConsole(p[1], null, p[2]) : BanCommandParser.toConsole(p[1], p[2], p[3]);
            console.dispatch(cmd); reply(msg.peerId(), "✅ Command executed: " + cmd); relay.event("⛔ Ban: " + p[1]); return;
        }
        if (t.startsWith("!admin remove ")) {
            String[] p = t.split("\\s+");
            if (p.length != 3) { reply(msg.peerId(), "❌ Usage: !admin remove <vk_id>"); return; }
            long targetVkId;
            try { targetVkId = Long.parseLong(p[2]); } catch (NumberFormatException e) { reply(msg.peerId(), "❌ vk_id must be numeric"); return; }
            var target = admins.find(targetVkId);
            if (target.isEmpty()) { reply(msg.peerId(), "❌ Admin not found"); return; }
            if (target.get().mcNick() == null || target.get().mcNick().isBlank()) { reply(msg.peerId(), "❌ Target admin has no linked Minecraft nickname"); return; }
            console.dispatch("lp user " + target.get().mcNick() + " parent set default");
            admins.remove(targetVkId);
            admins.save();
            relay.event("🛡 Admin removed: vk=" + targetVkId + " nick=" + target.get().mcNick());
            reply(msg.peerId(), "✅ Admin removed: " + targetVkId + ". Executed: lp user " + target.get().mcNick() + " parent set default");
            return;
        }
        if (t.startsWith("!cmd ")) {
            String raw = t.substring(5);
            if (!cmdPolicy.canUseRaw(role)) { reply(msg.peerId(), "❌ Not enough permission"); return; }
            if (!cmdPolicy.allows(raw)) { reply(msg.peerId(), "❌ This command is not allowed by policy"); return; }
            console.dispatch(raw); reply(msg.peerId(), "✅ Command executed: " + raw); relay.event("⚠ Dangerous cmd by VK: " + raw); return;
        }
        if (t.startsWith("!vk kick ")) {
            String[] p=t.split("\\s+",4); if(p.length<4){ reply(msg.peerId(),"❌ Usage: !vk kick <vk_id> <reason>"); return; }
            reply(msg.peerId(), governance.kickEverywhere(role, Long.parseLong(p[2]), p[3])); return;
        }
    }

    private void handleSupport(VkIncomingMessage msg, Role role) {
        if (role == null) { reply(msg.peerId(), "❌ Not enough permission"); return; }
        try {
            var p = SupportCommandParser.parse(msg.text());
            switch (p.cmd()) {
                case "!list" -> reply(msg.peerId(), tickets.openTickets().stream().map(t -> "#" + t.id() + " " + t.playerName() + " " + t.type()).reduce((a,b)->a+"\n"+b).orElse("No tickets"));
                case "!info" -> reply(msg.peerId(), tickets.find(p.id()).map(t -> "#"+t.id()+" "+t.text()).orElse("Not found"));
                case "!close" -> { tickets.close(p.id()); reply(msg.peerId(), "✅ Done"); }
                case "!r" -> tickets.find(p.id()).ifPresentOrElse(t -> {
                    var pl = Bukkit.getPlayer(t.playerUuid());
                    if (pl != null) {
                        pl.sendMessage("[Support] Reply: " + p.tail());
                        tickets.markAnswered(t.id(), msg.fromId(), settings.autoCloseOnReply());
                        relay.event("💬 Reply sent to " + t.playerName() + " for ticket #" + t.id());
                        reply(msg.peerId(), "✅ Reply sent to " + t.playerName() + " for ticket #" + t.id());
                    } else {
                        pending.put(new PendingReply(t.playerUuid(), t.playerName(), t.id(), p.tail(), msg.fromId(), String.valueOf(msg.fromId()), Instant.now().toEpochMilli()));
                        tickets.markAnswered(t.id(), msg.fromId(), false);
                        relay.event("💾 " + t.playerName() + " is offline. Reply for ticket #" + t.id() + " has been saved.");
                        reply(msg.peerId(), "💾 Reply saved.");
                    }
                }, () -> reply(msg.peerId(), "Not found"));
            }
        } catch (Exception e) {
            reply(msg.peerId(), "❌ Malformed support command");
        }
    }

    private void reply(long peerId, String text) { try { api.send(peerId, text); } catch (Exception ignored) {} }
}
