package ordacraft.vk.bridge;

import ordacraft.vk.admin.AdminRecord;
import ordacraft.vk.admin.AdminRegistry;
import ordacraft.vk.admin.Role;
import ordacraft.vk.admin.RoleService;
import ordacraft.vk.command.CommandPolicyService;
import ordacraft.vk.command.DangerousCommandInspector;
import ordacraft.vk.config.ChatMode;
import ordacraft.vk.config.PluginSettings;
import ordacraft.vk.governance.GovernanceService;
import ordacraft.vk.service.ConsoleDispatchService;
import ordacraft.vk.service.EventRelayService;
import ordacraft.vk.service.LocalizationService;
import ordacraft.vk.service.PermissionMatrixService;
import ordacraft.vk.service.AuditService;
import ordacraft.vk.support.PendingReply;
import ordacraft.vk.support.PendingActionService;
import ordacraft.vk.support.PendingReplyService;
import ordacraft.vk.support.SupportTicketService;
import ordacraft.vk.vk.api.VkApiClient;
import ordacraft.vk.vk.model.VkIncomingMessage;
import ordacraft.vk.vk.parser.BanCommandParser;
import ordacraft.vk.vk.parser.SupportCommandParser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class VkMessageRouter {
    private final PluginSettings settings;
    private final VkApiClient api;
    private final AdminRegistry admins;
    private final SupportTicketService tickets;
    private final PendingReplyService pending;
    private final PendingActionService pendingActions;
    private final CommandPolicyService cmdPolicy;
    private final ConsoleDispatchService console;
    private final GovernanceService governance;
    private final EventRelayService relay;
    private final AuditService audit;
    private final LocalizationService i18n;
    private final PermissionMatrixService matrix;
    private final RoleService roleService = new RoleService();
    private final DangerousCommandInspector dangerousInspector = new DangerousCommandInspector();
    private final long bootAt = System.currentTimeMillis();

    public VkMessageRouter(PluginSettings settings, VkApiClient api, AdminRegistry admins, SupportTicketService tickets,
                           PendingReplyService pending, CommandPolicyService cmdPolicy, ConsoleDispatchService console,
                           GovernanceService governance, EventRelayService relay,
                           LocalizationService i18n, PermissionMatrixService matrix) {
        this(settings, api, admins, tickets, pending, null, cmdPolicy, console, governance, relay, null, i18n, matrix);
    }

    public VkMessageRouter(PluginSettings settings, VkApiClient api, AdminRegistry admins, SupportTicketService tickets,
                           PendingReplyService pending, PendingActionService pendingActions,
                           CommandPolicyService cmdPolicy, ConsoleDispatchService console,
                           GovernanceService governance, EventRelayService relay, AuditService audit,
                           LocalizationService i18n, PermissionMatrixService matrix) {
        this.settings = settings;
        this.api = api;
        this.admins = admins;
        this.tickets = tickets;
        this.pending = pending;
        this.pendingActions = pendingActions;
        this.cmdPolicy = cmdPolicy;
        this.console = console;
        this.governance = governance;
        this.relay = relay;
        this.audit = audit;
        this.i18n = i18n;
        this.matrix = matrix;
    }

    public void onMessage(VkIncomingMessage msg) {
        ChatMode mode = resolveChatMode(msg.peerId());
        if (mode == ChatMode.IGNORE || mode == ChatMode.EVENTS) return;

        var actor = admins.find(msg.fromId());
        if (mode == ChatMode.MANAGE || mode == ChatMode.MMANAGE || mode == ChatMode.AMANAGE) {
            handleManage(msg, actor.orElse(null));
        }
        if (mode == ChatMode.SUPPORT) handleSupport(msg, actor.map(a -> a.role()).orElse(null));
    }

    private ChatMode resolveChatMode(long peerId) {
        final long chatOffset = 2_000_000_000L;
        for (var chat : settings.chats()) {
            if (chat.id() == peerId) {
                return chat.mode();
            }
            // Разрешаем настраивать id как peer_id (2000000001) или как chat_id (1)
            if (peerId >= chatOffset && chat.id() > 0 && chat.id() < chatOffset && chat.id() + chatOffset == peerId) {
                return chat.mode();
            }
        }
        return ChatMode.IGNORE;
    }

    private boolean require(Role role, String key, long peerId) {
        if (role == null || !matrix.allowed(role, key)) {
            reply(peerId, i18n.tr("common.not_enough_permission"));
            return false;
        }
        return true;
    }

    private void handleManage(VkIncomingMessage msg, AdminRecord actor) {
        Role role = actor == null ? null : actor.role();
        String commandText = msg.text() == null ? "" : msg.text().trim();
        if (!commandText.startsWith("!")) {
            return;
        }

        if ("!help".equalsIgnoreCase(commandText)) {
            if (!require(role, "manage.help", msg.peerId())) return;
            reply(msg.peerId(), i18n.tr("manage.help"));
            return;
        }

        if ("!online".equalsIgnoreCase(commandText)) {
            if (!require(role, "manage.online", msg.peerId())) return;
            List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList();
            String body = names.isEmpty() ? i18n.tr("manage.online.empty") : String.join(", ", names);
            reply(msg.peerId(), i18n.tr("manage.online", Map.of("count", String.valueOf(names.size()), "players", body)));
            return;
        }

        if ("!status".equalsIgnoreCase(commandText)) {
            if (!require(role, "manage.status", msg.peerId())) return;
            int online = Bukkit.getOnlinePlayers().size();
            int max = Bukkit.getMaxPlayers();
            Runtime rt = Runtime.getRuntime();
            long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long maxMb = rt.maxMemory() / (1024 * 1024);
            String tps = formatTps();
            String uptime = formatUptime();
            reply(msg.peerId(), i18n.tr("manage.status", Map.of(
                    "online", String.valueOf(online),
                    "max", String.valueOf(max),
                    "tps", tps,
                    "memory", usedMb + "MB/" + maxMb + "MB",
                    "uptime", uptime
            )));
            return;
        }

        if (commandText.startsWith("!check ")) {
            if (!require(role, "manage.check", msg.peerId())) return;
            String[] p = commandText.split("\\s+", 2);
            if (p.length < 2 || p[1].isBlank()) {
                reply(msg.peerId(), i18n.tr("manage.usage.check"));
                return;
            }
            OfflinePlayer op = Bukkit.getOfflinePlayer(p[1].trim());
            String uuid = op.getUniqueId() == null ? "-" : op.getUniqueId().toString();
            Player online = op.getPlayer();
            String status = online != null && online.isOnline() ? i18n.tr("manage.check.online") : i18n.tr("manage.check.offline");
            String ping = online == null ? "-" : readPing(online);
            String group = resolveGroupByNick(op.getName());
            String lastSeen = op.getLastSeen() <= 0 ? "-" : Instant.ofEpochMilli(op.getLastSeen()).toString();
            reply(msg.peerId(), i18n.tr("manage.check", Map.of(
                    "nick", op.getName() == null ? p[1].trim() : op.getName(),
                    "status", status,
                    "uuid", uuid,
                    "group", group,
                    "last_seen", lastSeen,
                    "ping", ping
            )));
            return;
        }

        if (commandText.equalsIgnoreCase("!admins")) {
            if (!require(role, "manage.admins", msg.peerId())) return;
            StringBuilder sb = new StringBuilder("Администраторы:\n");
            admins.all().stream().sorted(Comparator.comparingLong(AdminRecord::vkId))
                    .forEach(a -> sb.append(a.vkId()).append(" -> ").append(a.mcNick()).append(" (").append(a.role().name().toLowerCase()).append(")\n"));
            reply(msg.peerId(), sb.toString());
            return;
        }

        if (commandText.startsWith("!admin info ")) {
            if (!require(role, "manage.admin.info", msg.peerId())) return;
            String[] p = commandText.split("\\s+");
            if (p.length != 3) {
                reply(msg.peerId(), i18n.tr("manage.usage.admin_info"));
                return;
            }
            Long vkId = parseVkId(p[2], msg.peerId());
            if (vkId == null) return;
            Optional<AdminRecord> target = admins.find(vkId);
            if (target.isEmpty()) {
                reply(msg.peerId(), i18n.tr("manage.admin_not_found"));
                return;
            }
            AdminRecord a = target.get();
            reply(msg.peerId(), i18n.tr("manage.admin_info", Map.of(
                    "vk", String.valueOf(a.vkId()),
                    "nick", emptyAsDash(a.mcNick()),
                    "role", a.role().name().toLowerCase(),
                    "created", String.valueOf(a.createdAt()),
                    "updated", String.valueOf(a.updatedAt())
            )));
            return;
        }

        if (commandText.startsWith("!admin add ")) {
            if (!require(role, "manage.admin.add", msg.peerId())) return;
            String[] p = commandText.split("\\s+");
            if (p.length != 5) {
                reply(msg.peerId(), i18n.tr("manage.usage.admin_add"));
                return;
            }
            Long vkId = parseVkId(p[2], msg.peerId());
            if (vkId == null) return;
            Role targetRole = parseRole(p[4], msg.peerId());
            if (targetRole == null) return;
            if (!roleService.canAssign(role, targetRole)) {
                reply(msg.peerId(), i18n.tr("common.not_enough_permission"));
                return;
            }
            admins.upsert(vkId, p[3], targetRole);
            admins.save();
            relay.event("🛡 admin_add actor=" + actor.vkId() + " target=" + vkId + " nick=" + p[3] + " role=" + targetRole.name().toLowerCase());
            reply(msg.peerId(), i18n.tr("manage.admin_added", Map.of("vk", String.valueOf(vkId), "nick", p[3], "role", targetRole.name().toLowerCase())));
            return;
        }

        if (commandText.startsWith("!admin set ")) {
            if (!require(role, "manage.admin.set", msg.peerId())) return;
            String[] p = commandText.split("\\s+");
            if (p.length != 5) {
                reply(msg.peerId(), i18n.tr("manage.usage.admin_set"));
                return;
            }
            Long vkId = parseVkId(p[2], msg.peerId());
            if (vkId == null) return;
            String newNick = p[3];
            Role targetRole = parseRole(p[4], msg.peerId());
            if (targetRole == null) return;
            Optional<AdminRecord> existing = admins.find(vkId);
            if (existing.isEmpty()) {
                reply(msg.peerId(), i18n.tr("manage.admin_not_found"));
                return;
            }
            if (!roleService.canAssign(role, targetRole)) {
                reply(msg.peerId(), i18n.tr("common.not_enough_permission"));
                return;
            }
            String nickToSave = newNick;
            admins.upsert(vkId, nickToSave, targetRole);
            admins.save();
            relay.event("🛡 admin_set actor=" + actor.vkId() + " target=" + vkId + " nick=" + nickToSave + " role=" + targetRole.name().toLowerCase());
            reply(msg.peerId(), i18n.tr("manage.admin_set", Map.of("vk", String.valueOf(vkId), "role", targetRole.name().toLowerCase())));
            return;
        }

        if (commandText.startsWith("!rname ") || commandText.startsWith("!рнейм ")) {
            if (!require(role, "manage.admin.rname", msg.peerId())) return;
            String[] p = commandText.split("\\s+");
            if (p.length != 3) {
                reply(msg.peerId(), i18n.tr("manage.usage.admin_rname"));
                return;
            }
            Long vkId = parseVkId(p[1], msg.peerId());
            if (vkId == null) return;
            String newNick = p[2];
            Optional<AdminRecord> existing = admins.find(vkId);
            if (existing.isEmpty()) {
                reply(msg.peerId(), i18n.tr("manage.admin_not_found"));
                return;
            }
            if (!roleService.canManage(role, existing.get().role())) {
                reply(msg.peerId(), i18n.tr("manage.target_higher_or_equal"));
                return;
            }
            admins.upsert(vkId, newNick, existing.get().role());
            admins.save();
            relay.event("🛡 admin_rname actor=" + actor.vkId() + " target=" + vkId + " nick=" + newNick);
            reply(msg.peerId(), i18n.tr("manage.admin_renamed", Map.of("vk", String.valueOf(vkId), "nick", newNick)));
            return;
        }

        if (commandText.startsWith("!kick ")) {
            if (!require(role, "manage.kick", msg.peerId())) return;
            String[] p = commandText.split("\\s+", 3);
            if (p.length < 3) {
                reply(msg.peerId(), i18n.tr("manage.usage.kick"));
                return;
            }
            relay.event("⛔ Kick: " + p[1] + " | Причина: " + p[2] + " | Инициатор: " + actorLabel(actor));
            Player target = Bukkit.getPlayerExact(p[1]);
            if (target == null || !target.isOnline()) {
                reply(msg.peerId(), i18n.tr("manage.kick.online_only"));
                return;
            }
            console.dispatch("kick " + p[1] + " " + p[2]);
            audit("kick", "target=" + p[1] + ", reason=" + p[2] + ", actor=" + actorLabel(actor));
            reply(msg.peerId(), i18n.tr("common.done"));
            return;
        }

        if (commandText.startsWith("!mute ")) {
            if (!require(role, "manage.mute", msg.peerId())) return;
            String[] p = commandText.split("\\s+", 4);
            if (p.length < 4) {
                reply(msg.peerId(), i18n.tr("manage.usage.mute"));
                return;
            }
            String command = "tempmute " + p[1] + " " + p[2] + " " + p[3];
            relay.event("🔇 TempMute: " + p[1] + " на " + p[2] + " | Причина: " + p[3] + " | Инициатор: " + actorLabel(actor));
            if (isOnline(p[1])) {
                console.dispatch(command);
                audit("mute", "target=" + p[1] + ", duration=" + p[2] + ", reason=" + p[3] + ", actor=" + actorLabel(actor));
                reply(msg.peerId(), i18n.tr("common.done"));
            } else {
                enqueuePendingAction(p[1], "mute", command, actor);
                reply(msg.peerId(), i18n.tr("manage.pending_saved"));
            }
            return;
        }

        if (commandText.startsWith("!unmute ")) {
            if (!require(role, "manage.mute", msg.peerId())) return;
            String[] p = commandText.split("\\s+", 2);
            if (p.length < 2 || p[1].isBlank()) {
                reply(msg.peerId(), "❌ Использование: !unmute <player>");
                return;
            }
            String command = "unmute " + p[1].trim();
            if (isOnline(p[1].trim())) {
                console.dispatch(command);
                relay.event("🔈 UnMute: " + p[1].trim() + " | Инициатор: " + actorLabel(actor));
                audit("unmute", "target=" + p[1].trim() + ", actor=" + actorLabel(actor));
                reply(msg.peerId(), i18n.tr("common.done"));
            } else {
                enqueuePendingAction(p[1].trim(), "unmute", command, actor);
                reply(msg.peerId(), i18n.tr("manage.pending_saved"));
            }
            return;
        }

        if (commandText.startsWith("!ban ")) {
            if (!require(role, "manage.ban", msg.peerId())) return;
            String[] p = commandText.split("\\s+", 4);
            if (p.length < 3) {
                reply(msg.peerId(), i18n.tr("manage.usage.ban"));
                return;
            }
            String cmd = p.length == 3 ? BanCommandParser.toConsole(p[1], null, p[2]) : BanCommandParser.toConsole(p[1], p[2], p[3]);
            if (cmd.toLowerCase().startsWith("tempban ")) {
                relay.event("⛔ TempBan: " + p[1] + " на " + p[2] + " | Причина: " + p[3] + " | Инициатор: " + actorLabel(actor));
            } else {
                String reason = p.length == 3 ? p[2] : (p[2] + " " + p[3]);
                relay.event("⛔ Ban: " + p[1] + " навсегда | Причина: " + reason + " | Инициатор: " + actorLabel(actor));
            }
            if (isOnline(p[1])) {
                console.dispatch(cmd);
                audit("ban", "target=" + p[1] + ", cmd=" + cmd + ", actor=" + actorLabel(actor));
                reply(msg.peerId(), i18n.tr("manage.executed", Map.of("cmd", cmd)));
            } else {
                enqueuePendingAction(p[1], "ban", cmd, actor);
                reply(msg.peerId(), i18n.tr("manage.pending_saved"));
            }
            return;
        }

        if (commandText.startsWith("!unban ")) {
            if (!require(role, "manage.ban", msg.peerId())) return;
            String[] p = commandText.split("\\s+", 2);
            if (p.length < 2 || p[1].isBlank()) {
                reply(msg.peerId(), "❌ Использование: !unban <player>");
                return;
            }
            String command = "pardon " + p[1].trim();
            if (isOnline(p[1].trim())) {
                console.dispatch(command);
                relay.event("✅ UnBan: " + p[1].trim() + " | Инициатор: " + actorLabel(actor));
                audit("unban", "target=" + p[1].trim() + ", actor=" + actorLabel(actor));
                reply(msg.peerId(), i18n.tr("common.done"));
            } else {
                enqueuePendingAction(p[1].trim(), "unban", command, actor);
                reply(msg.peerId(), i18n.tr("manage.pending_saved"));
            }
            return;
        }

        if (commandText.startsWith("!admin remove ")) {
            if (!require(role, "manage.admin.remove", msg.peerId())) return;
            String[] p = commandText.split("\\s+");
            if (p.length != 3) {
                reply(msg.peerId(), i18n.tr("manage.usage.admin_remove"));
                return;
            }

            Long targetVkId = parseVkId(p[2], msg.peerId());
            if (targetVkId == null) return;

            var target = admins.find(targetVkId);
            if (target.isEmpty()) {
                reply(msg.peerId(), i18n.tr("manage.admin_not_found"));
                return;
            }

            if (!roleService.canManage(role, target.get().role())) {
                reply(msg.peerId(), i18n.tr("manage.target_higher_or_equal"));
                return;
            }

            String nick = target.get().mcNick();
            boolean lpSent = nick != null && !nick.isBlank();
            if (lpSent) {
                console.dispatch("lp user " + nick + " parent set default");
            }

            GovernanceService.RemovalStats stats = governance.removeUserFromAllChats(targetVkId);

            admins.remove(targetVkId);
            admins.save();

            relay.event("🛡 Администратор снят: VK " + targetVkId
                    + " | Ник: " + emptyAsDash(nick)
                    + " | Группа сброшена: " + (lpSent ? i18n.tr("common.yes") : i18n.tr("common.no"))
                    + " | Удалён из бесед: " + stats.removed()
                    + " | Не удалось: " + stats.failed()
                    + " | Инициатор: " + actor.vkId());
            audit("admin_remove", "target=" + targetVkId + ", nick=" + emptyAsDash(nick) + ", actor=" + actor.vkId());

            reply(msg.peerId(), i18n.tr("manage.admin_removed.summary", Map.of(
                    "vk", String.valueOf(targetVkId),
                    "nick", emptyAsDash(nick),
                    "lp", lpSent ? i18n.tr("common.yes") : i18n.tr("common.no"),
                    "removed", String.valueOf(stats.removed()),
                    "failed", String.valueOf(stats.failed()),
                    "not_found", String.valueOf(stats.notFound()),
                    "no_permissions", String.valueOf(stats.noPermissions()),
                    "api_errors", String.valueOf(stats.apiErrors())
            )));
            return;
        }

        if (commandText.startsWith("!cmd ")) {
            if (!require(role, "manage.cmd", msg.peerId())) return;
            String raw = commandText.substring(5).trim();
            if (raw.isEmpty()) {
                reply(msg.peerId(), i18n.tr("manage.empty_command"));
                return;
            }
            if (!cmdPolicy.canUseRaw(role)) {
                reply(msg.peerId(), i18n.tr("common.not_enough_permission"));
                return;
            }
            if (!cmdPolicy.allowsRaw(role, raw)) {
                reply(msg.peerId(), i18n.tr("manage.cmd_blocked_by_policy"));
                return;
            }
            console.dispatch(raw);
            String normalized = dangerousInspector.normalize(raw);
            DangerousCommandInspector.LpGroupChange lp = dangerousInspector.parseLpGroupChange(normalized);
            if (lp != null) {
                switch (lp.action()) {
                    case SET -> relay.event("👑 Группа: " + lp.user() + " -> " + lp.group() + " | Инициатор: " + actorLabel(actor));
                    case ADD -> relay.event("👑 Группа добавлена: " + lp.user() + " + " + lp.group() + " | Инициатор: " + actorLabel(actor));
                    case REMOVE -> relay.event("👑 Группа снята: " + lp.user() + " - " + lp.group() + " | Инициатор: " + actorLabel(actor));
                }
            } else if (dangerousInspector.isDangerous(normalized)) {
                relay.event("⚠️ Raw command: " + normalized + " | Инициатор: " + actorLabel(actor));
            }
            reply(msg.peerId(), i18n.tr("manage.executed", Map.of("cmd", raw)));
            audit("raw_command", "actor=" + actorLabel(actor) + ", cmd=" + raw);
            return;
        }

        reply(msg.peerId(), i18n.tr("manage.unknown"));
    }

    private void handleSupport(VkIncomingMessage msg, Role role) {
        String commandText = msg.text() == null ? "" : msg.text().trim();
        if (!commandText.startsWith("!")) {
            return;
        }
        if (role == null) {
            reply(msg.peerId(), i18n.tr("common.not_enough_permission"));
            return;
        }
        try {
            var p = SupportCommandParser.parse(commandText);
            switch (p.cmd()) {
                case "!help" -> {
                    if (!require(role, "support.list", msg.peerId())) return;
                    reply(msg.peerId(), i18n.tr("support.help"));
                }
                case "!list" -> {
                    if (!require(role, "support.list", msg.peerId())) return;
                    reply(msg.peerId(), tickets.openTickets().stream().map(ticket -> "#" + ticket.id() + " " + ticket.playerName() + " " + ticket.type()).reduce((a, b) -> a + "\n" + b).orElse("Нет открытых тикетов"));
                }
                case "!info" -> {
                    if (!require(role, "support.info", msg.peerId())) return;
                    reply(msg.peerId(), tickets.find(p.id()).map(ticket -> "#" + ticket.id() + " " + ticket.text()).orElse("Тикет не найден"));
                }
                case "!close" -> {
                    if (!require(role, "support.close", msg.peerId())) return;
                    tickets.close(p.id());
                    reply(msg.peerId(), i18n.tr("common.done"));
                }
                case "!r" -> {
                    if (!require(role, "support.reply", msg.peerId())) return;
                    tickets.find(p.id()).ifPresentOrElse(ticket -> {
                        var pl = Bukkit.getPlayer(ticket.playerUuid());
                        if (pl != null) {
                            pl.sendMessage(i18n.tr("player.support.reply", Map.of("text", p.tail())));
                            tickets.markAnswered(ticket.id(), msg.fromId(), settings.autoCloseOnReply());
                            relay.event("💬 Reply sent to " + ticket.playerName() + " for ticket #" + ticket.id());
                            reply(msg.peerId(), i18n.tr("support.reply_sent", Map.of("player", ticket.playerName(), "id", String.valueOf(ticket.id()))));
                        } else {
                            pending.put(new PendingReply(ticket.playerUuid(), ticket.playerName(), ticket.id(), p.tail(), msg.fromId(), String.valueOf(msg.fromId()), Instant.now().toEpochMilli()));
                            pending.save();
                            tickets.markAnswered(ticket.id(), msg.fromId(), false);
                            relay.event("💾 " + ticket.playerName() + " оффлайн. Ответ по тикету #" + ticket.id() + " сохранён.");
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

    private Long parseVkId(String input, long peerId) {
        String normalized = input == null ? "" : input.trim();
        if (normalized.startsWith("[id") && normalized.contains("|")) {
            normalized = normalized.substring(3, normalized.indexOf('|'));
        }
        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("id")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("https://vk.com/id")) {
            normalized = normalized.substring("https://vk.com/id".length());
        }
        if (normalized.startsWith("http://vk.com/id")) {
            normalized = normalized.substring("http://vk.com/id".length());
        }

        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException e) {
            reply(peerId, i18n.tr("manage.vk_id_numeric"));
            return null;
        }
    }

    private Role parseRole(String raw, long peerId) {
        try {
            return Role.fromString(raw);
        } catch (Exception e) {
            reply(peerId, i18n.tr("manage.invalid_role"));
            return null;
        }
    }

    private String resolveGroupByNick(String nick) {
        if (nick == null || nick.isBlank()) return "-";
        return admins.all().stream()
                .filter(a -> nick.equalsIgnoreCase(a.mcNick()))
                .map(a -> a.role().name().toLowerCase())
                .findFirst().orElse("-");
    }

    private String readPing(Player player) {
        try {
            Object v = player.getClass().getMethod("getPing").invoke(player);
            return String.valueOf(v);
        } catch (Exception ignored) {
            return "-";
        }
    }

    private String formatTps() {
        try {
            Object[] tps = (Object[]) Bukkit.class.getMethod("getTPS").invoke(null);
            if (tps.length == 0) return "-";
            return String.format("%.2f", (double) tps[0]);
        } catch (Exception ignored) {
            return "-";
        }
    }

    private String formatUptime() {
        long sec = Duration.ofMillis(System.currentTimeMillis() - bootAt).getSeconds();
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    private String emptyAsDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }


    private String actorLabel(AdminRecord actor) {
        if (actor == null) return "unknown";
        if (actor.mcNick() != null && !actor.mcNick().isBlank()) return actor.mcNick();
        return String.valueOf(actor.vkId());
    }

    private void reply(long peerId, String text) {
        try {
            api.send(peerId, text);
        } catch (Exception ignored) {
        }
    }

    private boolean isOnline(String playerName) {
        Player p = Bukkit.getPlayerExact(playerName);
        return p != null && p.isOnline();
    }

    private void enqueuePendingAction(String targetNick, String actionType, String command, AdminRecord actor) {
        if (pendingActions == null) return;
        OfflinePlayer off = Bukkit.getOfflinePlayer(targetNick);
        var uuid = off.getUniqueId() != null ? off.getUniqueId() : java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + targetNick).getBytes(StandardCharsets.UTF_8));
        pendingActions.enqueue(uuid, targetNick, actionType, command,
                actor == null ? 0L : actor.vkId(), actor == null ? "unknown" : actor.role().name().toLowerCase());
        pendingActions.save();
        relay.event("🕓 Отложенное действие сохранено: " + actionType + " для " + targetNick + " | Инициатор: " + actorLabel(actor));
        audit("pending_action_saved", "type=" + actionType + ", target=" + targetNick + ", actor=" + actorLabel(actor) + ", cmd=" + command);
    }

    private void audit(String action, String details) {
        if (audit != null) {
            audit.log(action, details);
        }
    }
}
