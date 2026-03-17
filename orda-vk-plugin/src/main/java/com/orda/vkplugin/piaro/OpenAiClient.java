package com.orda.vkplugin.piaro;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.orda.vkplugin.PiarOrdaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class OpenAiClient {
    private final PiarOrdaPlugin plugin;
    private final Logger logger;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final Gson gson = new Gson();

    public OpenAiClient(PiarOrdaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public CompletableFuture<String> generatePostAsync(String prompt) {
        String key = apiKey();
        if (key.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("OpenAI key is empty"));
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", plugin.getConfig().getString("auto-pr.openai.responses-model", "gpt-5-mini"));
        JsonArray input = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject inputText = new JsonObject();
        inputText.addProperty("type", "input_text");
        inputText.addProperty("text", prompt);
        content.add(inputText);
        message.add("content", content);
        input.add(message);
        body.add("input", input);

        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/responses"))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 400) {
                        throw new IllegalStateException("OpenAI Responses HTTP " + response.statusCode() + ": " + response.body());
                    }
                    return response.body();
                })
                .thenApply(this::extractText);
    }

    public CompletableFuture<byte[]> generateImageAsync(String prompt) {
        String key = apiKey();
        if (key.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("OpenAI key is empty"));
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", plugin.getConfig().getString("auto-pr.openai.images-model", "gpt-image-1"));
        body.addProperty("prompt", prompt);
        body.addProperty("size", plugin.getConfig().getString("auto-pr.image-size", "1024x1024"));

        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/images/generations"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 400) {
                        throw new IllegalStateException("OpenAI Images HTTP " + response.statusCode() + ": " + response.body());
                    }
                    return response.body();
                })
                .thenApply(this::extractImageBytes);
    }

    private String apiKey() {
        String env = System.getenv("OPENAI_API_KEY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return plugin.getConfig().getString("auto-pr.openai.api-key", "");
    }

    private String extractText(String json) {
        JsonObject o = gson.fromJson(json, JsonObject.class);
        if (o == null) return "";

        if (o.has("error")) {
            throw new IllegalStateException("OpenAI Responses error: " + o.get("error"));
        }
        if (o.has("output_text")) return o.get("output_text").getAsString();

        JsonArray output = o.getAsJsonArray("output");
        if (output == null) return "";
        for (JsonElement e : output) {
            JsonObject obj = e.getAsJsonObject();
            JsonArray content = obj.getAsJsonArray("content");
            if (content == null) continue;
            for (JsonElement c : content) {
                JsonObject cc = c.getAsJsonObject();
                if (cc.has("text")) return cc.get("text").getAsString();
            }
        }
        logger.warning("Cannot parse Responses output: " + json);
        return "";
    }

    private byte[] extractImageBytes(String json) {
        JsonObject o = gson.fromJson(json, JsonObject.class);
        if (o == null) return new byte[0];
        if (o.has("error")) {
            throw new IllegalStateException("OpenAI Images error: " + o.get("error"));
        }
        JsonArray data = o.getAsJsonArray("data");
        if (data == null || data.isEmpty()) return new byte[0];
        JsonObject first = data.get(0).getAsJsonObject();
        if (!first.has("b64_json")) return new byte[0];
        return Base64.getDecoder().decode(first.get("b64_json").getAsString().getBytes(StandardCharsets.UTF_8));
    }
}
