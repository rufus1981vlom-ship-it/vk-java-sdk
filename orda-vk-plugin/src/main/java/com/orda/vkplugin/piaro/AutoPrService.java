package com.orda.vkplugin.piaro;

import com.orda.vkplugin.PiarOrdaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class AutoPrService {
    private final PiarOrdaPlugin plugin;
    private final Storage storage;
    private final RuntimeFactCollector factCollector;
    private final OpenAiClient openAiClient;
    private final VkClient vkClient;
    private ExecutorService ioPool;

    private final AtomicBoolean publishInProgress = new AtomicBoolean(false);
    private BukkitTask heartbeatTask;
    private LocalDate lastMorning;
    private LocalDate lastEvening;
    private LocalDate lastWeekly;
    private Instant lastSmmAt = Instant.EPOCH;
    private Instant lastEventDrivenAt = Instant.EPOCH;

    public AutoPrService(PiarOrdaPlugin plugin, Storage storage, RuntimeFactCollector factCollector) {
        this.plugin = plugin;
        this.storage = storage;
        this.factCollector = factCollector;
        this.openAiClient = new OpenAiClient(plugin, plugin.getLogger());
        this.vkClient = new VkClient(plugin, plugin.getLogger());
    }

    public void start() {
        if (heartbeatTask != null) return;
        plugin.debug("auto-pr:start");
        ioPool = Executors.newFixedThreadPool(2);
        heartbeatTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tick, 20L, 20L * 60L);
    }

    public void stop() {
        plugin.debug("auto-pr:stop");
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
        if (ioPool != null) {
            ioPool.shutdownNow();
            ioPool = null;
        }
        publishInProgress.set(false);
    }

    public void reload() {
        // config read on every tick
    }

    private void tick() {
        if (!plugin.getConfig().getBoolean("auto-pr.auto-enable", true)) return;
        if (publishInProgress.get()) return;
        storage.setStateString("debug.last_stage", "auto-pr:tick");

        int dailyLimit = plugin.getConfig().getInt("auto-pr.daily-limit", 6);
        if (storage.countPublishedToday() >= dailyLimit) return;

        LocalDateTime now = LocalDateTime.now();
        maybeScheduleByTime(now);
        maybeScheduleEventDriven();
        maybeScheduleSmmPeriodic();
    }

    private void maybeScheduleByTime(LocalDateTime now) {
        LocalTime morning = safeTime("auto-pr.schedule.morning", "09:00");
        LocalTime evening = safeTime("auto-pr.schedule.evening", "19:00");

        if (!now.toLocalTime().isBefore(morning) && !now.toLocalDate().equals(lastMorning)) {
            createAndPublish("новости", "morning");
            lastMorning = now.toLocalDate();
        }
        if (!now.toLocalTime().isBefore(evening) && !now.toLocalDate().equals(lastEvening)) {
            createAndPublish("анонсы", "evening");
            lastEvening = now.toLocalDate();
        }

        DayOfWeek weeklyDay = safeDayOfWeek(plugin.getConfig().getString("auto-pr.schedule.weekly-day", "SUNDAY"));
        if (now.getDayOfWeek() == weeklyDay && !now.toLocalDate().equals(lastWeekly)) {
            createAndPublish("итоги недели", "weekly");
            lastWeekly = now.toLocalDate();
        }
    }

    private void maybeScheduleEventDriven() {
        if (!plugin.getConfig().getBoolean("auto-pr.schedule.event-driven", true)) return;

        int cooldownMin = plugin.getConfig().getInt("auto-pr.event-cooldown-minutes", 90);
        if (Duration.between(lastEventDrivenAt, Instant.now()).toMinutes() < cooldownMin) {
            return;
        }

        RuntimeFactCollector.FactSnapshot facts = factCollector.snapshot();
        if (facts.pvpKills() >= plugin.getConfig().getInt("auto-pr.event-thresholds.pvp-kills", 6)
                || facts.deaths() >= plugin.getConfig().getInt("auto-pr.event-thresholds.deaths", 15)) {
            createAndPublish("живые посты по событиям", "event");
            lastEventDrivenAt = Instant.now();
        }
    }

    private void maybeScheduleSmmPeriodic() {
        if (!plugin.getConfig().getBoolean("auto-pr.smm-group.enabled", true)) return;

        long hours = plugin.getConfig().getLong("auto-pr.smm-group.period-hours", 8L);
        if (Duration.between(lastSmmAt, Instant.now()).toHours() >= Math.max(1L, hours)) {
            createAndPublish("новости", "smm-periodic");
            lastSmmAt = Instant.now();
        }
    }

    private void createAndPublish(String rubric, String reason) {
        if (!rubricEnabled(rubric)) return;
        int ownerId = plugin.getConfig().getInt("auto-pr.vk.smm-group-owner-id", 0);
        if (ownerId == 0) {
            storage.markSkipped(rubric, "missing_smm_group_owner_id");
            return;
        }
        if (!publishInProgress.compareAndSet(false, true)) {
            return;
        }
        storage.setStateString("debug.last_stage", "auto-pr:compose");

        RuntimeFactCollector.FactSnapshot facts = factCollector.snapshot();
        String topicFingerprint = rubric + ":online=" + facts.online() + ":pvp=" + facts.pvpKills() + ":deaths=" + facts.deaths();
        String contextFingerprint = factsFingerprint(rubric, reason, facts);
        if (storage.hasTopicInLast24h(topicFingerprint)) {
            publishInProgress.set(false);
            return;
        }

        String prompt = buildPrompt(rubric, facts);
        storage.logPrompt(rubric, prompt);

        storage.setStateString("debug.last_stage", "auto-pr:openai_request");
        openAiClient.generatePostAsync(prompt)
                .thenComposeAsync(result -> {
                    storage.setStateString("debug.last_stage", "auto-pr:openai_response");
                    storage.logOpenAiResponse(rubric, reason, "ok", result.rawResponse(), result.text());
                    String text = result.text();
                    int minLen = plugin.getConfig().getInt("auto-pr.min-post-length", 60);
                    if (text.isBlank()) {
                        rejectPost(rubric, reason, text, "", "empty_openai_response", contextFingerprint);
                        return CompletableFuture.completedFuture(false);
                    }
                    if (text.length() < minLen) {
                        rejectPost(rubric, reason, text, "", "filtered_too_short", contextFingerprint);
                        return CompletableFuture.completedFuture(false);
                    }
                    Optional<String> duplicateReason = storage.duplicateReason(text, contextFingerprint);
                    if (duplicateReason.isPresent()) {
                        rejectPost(rubric, reason, text, "", "duplicate_text:" + duplicateReason.get(), contextFingerprint);
                        return CompletableFuture.completedFuture(false);
                    }
                    return maybeGenerateImage(rubric, text)
                            .thenCompose(img -> {
                                storage.setStateString("debug.last_stage", "auto-pr:vk_post");
                                return vkClient.postToWallAsync(ownerId, text)
                                    .thenApply(vk -> {
                                        if (vk.success()) {
                                            storage.savePost(rubric, reason, text, img, "published", "", contextFingerprint);
                                            storage.markStatus(rubric, "published", reason);
                                            storage.setStateString("debug.last_stage", "auto-pr:published");
                                            storage.addTopic(topicFingerprint);
                                            return true;
                                        }
                                        String err = "vk_publish_failed: " + vk.error();
                                        rejectPost(rubric, reason, text, img, err, contextFingerprint);
                                        return false;
                                    });
                            });
                }, ioPool)
                .exceptionally(ex -> {
                    plugin.getLogger().warning("AutoPR аварийный режим: публикация пропущена: " + ex.getMessage());
                    storage.setStateString("debug.last_stage", "auto-pr:exception:" + ex.getClass().getSimpleName());
                    storage.logOpenAiResponse(rubric, reason, "openai_failed", "", ex.getMessage());
                    rejectPost(rubric, reason, "", "", "openai_failed:" + ex.getMessage(), contextFingerprint);
                    return false;
                })
                .whenComplete((ok, ex) -> publishInProgress.set(false));
    }

    private void rejectPost(String rubric, String reason, String text, String imagePath, String rejectReason, String contextFingerprint) {
        storage.savePost(rubric, reason, text == null ? "" : text, imagePath, "rejected", rejectReason, contextFingerprint);
        storage.markStatus(rubric, "rejected", rejectReason);
        plugin.debug("reject " + rubric + "/" + reason + ": " + rejectReason);
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
                })
                .exceptionally(ex -> {
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
        int maxLen = plugin.getConfig().getInt("auto-pr.max-post-length", 700);

        return "Ты SMM-менеджер Minecraft-сервера. Язык только русский. Без канцелярита, без выдумок. "
                + "Сделай 1 пост рубрики '" + rubric + "'. Стиль: " + style + ". "
                + "Добавь CTA и в конце обязательно IP: " + serverIp + " и VK: " + vkLink + ". "
                + "Факты: online=" + facts.online() + ", peak=" + facts.peakOnline() + ", joins=" + facts.joins()
                + ", deaths=" + facts.deaths() + ", pvp=" + facts.pvpKills() + ". "
                + "События: " + facts.liveEvents() + ". Ивенты: " + facts.events() + ". Обновления: " + facts.changelog()
                + ". Карта/спавн: " + facts.mapInfo() + ". "
                + "Ограничение до " + maxLen + " символов. CTA: " + cta;
    }

    private String factsFingerprint(String rubric, String reason, RuntimeFactCollector.FactSnapshot facts) {
        return rubric
                + "|" + reason
                + "|online=" + facts.online()
                + "|peak=" + facts.peakOnline()
                + "|joins=" + facts.joins()
                + "|deaths=" + facts.deaths()
                + "|pvp=" + facts.pvpKills()
                + "|events=" + facts.liveEvents();
    }

    private LocalTime safeTime(String key, String fallback) {
        try {
            return LocalTime.parse(plugin.getConfig().getString(key, fallback));
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid time for " + key + ", fallback to " + fallback);
            return LocalTime.parse(fallback);
        }
    }

    private DayOfWeek safeDayOfWeek(String value) {
        try {
            return DayOfWeek.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid weekly-day value, fallback to SUNDAY");
            return DayOfWeek.SUNDAY;
        }
    }
}
