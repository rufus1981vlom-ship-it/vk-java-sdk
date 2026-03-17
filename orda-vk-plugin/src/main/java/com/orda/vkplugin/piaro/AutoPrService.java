package com.orda.vkplugin.piaro;

import com.orda.vkplugin.PiarOrdaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AutoPrService {
    private final PiarOrdaPlugin plugin;
    private final Storage storage;
    private final RuntimeFactCollector factCollector;
    private final OpenAiClient openAiClient;
    private final VkClient vkClient;
    private final ExecutorService ioPool = Executors.newFixedThreadPool(2);

    private BukkitTask heartbeatTask;
    private LocalDate lastMorning;
    private LocalDate lastEvening;
    private LocalDate lastWeekly;

    public AutoPrService(PiarOrdaPlugin plugin, Storage storage, RuntimeFactCollector factCollector) {
        this.plugin = plugin;
        this.storage = storage;
        this.factCollector = factCollector;
        this.openAiClient = new OpenAiClient(plugin, plugin.getLogger());
        this.vkClient = new VkClient(plugin, plugin.getLogger());
    }

    public void start() {
        if (heartbeatTask != null) return;
        heartbeatTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tick, 20L, 20L * 60L);
    }

    public void stop() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
        ioPool.shutdownNow();
    }

    public void reload() {
        // no-op, config read on every tick
    }

    private void tick() {
        if (!plugin.getConfig().getBoolean("auto-pr.auto-enable", true)) return;
        int dailyLimit = plugin.getConfig().getInt("auto-pr.daily-limit", 3);
        if (storage.countPublishedToday() >= dailyLimit) return;

        LocalDateTime now = LocalDateTime.now();
        maybeScheduleByTime(now);
        maybeScheduleEventDriven();
    }

    private void maybeScheduleByTime(LocalDateTime now) {
        LocalTime morning = LocalTime.parse(plugin.getConfig().getString("auto-pr.schedule.morning", "09:00"));
        LocalTime evening = LocalTime.parse(plugin.getConfig().getString("auto-pr.schedule.evening", "19:00"));

        if (!now.toLocalTime().isBefore(morning) && !now.toLocalDate().equals(lastMorning)) {
            createAndPublish("новости", "morning");
            lastMorning = now.toLocalDate();
        }
        if (!now.toLocalTime().isBefore(evening) && !now.toLocalDate().equals(lastEvening)) {
            createAndPublish("анонсы", "evening");
            lastEvening = now.toLocalDate();
        }

        DayOfWeek weeklyDay = DayOfWeek.valueOf(plugin.getConfig().getString("auto-pr.schedule.weekly-day", "SUNDAY").toUpperCase(Locale.ROOT));
        if (now.getDayOfWeek() == weeklyDay && !now.toLocalDate().equals(lastWeekly)) {
            createAndPublish("итоги недели", "weekly");
            lastWeekly = now.toLocalDate();
        }
    }

    private void maybeScheduleEventDriven() {
        if (!plugin.getConfig().getBoolean("auto-pr.schedule.event-driven", true)) return;
        RuntimeFactCollector.FactSnapshot facts = factCollector.snapshot();
        if (facts.pvpKills() >= plugin.getConfig().getInt("auto-pr.event-thresholds.pvp-kills", 6)
                || facts.deaths() >= plugin.getConfig().getInt("auto-pr.event-thresholds.deaths", 15)) {
            createAndPublish("живые посты по событиям", "event");
        }
    }

    private void createAndPublish(String rubric, String reason) {
        if (!rubricEnabled(rubric)) return;

        RuntimeFactCollector.FactSnapshot facts = factCollector.snapshot();
        String topicFingerprint = rubric + ":online=" + facts.online() + ":pvp=" + facts.pvpKills() + ":deaths=" + facts.deaths();
        if (storage.hasTopicInLast24h(topicFingerprint)) {
            return;
        }

        String prompt = buildPrompt(rubric, facts);
        storage.logPrompt(rubric, prompt);

        CompletableFuture<String> textFuture = openAiClient.generatePostAsync(prompt)
                .exceptionally(ex -> {
                    plugin.getLogger().warning("OpenAI text error: " + ex.getMessage());
                    return "";
                });

        textFuture.thenComposeAsync(text -> {
            if (text.isBlank() || text.length() < 60 || storage.tooSimilarRecentText(text)) {
                storage.markSkipped(rubric, "empty_or_duplicate");
                return CompletableFuture.completedFuture(false);
            }
            return maybeGenerateImage(rubric, text)
                    .thenCompose(img -> vkClient.postToWallAsync(text)
                            .thenApply(success -> {
                                storage.savePost(rubric, reason, text, img, success ? "published" : "failed");
                                if (success) {
                                    storage.addTopic(topicFingerprint);
                                }
                                return success;
                            }));
        }, ioPool).exceptionally(ex -> {
            plugin.getLogger().warning("AutoPR аварийный режим: публикация пропущена: " + ex.getMessage());
            storage.markSkipped(rubric, "exception");
            return false;
        });
    }

    private CompletableFuture<String> maybeGenerateImage(String rubric, String text) {
        if (!plugin.getConfig().getBoolean("auto-pr.image-generation.enabled", false)) {
            return CompletableFuture.completedFuture("");
        }
        String imagePrompt = "Создай обложку для поста Minecraft-сервера. Рубрика: " + rubric + ". Текст поста: " + text;
        return openAiClient.generateImageAsync(imagePrompt)
                .thenApply(bytes -> {
                    if (bytes.length == 0) return "";
                    File dir = new File(plugin.getDataFolder(), "generated-images");
                    if (!dir.exists()) dir.mkdirs();
                    File file = new File(dir, "post-" + System.currentTimeMillis() + ".png");
                    try {
                        Files.write(file.toPath(), bytes);
                        return file.getAbsolutePath();
                    } catch (IOException e) {
                        plugin.getLogger().warning("Image save failed: " + e.getMessage());
                        return "";
                    }
                }).exceptionally(ex -> {
                    plugin.getLogger().warning("OpenAI image error: " + ex.getMessage());
                    return "";
                });
    }

    private boolean rubricEnabled(String rubric) {
        Set<String> enabled = Set.copyOf(plugin.getConfig().getStringList("auto-pr.rubrics-enabled"));
        return enabled.contains(rubric);
    }

    private String buildPrompt(String rubric, RuntimeFactCollector.FactSnapshot facts) {
        String style = plugin.getConfig().getString("auto-pr.text-style", "живой, дружелюбный");
        String cta = plugin.getConfig().getString("auto-pr.cta", "Залетай на сервер прямо сейчас!");
        String serverIp = plugin.getConfig().getString("auto-pr.server-ip", "mc.example.net");
        String vkLink = plugin.getConfig().getString("auto-pr.vk.group-link", "https://vk.com/");

        return "Ты SMM-менеджер Minecraft-сервера. Язык только русский. Без канцелярита, без выдумок. "
                + "Сделай 1 пост рубрики '" + rubric + "'. Стиль: " + style + ". "
                + "Добавь CTA и в конце обязательно IP: " + serverIp + " и VK: " + vkLink + ". "
                + "Факты: online=" + facts.online() + ", peak=" + facts.peakOnline() + ", joins=" + facts.joins()
                + ", deaths=" + facts.deaths() + ", pvp=" + facts.pvpKills() + ". "
                + "События: " + facts.liveEvents() + ". Ивенты: " + facts.events() + ". Обновления: " + facts.changelog()
                + ". Карта/спавн: " + facts.mapInfo() + ". "
                + "Ограничение до 700 символов. CTA: " + cta;
    }
}
