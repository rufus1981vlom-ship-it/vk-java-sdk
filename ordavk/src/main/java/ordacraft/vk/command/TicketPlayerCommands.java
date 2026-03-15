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
    private final java.util.function.Consumer<String> notifyManageChat;

    public TicketPlayerCommands(SupportTicketService tickets, int cooldownSec, int maxOpen,
                                LocalizationService i18n,
                                java.util.function.Consumer<String> notifySupportChat,
                                java.util.function.Consumer<String> notifyManageChat) {
        this.tickets = tickets;
        this.cooldownSec = cooldownSec;
        this.maxOpen = maxOpen;
        this.i18n = i18n;
        this.notifySupportChat = notifySupportChat;
        this.notifyManageChat = notifyManageChat;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;

        String cmdName = command.getName().toLowerCase();
        boolean helpopLike = cmdName.equals("helpop") || cmdName.equals("ac");
        boolean reportLike = cmdName.equals("report") || cmdName.equals("rep");

        if (args.length == 0) {
            if (helpopLike) {
                p.sendMessage(i18n.tr("player.support.helpop_usage"));
            } else if (reportLike) {
                p.sendMessage(i18n.tr("player.support.report_usage"));
            } else {
                p.sendMessage(i18n.tr("player.support.enter_text"));
            }
            return true;
        }
        if (tickets.isCooldown(p.getUniqueId(), cooldownSec)) { p.sendMessage(i18n.tr("player.support.cooldown")); return true; }
        if (tickets.openBy(p.getUniqueId()) >= maxOpen) { p.sendMessage(i18n.tr("player.support.max_open")); return true; }

        if (helpopLike) {
            var t = tickets.create(TicketType.QUESTION, p.getUniqueId(), p.getName(), "", String.join(" ", args));
            tickets.touch(p.getUniqueId());
            p.sendMessage(i18n.tr("player.support.sent", Map.of("id", String.valueOf(t.id()))));
            notifySupportChat.accept("#" + t.id() + " Новый вопрос от " + p.getName() + ": " + t.text());
            return true;
        }

        if (reportLike) {
            if (args.length < 2) { p.sendMessage(i18n.tr("player.support.report_usage")); return true; }
            String target = args[0];
            String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            var t = tickets.create(TicketType.REPORT, p.getUniqueId(), p.getName(), target, reason);
            tickets.touch(p.getUniqueId());
            p.sendMessage(i18n.tr("player.support.sent", Map.of("id", String.valueOf(t.id()))));
            notifySupportChat.accept("#" + t.id() + " Новая жалоба от " + p.getName() + " -> " + target + ": " + t.text());
            notifyManageChat.accept("#" + t.id() + " Жалоба: " + p.getName() + " -> " + target + ": " + t.text());
            return true;
        }
        return true;
    }
}
