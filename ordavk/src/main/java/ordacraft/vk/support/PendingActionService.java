package ordacraft.vk.support;

import ordacraft.vk.storage.YamlFileStore;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PendingActionService {
    private final YamlFileStore store;
    private final Map<Integer, PendingAction> actions = new ConcurrentHashMap<>();
    private int sequence = 1;

    public PendingActionService(YamlFileStore store) {
        this.store = store;
    }

    @SuppressWarnings("unchecked")
    public synchronized void load() {
        actions.clear();
        Map<String, Object> root = store.load();
        Object seq = root.get("sequence");
        if (seq instanceof Number n) sequence = n.intValue();

        Object raw = root.get("actions");
        if (!(raw instanceof Map<?, ?> map)) return;

        for (var e : map.entrySet()) {
            try {
                int id = Integer.parseInt(String.valueOf(e.getKey()));
                Map<String, Object> v = (Map<String, Object>) e.getValue();
                PendingAction action = new PendingAction(
                        id,
                        UUID.fromString(String.valueOf(v.get("playerUuid"))),
                        String.valueOf(v.getOrDefault("playerNick", "")),
                        String.valueOf(v.getOrDefault("actionType", "")),
                        String.valueOf(v.getOrDefault("command", "")),
                        ((Number) v.getOrDefault("issuedByVkId", 0)).longValue(),
                        String.valueOf(v.getOrDefault("issuedByRole", "")),
                        ((Number) v.getOrDefault("createdAt", 0)).longValue(),
                        String.valueOf(v.getOrDefault("status", "pending")),
                        ((Number) v.getOrDefault("attempts", 0)).intValue(),
                        v.get("appliedAt") == null ? null : ((Number) v.get("appliedAt")).longValue(),
                        v.get("errorMessage") == null ? null : String.valueOf(v.get("errorMessage"))
                );
                actions.put(id, action);
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized PendingAction enqueue(UUID playerUuid, String playerNick, String actionType, String command,
                                              long issuedByVkId, String issuedByRole) {
        int id = sequence++;
        PendingAction action = new PendingAction(id, playerUuid, playerNick, actionType, command,
                issuedByVkId, issuedByRole, Instant.now().toEpochMilli(), "pending", 0, null, null);
        actions.put(id, action);
        return action;
    }

    public synchronized List<PendingAction> pendingFor(UUID uuid) {
        return actions.values().stream()
                .filter(a -> a.playerUuid().equals(uuid))
                .filter(a -> "pending".equalsIgnoreCase(a.status()))
                .sorted(Comparator.comparingLong(PendingAction::createdAt).thenComparingInt(PendingAction::id))
                .toList();
    }

    public synchronized void markApplied(int id) {
        actions.remove(id);
    }

    public synchronized void markFailed(int id, String error) {
        PendingAction a = actions.get(id);
        if (a == null) return;
        actions.put(id, new PendingAction(a.id(), a.playerUuid(), a.playerNick(), a.actionType(), a.command(),
                a.issuedByVkId(), a.issuedByRole(), a.createdAt(), "pending", a.attempts() + 1, null, error));
    }

    public synchronized void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("sequence", sequence);
        Map<String, Object> out = new LinkedHashMap<>();
        for (PendingAction a : actions.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("playerUuid", a.playerUuid().toString());
            row.put("playerNick", a.playerNick());
            row.put("actionType", a.actionType());
            row.put("command", a.command());
            row.put("issuedByVkId", a.issuedByVkId());
            row.put("issuedByRole", a.issuedByRole());
            row.put("createdAt", a.createdAt());
            row.put("status", a.status());
            row.put("attempts", a.attempts());
            row.put("appliedAt", a.appliedAt());
            row.put("errorMessage", a.errorMessage());
            out.put(String.valueOf(a.id()), row);
        }
        root.put("actions", out);
        store.save(root);
    }
}
