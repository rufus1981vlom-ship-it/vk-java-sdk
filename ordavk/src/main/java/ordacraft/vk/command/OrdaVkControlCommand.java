package ordacraft.vk.command;

import ordacraft.vk.OrdaVKPlugin;
import ordacraft.vk.admin.Role;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

public class OrdaVkControlCommand implements CommandExecutor {
    private final OrdaVKPlugin plugin;

    public OrdaVkControlCommand(OrdaVKPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Использование: /ordavk <reload|bootstrap|status|doctor|testvk>");
            return true;
        }

        String sub = args[0].toLowerCase();
        if ("reload".equals(sub)) {
            if (!(sender instanceof ConsoleCommandSender) && !sender.hasPermission("ordavk.reload")) {
                sender.sendMessage("§cНедостаточно прав");
                return true;
            }
            sender.sendMessage(plugin.reloadManager());
            return true;
        }

        if ("status".equals(sub)) {
            sender.sendMessage(plugin.statusReport());
            return true;
        }

        if ("doctor".equals(sub)) {
            sender.sendMessage(plugin.doctorReport());
            return true;
        }

        if ("testvk".equals(sub)) {
            sender.sendMessage(plugin.testVk());
            return true;
        }

        if ("bootstrap".equals(sub)) {
            if (!(sender instanceof ConsoleCommandSender)) {
                sender.sendMessage("§cBootstrap доступен только из консоли");
                return true;
            }
            if (args.length != 4) {
                sender.sendMessage("Использование: ordavk bootstrap <vk_id> <mc_nick> <role>");
                return true;
            }

            long vkId;
            try {
                vkId = Long.parseLong(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("Некорректный vk_id: требуется число");
                return true;
            }

            Role role;
            try {
                role = Role.fromString(args[3]);
            } catch (Exception e) {
                sender.sendMessage("Некорректная роль. Используйте: helper|moder|admin|staff|chief");
                return true;
            }

            sender.sendMessage(plugin.bootstrapAdmin(vkId, args[2], role));
            return true;
        }

        sender.sendMessage("Неизвестная подкоманда: " + args[0]);
        return true;
    }
}
