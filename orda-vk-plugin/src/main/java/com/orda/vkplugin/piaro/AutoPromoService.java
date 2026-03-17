package com.orda.vkplugin.piaro;

import com.orda.vkplugin.PiarOrdaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class AutoPromoService {
    public record PromoGroup(int ownerId, String name, boolean enabled) {}

    private final PiarOrdaPlugin plugin;
    private final Storage storage;
    private final OpenAiClient openAiClient;
    private final VkClient vkClient;

    private BukkitTask task;
    private final List<PromoGroup> groups = new ArrayList<>();
    private final AtomicBoolean publishInProgress = new AtomicBoolean(false);

    public AutoPromoService(PiarOrdaPlugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.openAiClient = new OpenAiClient(plugin, plugin.getLogger());
        this.vkClient = new VkClient(plugin, plugin.getLogger());
        reloadGroups();
    }

    public void start() {
        if (task != null) return;
        long intervalMin = Math.max(1L, plugin.getConfig().getLong("auto-promo.interval-minutes", 20L));
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tick, 20L, 20L * 60L * intervalMin);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        publishInProgress.set(false);
    }

    public void reload() {
        reloadGroups();
        stop();
        if (plugin.getConfig().getBoolean("auto-promo.enabled", true)) {
            start();
        }
    }

    private void tick() {
        if (!plugin.getConfig().getBoolean("auto-promo.enabled", true)) return;
        if (!publishInProgress.compareAndSet(false, true)) return;

        try {
            int promoDailyLimit = plugin.getConfig().getInt("auto-promo.daily-limit", 24);
            if (storage.countPublishedTodayByRubric("promo") >= promoDailyLimit) {
                storage.markSkipped("promo", "daily_limit_reached");
                return;
            }

            List<PromoGroup> enabled = groups.stream().filter(PromoGroup::enabled).toList();
            if (enabled.isEmpty()) {
                storage.markSkipped("promo", "no_enabled_groups");
                return;
            }
            int idx = Math.floorMod(storage.getStateInt("promo_round_robin_index", 0), enabled.size());
            PromoGroup target = enabled.get(idx);

            String prompt = buildPromoPrompt(target);
            openAiClient.generatePostAsync(prompt)
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("Promo AI error: " + ex.getMessage());
                        return "";
                    })
                    .thenCompose(text -> {
                        int minLen = plugin.getConfig().getInt("auto-promo.min-post-length", 40);
                        if (text.isBlank() || text.length() < minLen || storage.tooSimilarRecentText(text)) {
                            storage.markSkipped("promo", "empty_or_duplicate");
                            return CompletableFuture.completedFuture(false);
                        }
                        return vkClient.postToWallAsync(target.ownerId(), text)
                                .thenApply(success -> {
                                    storage.savePost("promo", "round-robin:" + target.name(), text, "", success ? "published" : "failed");
                                    if (success) {
                                        storage.setStateInt("promo_round_robin_index", idx + 1);
                                    }
                                    return success;
                                });
                    })
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("Promo аварийный режим: " + ex.getMessage());
                        storage.markSkipped("promo", "exception");
                        return false;
                    })
                    .whenComplete((ok, ex) -> publishInProgress.set(false));
            return;
        } catch (Exception ex) {
            plugin.getLogger().warning("Promo tick error: " + ex.getMessage());
            storage.markSkipped("promo", "tick_exception");
        }
        publishInProgress.set(false);
    }

    private String buildPromoPrompt(PromoGroup target) {
        String site = plugin.getConfig().getString("auto-promo.server-site", "https://example.com");
        String ip = plugin.getConfig().getString("auto-promo.server-ip", "play.example.net");
        int maxLen = plugin.getConfig().getInt("auto-promo.max-post-length", 650);
        return "Сгенерируй рекламный пост для Minecraft-сервера на русском. "
                + "Стиль живой и игровой, без канцелярита и без выдумок. "
                + "Пост для VK группы: " + target.name() + ". "
                + "Обязательно укажи сайт: " + site + " и IP: " + ip + ". "
                + "Добавь CTA для захода игроков. Ограничение " + maxLen + " символов. Время: " + Instant.now();
    }

    private void reloadGroups() {
        groups.clear();
        File file = new File(plugin.getDataFolder(), "groups.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("groups");
        if (section == null) return;

        int maxTargets = plugin.getConfig().getInt("auto-promo.max-targets", 200);
        int count = 0;
        for (String key : section.getKeys(false).stream().sorted(Comparator.naturalOrder()).toList()) {
            if (count >= maxTargets) break;
            ConfigurationSection gs = section.getConfigurationSection(key);
            if (gs == null) continue;
            int id = gs.getInt("id", 0);
            if (id == 0) continue;
            int ownerId = id > 0 ? -id : id;
            groups.add(new PromoGroup(ownerId, key, gs.getBoolean("enabled", true)));
            count++;
        }
    }
}
