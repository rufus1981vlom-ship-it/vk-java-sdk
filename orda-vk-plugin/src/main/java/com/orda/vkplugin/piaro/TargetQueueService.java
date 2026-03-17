package com.orda.vkplugin.piaro;

import com.orda.vkplugin.PiarOrdaPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class TargetQueueService {
    public record Target(int groupId, String vkLink, boolean enabled) {}
    public record QueueItem(int outboxId, int campaignId, Target target, String text) {}

    private final PiarOrdaPlugin plugin;
    private final Storage storage;
    private final Deque<QueueItem> queue = new ArrayDeque<>();
    private List<Target> targets = new ArrayList<>();
    private Instant lastSendAt = Instant.EPOCH;

    public TargetQueueService(PiarOrdaPlugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
        reloadTargets();
    }

    public void reloadTargets() {
        File file = new File(plugin.getDataFolder(), "group.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<Target> loaded = new ArrayList<>();
        ConfigurationSection section = yaml.getConfigurationSection("groups");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection groupSection = section.getConfigurationSection(key);
                if (groupSection == null) continue;
                loaded.add(new Target(
                        groupSection.getInt("id"),
                        groupSection.getString("vk-link", ""),
                        groupSection.getBoolean("enabled", true)
                ));
            }
        }
        this.targets = loaded;
    }

    public List<Target> activeTargets() {
        int maxTargets = plugin.getConfig().getInt("piaro.max-targets-per-campaign", 20);
        List<Target> active = targets.stream().filter(Target::enabled).limit(maxTargets).toList();
        if (targets.size() > maxTargets) {
            plugin.getLogger().warning("Max targets limit reached: " + maxTargets);
        }
        return active;
    }

    public void fillQueue(int campaignId, List<String> variants) {
        queue.clear();
        List<Target> active = activeTargets();
        for (int i = 0; i < active.size(); i++) {
            Target target = active.get(i);
            String text = variants.get(i % variants.size());
            int outboxId = storage.enqueueOutbox(campaignId, target.groupId(), text);
            queue.add(new QueueItem(outboxId, campaignId, target, text));
        }
    }

    public QueueItem nextReady() {
        if (queue.isEmpty()) {
            return null;
        }
        int minDelay = plugin.getConfig().getInt("piaro.target-delay-min-minutes", 10);
        int maxDelay = plugin.getConfig().getInt("piaro.target-delay-max-minutes", 20);
        long required = ThreadLocalRandom.current().nextLong(minDelay, maxDelay + 1);
        if (Duration.between(lastSendAt, Instant.now()).toMinutes() < required) {
            return null;
        }
        lastSendAt = Instant.now();
        return queue.poll();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
