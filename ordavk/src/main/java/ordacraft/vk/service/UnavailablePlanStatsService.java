package ordacraft.vk.service;

import org.bukkit.OfflinePlayer;

import java.util.Optional;

public class UnavailablePlanStatsService implements PlanStatsService {
    @Override
    public Optional<PlaytimeSnapshot> getPlaytime(OfflinePlayer player) {
        return Optional.empty();
    }
}
