package ordacraft.vk.command;

import ordacraft.vk.service.LocalizationService;
import ordacraft.vk.support.SupportTicketService;
import ordacraft.vk.support.TicketType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Map;

public class TicketPlayerCommands implements CommandExecutor {
    private final SupportTicketService tickets;
    private final int cooldownSec;
    private final int maxOpen;
    private final LocalizationService i18n;
    private final java.util.function.Consumer<String> notifySupportChat;

    public TicketPlayerCommands(SupportTicketService tickets, int cooldownSec, int maxOpen, LocalizationService i18n, java.util.function.Consumer<String> notifySupportChat) {
        this.tickets = tickets; this.cooldownSec = cooldownSec; this.maxOpen = maxOpen; this.i18n = i18n; this.notifySupportChat = notifySupportChat;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        if (args.length == 0) { p.sendMessage(i18n.tr("player.support.enter_text")); return true; }
        if (tickets.isCooldown(p.getUniqueId(), cooldownSec)) { p.sendMessage(i18n.tr("player.support.cooldown")); return true; }
        if (tickets.openBy(p.getUniqueId()) >= maxOpen) { p.sendMessage(i18n.tr("player.support.max_open")); return true; }

        if (command.getName().equalsIgnoreCase("helpop")) {
            var t = tickets.create(TicketType.QUESTION, p.getUniqueId(), p.getName(), "", String.join(" ", args));
            tickets.touch(p.getUniqueId());
            p.sendMessage(i18n.tr("player.support.sent", Map.of("id", String.valueOf(t.id()))));
            notifySupportChat.accept("#" + t.id() + " New question from " + p.getName() + ": " + t.text());
            return true;
        }

        if (command.getName().equalsIgnoreCase("report")) {
            if (args.length < 2) { p.sendMessage("/report <player> <reason>"); return true; }
            String target = args[0];
            String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            var t = tickets.create(TicketType.REPORT, p.getUniqueId(), p.getName(), target, reason);
            tickets.touch(p.getUniqueId());
            p.sendMessage(i18n.tr("player.support.sent", Map.of("id", String.valueOf(t.id()))));
            notifySupportChat.accept("#" + t.id() + " New report from " + p.getName() + " -> " + target + ": " + t.text());
            return true;
        }
        return true;
    }
}
