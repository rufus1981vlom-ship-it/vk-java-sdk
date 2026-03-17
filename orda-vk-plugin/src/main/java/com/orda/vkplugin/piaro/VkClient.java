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
import java.util.logging.Logger;

public class VkClient {
    private final PiarOrdaPlugin plugin;
    private final Logger logger;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Gson gson = new Gson();

    private String userToken;

    public VkClient(PiarOrdaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public boolean ensureAuthorized() {
        if (userToken != null && !userToken.isBlank()) {
            return true;
        }

        String login = plugin.getConfig().getString("vk-profile.login", "");
        String password = plugin.getConfig().getString("vk-profile.password", "");
        String clientId = plugin.getConfig().getString("vk-profile.app-id", "");
        String clientSecret = plugin.getConfig().getString("vk-profile.app-secret", "");

        if (login.isBlank() || password.isBlank() || clientId.isBlank() || clientSecret.isBlank()) {
            logger.warning("VK profile credentials are not fully configured");
            return false;
        }

        String body = "grant_type=password"
                + "&client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret)
                + "&username=" + enc(login)
                + "&password=" + enc(password)
                + "&scope=" + enc("wall,groups,offline")
                + "&v=" + enc(plugin.getConfig().getString("piaro.vk-api-version", "5.199"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://oauth.vk.com/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(20))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject object = gson.fromJson(response.body(), JsonObject.class);
            if (object.has("access_token")) {
                userToken = object.get("access_token").getAsString();
                return true;
            }
            logger.warning("VK OAuth failed: " + response.body());
        } catch (Exception e) {
            logger.warning("VK OAuth error: " + e.getMessage());
        }
        return false;
    }

    public boolean postToGroup(int groupId, String text) {
        if (!ensureAuthorized()) return false;
        String version = plugin.getConfig().getString("piaro.vk-api-version", "5.199");

        String body = "owner_id=" + enc("-" + groupId)
                + "&from_group=0"
                + "&message=" + enc(text)
                + "&access_token=" + enc(userToken)
                + "&v=" + enc(version);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.vk.com/method/wall.post"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(20))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject object = gson.fromJson(response.body(), JsonObject.class);
            if (object.has("response")) {
                return true;
            }
            logger.warning("VK wall.post failed: " + response.body());
        } catch (Exception e) {
            logger.warning("VK wall.post error: " + e.getMessage());
        }
        return false;
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
