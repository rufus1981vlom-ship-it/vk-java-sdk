package com.orda.vkplugin.piaro;

import com.orda.vkplugin.PiarOrdaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class CampaignScheduler {
    private final PiarOrdaPlugin plugin;
    private final Storage storage;
    private final TargetQueueService queueService;
    private final SenderService senderService;
    private final PromoComposer composer;

    private BukkitTask campaignTask;
    private BukkitTask senderTask;
    private Integer activeCampaign;
    private Instant nextCampaignAt = Instant.EPOCH;

    public CampaignScheduler(PiarOrdaPlugin plugin, Storage storage, TargetQueueService queueService, SenderService senderService, PromoComposer composer) {
        this.plugin = plugin;
        this.storage = storage;
        this.queueService = queueService;
        this.senderService = senderService;
        this.composer = composer;
    }

    public void start() {
        if (campaignTask != null) {
            return;
        }
        campaignTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tickCampaign, 20L, 20L * 60L);
        senderTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, senderService::tickSend, 20L, 20L * 30L);
    }

    public void stop() {
        if (campaignTask != null) {
            campaignTask.cancel();
            campaignTask = null;
        }
        if (senderTask != null) {
            senderTask.cancel();
            senderTask = null;
        }
    }

    private void tickCampaign() {
        if (Instant.now().isBefore(nextCampaignAt)) {
            return;
        }
        if (activeCampaign != null && !queueService.isEmpty()) {
            return;
        }
        if (activeCampaign != null) {
            storage.cleanCampaignData(activeCampaign);
            activeCampaign = null;
        }

        activeCampaign = storage.createCampaign();
        List<String> variants = generateAiVariants();
        storage.saveVariants(activeCampaign, variants);

        List<TargetQueueService.Target> targets = queueService.activeTargets();
        List<String> composed = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            String base = variants.get(i % variants.size());
            composed.add(composer.compose(base, targets.get(i).vkLink()));
        }
        queueService.fillQueue(activeCampaign, composed);

        int min = plugin.getConfig().getInt("piaro.campaign-minutes-min", 60);
        int max = plugin.getConfig().getInt("piaro.campaign-minutes-max", 120);
        long nextMinutes = ThreadLocalRandom.current().nextLong(min, max + 1L);
        nextCampaignAt = Instant.now().plusSeconds(nextMinutes * 60);
        plugin.getLogger().info("PiarOrda campaign=" + activeCampaign + " variants=" + variants.size() + " next in " + nextMinutes + "m");
    }

    private List<String> generateAiVariants() {
        int count = ThreadLocalRandom.current().nextInt(3, 6);
        List<String> variants = new ArrayList<>();
        String[] themes = {
                "Новый сезон на сервере, ежедневные ивенты и бонусы для новичков!",
                "Собери команду и покори кланы: PvP, рейды и щедрые награды.",
                "Уютный survival с экономикой, данжами и активной администрацией.",
                "Прокачай старт за вечер: киты, квесты и быстрый прогресс.",
                "Ламповое комьюнити и постоянные обновления — заходи сегодня."
        };
        for (int i = 0; i < count; i++) {
            variants.add(themes[ThreadLocalRandom.current().nextInt(themes.length)]);
        }
        return variants;
    }
}
