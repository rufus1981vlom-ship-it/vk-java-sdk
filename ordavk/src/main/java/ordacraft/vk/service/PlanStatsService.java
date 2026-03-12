package ordacraft.vk.service;

import org.bukkit.OfflinePlayer;

import java.util.Optional;

public interface PlanStatsService {
    record PlaytimeSnapshot(long todaySec, Long yesterdaySec, Long weekSec, Long totalSec) {}

    Optional<PlaytimeSnapshot> getPlaytime(OfflinePlayer player);

    static String formatHours(long seconds) {
        double h = seconds / 3600.0;
        return String.format(java.util.Locale.ROOT, "%.1f ч", h);
    }

    static String formatHuman(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        if (h > 0) return h + " ч " + m + " мин";
        return m + " мин";
    }
}
