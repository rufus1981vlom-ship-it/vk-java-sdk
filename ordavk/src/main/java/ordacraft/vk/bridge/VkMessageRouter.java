package ordacraft.vk.bridge;

import ordacraft.vk.admin.AdminRegistry;
import ordacraft.vk.admin.Role;
import ordacraft.vk.command.CommandPolicyService;
import ordacraft.vk.config.ChatMode;
import ordacraft.vk.config.PluginSettings;
import ordacraft.vk.governance.GovernanceService;
import ordacraft.vk.service.ConsoleDispatchService;
import ordacraft.vk.service.EventRelayService;
import ordacraft.vk.service.LocalizationService;
import ordacraft.vk.service.PermissionMatrixService;
import ordacraft.vk.support.PendingReply;
import ordacraft.vk.support.PendingReplyService;
import ordacraft.vk.support.SupportTicketService;
import ordacraft.vk.vk.api.VkApiClient;
import ordacraft.vk.vk.model.VkIncomingMessage;
import ordacraft.vk.vk.parser.BanCommandParser;
import ordacraft.vk.vk.parser.SupportCommandParser;
import org.bukkit.Bukkit;

import java.time.Instant;
import java.util.Map;

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
    private final LocalizationService i18n;
    private final PermissionMatrixService matrix;

    public VkMessageRouter(PluginSettings settings, VkApiClient api, AdminRegistry admins, SupportTicketService tickets,
                           PendingReplyService pending, CommandPolicyService cmdPolicy, ConsoleDispatchService console,
                           GovernanceService governance, EventRelayService relay,
                           LocalizationService i18n, PermissionMatrixService matrix) {
        this.settings = settings; this.api = api; this.admins = admins; this.tickets = tickets; this.pending = pending;
        this.cmdPolicy = cmdPolicy; this.console = console; this.governance = governance; this.relay = relay;
        this.i18n = i18n; this.matrix = matrix;
    }

    public void onMessage(VkIncomingMessage msg) {
        ChatMode mode = settings.chats().stream().filter(c -> c.id() == msg.peerId()).map(c -> c.mode()).findFirst().orElse(ChatMode.IGNORE);
        if (mode == ChatMode.IGNORE || mode == ChatMode.EVENTS) return;

        var actor = admins.find(msg.fromId());
        if (mode == ChatMode.MANAGE) handleManage(msg, actor.map(a -> a.role()).orElse(null));
        if (mode == ChatMode.SUPPORT) handleSupport(msg, actor.map(a -> a.role()).orElse(null));
    }

    private boolean require(Role role, String key, long peerId) {
        if (role == null || !matrix.allowed(role, key)) {
            reply(peerId, i18n.tr("common.not_enough_permission"));
            return false;
        }
        return true;
    }

    private void handleManage(VkIncomingMessage msg, Role role) {
        String t = msg.text() == null ? "" : msg.text().trim();
        if ("!help".equalsIgnoreCase(t)) {
            if (!require(role, "manage.help", msg.peerId())) return;
            reply(msg.peerId(), i18n.tr("manage.help"));
            return;
        }

        if (t.equalsIgnoreCase("!admins")) {
            if (!require(role, "manage.admins", msg.peerId())) return;
            StringBuilder sb = new StringBuilder("Admins:\n");
            admins.all().forEach(a -> sb.append(a.vkId()).append(" -> ").append(a.mcNick()).append(" (" + a.role().name().toLowerCase() + ")\n"));
            reply(msg.peerId(), sb.toString());
            return;
        }

        if (t.startsWith("!kick ")) {
            if (!require(role, "manage.kick", msg.peerId())) return;
            String[] p = t.split("\\s+", 3); if (p.length < 3) { reply(msg.peerId(), i18n.tr("manage.usage.kick")); return; }
            console.dispatch("kick " + p[1] + " " + p[2]); relay.event("⛔ Kick: " + p[1] + " | Reason: " + p[2]); reply(msg.peerId(), i18n.tr("common.done")); return;
        }

        if (t.startsWith("!mute ")) {
            if (!require(role, "manage.mute", msg.peerId())) return;
            String[] p = t.split("\\s+", 4); if (p.length < 4) { reply(msg.peerId(), i18n.tr("manage.usage.mute")); return; }
            console.dispatch("tempmute " + p[1] + " " + p[2] + " " + p[3]); reply(msg.peerId(), i18n.tr("common.done")); return;
        }

        if (t.startsWith("!ban ")) {
            if (!require(role, "manage.ban", msg.peerId())) return;
            String[] p = t.split("\\s+", 4); if (p.length < 3) { reply(msg.peerId(), i18n.tr("manage.usage.ban")); return; }
            String cmd = p.length == 3 ? BanCommandParser.toConsole(p[1], null, p[2]) : BanCommandParser.toConsole(p[1], p[2], p[3]);
            console.dispatch(cmd); reply(msg.peerId(), i18n.tr("manage.executed", Map.of("cmd", cmd))); relay.event("⛔ Ban: " + p[1]); return;
        }

        if (t.startsWith("!admin remove ")) {
            if (!require(role, "manage.admin.remove", msg.peerId())) return;
            String[] p = t.split("\\s+");
            if (p.length != 3) { reply(msg.peerId(), i18n.tr("manage.usage.admin_remove")); return; }
            long targetVkId;
            try { targetVkId = Long.parseLong(p[2]); } catch (NumberFormatException e) { reply(msg.peerId(), i18n.tr("manage.vk_id_numeric")); return; }
            var target = admins.find(targetVkId);
            if (target.isEmpty()) { reply(msg.peerId(), i18n.tr("manage.admin_not_found")); return; }
            if (!role.higherThan(target.get().role())) { reply(msg.peerId(), i18n.tr("manage.target_higher_or_equal")); return; }
            if (target.get().mcNick() == null || target.get().mcNick().isBlank()) { reply(msg.peerId(), i18n.tr("manage.target_no_nick")); return; }
            console.dispatch("lp user " + target.get().mcNick() + " parent set default");
            admins.remove(targetVkId);
            admins.save();
            relay.event("🛡 Admin removed: vk=" + targetVkId + " nick=" + target.get().mcNick());
            reply(msg.peerId(), i18n.tr("manage.admin_removed", Map.of("vk", String.valueOf(targetVkId), "nick", target.get().mcNick())));
            return;
        }

        if (t.startsWith("!cmd ")) {
            if (!require(role, "manage.cmd", msg.peerId())) return;
            String raw = t.substring(5).trim();
            if (raw.isEmpty()) { reply(msg.peerId(), i18n.tr("manage.empty_command")); return; }
            if (!cmdPolicy.canUseRaw(role)) { reply(msg.peerId(), i18n.tr("common.not_enough_permission")); return; }
            if (!cmdPolicy.allows(raw)) { reply(msg.peerId(), i18n.tr("manage.cmd_blocked_by_policy")); return; }
            console.dispatch(raw); reply(msg.peerId(), i18n.tr("manage.executed", Map.of("cmd", raw))); relay.event("⚠ Dangerous cmd by VK: " + raw); return;
        }

        if (t.startsWith("!vk kick ")) {
            if (!require(role, "manage.vk.kick", msg.peerId())) return;
            String[] p=t.split("\\s+",4); if(p.length<4){ reply(msg.peerId(), i18n.tr("manage.usage.vk_kick")); return; }
            try {
                reply(msg.peerId(), governance.kickEverywhere(role, Long.parseLong(p[2]), p[3]));
            } catch (NumberFormatException e) {
                reply(msg.peerId(), i18n.tr("manage.vk_id_numeric"));
            }
            return;
        }

        reply(msg.peerId(), i18n.tr("manage.unknown"));
    }

    private void handleSupport(VkIncomingMessage msg, Role role) {
        if (role == null) { reply(msg.peerId(), i18n.tr("common.not_enough_permission")); return; }
        try {
            var p = SupportCommandParser.parse(msg.text());
            switch (p.cmd()) {
                case "!list" -> {
                    if (!require(role, "support.list", msg.peerId())) return;
                    reply(msg.peerId(), tickets.openTickets().stream().map(t -> "#" + t.id() + " " + t.playerName() + " " + t.type()).reduce((a,b)->a+"\n"+b).orElse("No tickets"));
                }
                case "!info" -> {
                    if (!require(role, "support.info", msg.peerId())) return;
                    reply(msg.peerId(), tickets.find(p.id()).map(t -> "#"+t.id()+" "+t.text()).orElse("Not found"));
                }
                case "!close" -> {
                    if (!require(role, "support.close", msg.peerId())) return;
                    tickets.close(p.id()); reply(msg.peerId(), i18n.tr("common.done"));
                }
                case "!r" -> {
                    if (!require(role, "support.reply", msg.peerId())) return;
                    tickets.find(p.id()).ifPresentOrElse(t -> {
                        var pl = Bukkit.getPlayer(t.playerUuid());
                        if (pl != null) {
                            pl.sendMessage(i18n.tr("player.support.reply", Map.of("text", p.tail())));
                            tickets.markAnswered(t.id(), msg.fromId(), settings.autoCloseOnReply());
                            relay.event("💬 Reply sent to " + t.playerName() + " for ticket #" + t.id());
                            reply(msg.peerId(), i18n.tr("support.reply_sent", Map.of("player", t.playerName(), "id", String.valueOf(t.id()))));
                        } else {
                            pending.put(new PendingReply(t.playerUuid(), t.playerName(), t.id(), p.tail(), msg.fromId(), String.valueOf(msg.fromId()), Instant.now().toEpochMilli()));
                            tickets.markAnswered(t.id(), msg.fromId(), false);
                            relay.event("💾 " + t.playerName() + " is offline. Reply for ticket #" + t.id() + " has been saved.");
                            reply(msg.peerId(), i18n.tr("support.reply_saved"));
                        }
                    }, () -> reply(msg.peerId(), i18n.tr("support.not_found")));
                }
                default -> reply(msg.peerId(), i18n.tr("support.unknown"));
            }
        } catch (Exception e) {
            reply(msg.peerId(), i18n.tr("support.malformed"));
        }
    }

    private void reply(long peerId, String text) { try { api.send(peerId, text); } catch (Exception ignored) {} }
}
