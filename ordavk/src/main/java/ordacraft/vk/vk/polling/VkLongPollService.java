package ordacraft.vk.vk.polling;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ordacraft.vk.vk.api.VkApiClient;
import ordacraft.vk.vk.model.VkIncomingMessage;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class VkLongPollService {
    private final VkApiClient api;
    private final int groupId;
    private final int waitSeconds;
    private final Consumer<VkIncomingMessage> consumer;

    private volatile boolean running;
    private ExecutorService executor;

    public VkLongPollService(VkApiClient api, int groupId, int waitSeconds, Consumer<VkIncomingMessage> consumer) {
        this.api = api;
        this.groupId = groupId;
        this.waitSeconds = waitSeconds;
        this.consumer = consumer;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ordavk-longpoll");
            t.setDaemon(true);
            return t;
        });
        executor.submit(this::loop);
    }

    public synchronized void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void simulateIncoming(VkIncomingMessage message) {
        consumer.accept(message);
    }

    private void loop() {
        String server = null;
        String key = null;
        String ts = null;

        while (running) {
            try {
                if (server == null || key == null || ts == null) {
                    JsonObject lp = api.extractResponse(api.call("groups.getLongPollServer", Map.of("group_id", String.valueOf(groupId))));
                    if (lp == null) {
                        sleepBriefly();
                        continue;
                    }
                    server = lp.get("server").getAsString();
                    key = lp.get("key").getAsString();
                    ts = lp.get("ts").getAsString();
                }

                String url = server + "?act=a_check&key=" + key + "&ts=" + ts + "&wait=" + waitSeconds + "&mode=2&version=3";
                JsonObject poll = api.callRaw(url);
                if (poll == null) {
                    sleepBriefly();
                    continue;
                }

                if (poll.has("failed")) {
                    int failed = poll.get("failed").getAsInt();
                    if (failed == 1 && poll.has("ts")) {
                        ts = poll.get("ts").getAsString();
                    } else {
                        server = null;
                        key = null;
                        ts = null;
                    }
                    continue;
                }

                if (poll.has("ts")) ts = poll.get("ts").getAsString();
                JsonArray updates = poll.has("updates") ? poll.getAsJsonArray("updates") : null;
                if (updates == null) continue;

                for (int i = 0; i < updates.size(); i++) {
                    JsonObject upd = updates.get(i).getAsJsonObject();
                    if (!upd.has("type") || !"message_new".equals(upd.get("type").getAsString())) continue;
                    JsonObject object = upd.getAsJsonObject("object");
                    if (object == null || !object.has("message")) continue;
                    JsonObject m = object.getAsJsonObject("message");
                    if (m == null) continue;
                    long peerId = m.has("peer_id") ? m.get("peer_id").getAsLong() : 0L;
                    long fromId = m.has("from_id") ? m.get("from_id").getAsLong() : 0L;
                    String text = m.has("text") ? m.get("text").getAsString() : "";
                    consumer.accept(new VkIncomingMessage(peerId, fromId, text));
                }
            } catch (Exception ignored) {
                sleepBriefly();
            }
        }
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
