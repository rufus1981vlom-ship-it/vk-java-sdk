package com.orda.vkplugin.piaro;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.orda.vkplugin.PiarOrdaPlugin;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

public class VkClient {
    private final PiarOrdaPlugin plugin;
    private final Logger logger;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final Gson gson = new Gson();

    public VkClient(PiarOrdaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public CompletableFuture<Boolean> postToWallAsync(int ownerId, String message) {
        String token = plugin.getConfig().getString("vk-account.token", "");
        String apiVersion = plugin.getConfig().getString("vk-account.api-version", "5.199");

        if (token.isBlank() || ownerId == 0) {
            return CompletableFuture.failedFuture(new IllegalStateException("VK token/owner-id not configured"));
        }

        int maxLen = plugin.getConfig().getInt("vk-account.max-message-length", 3900);
        String payload = message.length() > maxLen ? message.substring(0, maxLen - 1) + "…" : message;

        String body = "owner_id=" + enc(ownerId)
                + "&from_group=0"
                + "&message=" + enc(payload)
                + "&random_id=" + enc(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE))
                + "&access_token=" + enc(token)
                + "&v=" + enc(apiVersion);

        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.vk.com/method/wall.post"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 400) {
                        logger.warning("VK HTTP error " + response.statusCode() + ": " + response.body());
                        return false;
                    }
                    return isSuccess(response.body());
                })
                .exceptionally(ex -> {
                    logger.warning("VK publish failed: " + ex.getMessage());
                    return false;
                });
    }

    private boolean isSuccess(String body) {
        JsonObject object = gson.fromJson(body, JsonObject.class);
        if (object != null && object.has("response")) {
            return true;
        }
        logger.warning("VK wall.post error response: " + body);
        return false;
    }

    private String enc(Object value) {
        return URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8);
    }
}
