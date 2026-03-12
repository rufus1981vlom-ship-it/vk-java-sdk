package ordacraft.vk.command;

import ordacraft.vk.service.PlanStatsService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class OvkOnlineCommand implements CommandExecutor {
    private final PlanStatsService planStats;

    public OvkOnlineCommand(PlanStatsService planStats) {
        this.planStats = planStats;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Эта команда доступна только игроку.");
            return true;
        }

        var stats = planStats.getPlaytime(player);
        if (stats.isEmpty()) {
            player.sendMessage("Статистика онлайна сейчас недоступна.");
            return true;
        }

        var s = stats.get();
        player.sendMessage("Ваш онлайн за сегодня: " + PlanStatsService.formatHuman(s.todaySec()));
        if (s.yesterdaySec() != null) {
            player.sendMessage("Ваш онлайн за вчера: " + PlanStatsService.formatHuman(s.yesterdaySec()));
        }
        if (s.weekSec() != null) {
            player.sendMessage("Ваш онлайн за неделю: " + PlanStatsService.formatHuman(s.weekSec()));
        }
        if (s.totalSec() != null) {
            player.sendMessage("Ваш общий онлайн: " + PlanStatsService.formatHuman(s.totalSec()));
        }
        return true;
    }
}
