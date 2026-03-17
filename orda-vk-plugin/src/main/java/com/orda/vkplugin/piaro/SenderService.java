package com.orda.vkplugin.piaro;

import com.orda.vkplugin.PiarOrdaPlugin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;

public class SenderService {
    private final PiarOrdaPlugin plugin;
    private final Storage storage;
    private final TargetQueueService queueService;
    private final VkClient vkClient;
    private Instant cooldownUntil = Instant.EPOCH;

    public SenderService(PiarOrdaPlugin plugin, Storage storage, TargetQueueService queueService, VkClient vkClient) {
        this.plugin = plugin;
        this.storage = storage;
        this.queueService = queueService;
        this.vkClient = vkClient;
    }

    public void tickSend() {
        if (Instant.now().isBefore(cooldownUntil)) {
            return;
        }

        TargetQueueService.QueueItem item = queueService.nextReady();
        if (item == null) {
            return;
        }

        String hash = sha256(item.text());
        if (storage.sentInLast24h(item.target().groupId(), hash)) {
            storage.updateOutboxStatus(item.outboxId(), "skipped");
            plugin.getLogger().info("Outbox " + item.outboxId() + " status=skipped deduplicate-24h");
            return;
        }

        if (plugin.getConfig().getBoolean("piaro.manual-approval", true)) {
            storage.updateOutboxStatus(item.outboxId(), "skipped");
            plugin.getLogger().info("Outbox " + item.outboxId() + " status=skipped manual-approval");
            return;
        }

        boolean sent = vkClient.postToGroup(item.target().groupId(), item.text());
        if (sent) {
            storage.updateOutboxStatus(item.outboxId(), "sent");
            storage.addSendHistory(item.target().groupId(), hash);
            int cooldownMin = plugin.getConfig().getInt("piaro.cooldown-minutes", 5);
            cooldownUntil = Instant.now().plus(Duration.ofMinutes(cooldownMin));
            plugin.getLogger().info("Outbox " + item.outboxId() + " status=sent");
        } else {
            storage.updateOutboxStatus(item.outboxId(), "failed");
            plugin.getLogger().warning("Outbox " + item.outboxId() + " status=failed");
        }
    }

    private String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }
}
