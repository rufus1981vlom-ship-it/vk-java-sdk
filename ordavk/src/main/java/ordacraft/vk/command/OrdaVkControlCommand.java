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
            sender.sendMessage("Usage: /ordavk <reload|bootstrap>");
            return true;
        }

        String sub = args[0].toLowerCase();
        if ("reload".equals(sub)) {
            if (!(sender instanceof ConsoleCommandSender) && !sender.hasPermission("ordavk.reload")) {
                sender.sendMessage("§cNo permission");
                return true;
            }
            String result = plugin.reloadManager();
            sender.sendMessage(result);
            return true;
        }

        if ("bootstrap".equals(sub)) {
            if (!(sender instanceof ConsoleCommandSender)) {
                sender.sendMessage("§cBootstrap is console-only");
                return true;
            }
            if (args.length != 4) {
                sender.sendMessage("Usage: ordavk bootstrap <vk_id> <mc_nick> <role>");
                return true;
            }

            long vkId;
            try {
                vkId = Long.parseLong(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("Invalid vk_id: must be numeric");
                return true;
            }

            Role role;
            try {
                role = Role.fromString(args[3]);
            } catch (Exception e) {
                sender.sendMessage("Invalid role. Use: helper|moder|admin|staff|chief");
                return true;
            }

            String result = plugin.bootstrapAdmin(vkId, args[2], role);
            sender.sendMessage(result);
            return true;
        }

        sender.sendMessage("Unknown subcommand: " + args[0]);
        return true;
    }
}
