package ordacraft.vk.vk.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ordacraft.vk.governance.GovernanceService;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

public class VkApiClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Gson gson = new Gson();
    private final String token;
    private final String version;

    public VkApiClient(String token, String version) {
        this.token = token;
        this.version = version;
    }

    public JsonObject call(String method, Map<String, String> params) throws IOException, InterruptedException {
        StringJoiner q = new StringJoiner("&");
        for (var e : params.entrySet()) q.add(enc(e.getKey()) + "=" + enc(e.getValue()));
        q.add("access_token=" + enc(token));
        q.add("v=" + enc(version));
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("https://api.vk.com/method/" + method + "?" + q)).GET().timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
        JsonObject obj = gson.fromJson(res.body(), JsonObject.class);
        if (obj != null && obj.has("error")) throw new IllegalStateException(obj.get("error").toString());
        return obj;
    }

    public JsonObject callRaw(String absoluteUrl) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(absoluteUrl)).GET().timeout(Duration.ofSeconds(35)).build(), HttpResponse.BodyHandlers.ofString());
        return gson.fromJson(res.body(), JsonObject.class);
    }

    public void send(long peerId, String message) throws IOException, InterruptedException {
        call("messages.send", Map.of("peer_id", String.valueOf(peerId), "message", message, "random_id", String.valueOf(System.nanoTime())));
    }

    public GovernanceService.RemoveStatus removeChatUserDetailed(long chatId, long memberId) {
        try {
            call("messages.removeChatUser", Map.of("chat_id", String.valueOf(chatId), "member_id", String.valueOf(memberId)));
            return GovernanceService.RemoveStatus.REMOVED;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("925") || msg.contains("user not found")) {
                return GovernanceService.RemoveStatus.NOT_FOUND;
            }
            if (msg.contains("15") || msg.contains("917") || msg.contains("not admin")) {
                return GovernanceService.RemoveStatus.NO_PERMISSIONS;
            }
            return GovernanceService.RemoveStatus.API_ERROR;
        }
    }

    public boolean removeChatUser(long chatId, long memberId) {
        return removeChatUserDetailed(chatId, memberId) == GovernanceService.RemoveStatus.REMOVED;
    }

    public JsonObject extractResponse(JsonObject vkResponse) {
        JsonElement response = vkResponse == null ? null : vkResponse.get("response");
        return response != null && response.isJsonObject() ? response.getAsJsonObject() : null;
    }

    private String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
