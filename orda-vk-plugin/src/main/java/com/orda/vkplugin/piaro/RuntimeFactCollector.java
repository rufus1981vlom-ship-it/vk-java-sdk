package com.orda.vkplugin.piaro;

import com.orda.vkplugin.PiarOrdaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

public class RuntimeFactCollector implements Listener {
    private final PiarOrdaPlugin plugin;
    private final AtomicInteger joins = new AtomicInteger();
    private final AtomicInteger quits = new AtomicInteger();
    private final AtomicInteger deaths = new AtomicInteger();
    private final AtomicInteger pvpKills = new AtomicInteger();
    private final AtomicInteger peakOnline = new AtomicInteger();
    private final Deque<String> liveEvents = new ArrayDeque<>();

    public RuntimeFactCollector(PiarOrdaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        joins.incrementAndGet();
        int online = Bukkit.getOnlinePlayers().size();
        peakOnline.updateAndGet(prev -> Math.max(prev, online));
        addEvent("Игрок " + event.getPlayer().getName() + " зашел на сервер");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        quits.incrementAndGet();
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        deaths.incrementAndGet();
        addEvent("Смерть: " + event.getDeathMessage());
    }

    @EventHandler
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player damager)) return;
        if (victim.getHealth() - event.getFinalDamage() <= 0) {
            pvpKills.incrementAndGet();
            addEvent("PvP: " + damager.getName() + " победил " + victim.getName());
        }
    }

    public FactSnapshot snapshot() {
        return new FactSnapshot(
                Instant.now(),
                Bukkit.getOnlinePlayers().size(),
                peakOnline.get(),
                joins.get(),
                deaths.get(),
                pvpKills.get(),
                String.join("; ", liveEvents),
                plugin.getConfig().getString("auto-pr.sources.events", "Ивенты проходят по расписанию"),
                plugin.getConfig().getString("auto-pr.sources.changelog", "Небольшие фиксы и улучшения"),
                plugin.getConfig().getString("auto-pr.sources.map", "Обновлены точки интереса на карте")
        );
    }

    private void addEvent(String event) {
        if (liveEvents.size() >= 20) {
            liveEvents.pollFirst();
        }
        liveEvents.addLast(event);
    }

    public record FactSnapshot(
            Instant createdAt,
            int online,
            int peakOnline,
            int joins,
            int deaths,
            int pvpKills,
            String liveEvents,
            String events,
            String changelog,
            String mapInfo
    ) {}
}
