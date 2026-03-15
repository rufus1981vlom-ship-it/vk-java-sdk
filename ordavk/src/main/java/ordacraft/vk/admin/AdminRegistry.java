package ordacraft.vk.admin;

import ordacraft.vk.storage.YamlFileStore;

import java.time.Instant;
import java.util.*;

public class AdminRegistry {
    private final YamlFileStore store;
    private final Map<Long, AdminRecord> admins = new HashMap<>();

    public AdminRegistry(YamlFileStore store) { this.store = store; }

    @SuppressWarnings("unchecked")
    public void load() {
        admins.clear();
        Map<String, Object> root = store.load();
        Object raw = root.get("admins");
        if (!(raw instanceof Map<?, ?> map)) return;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            try {
                long vk = Long.parseLong(e.getKey().toString());
                Map<String, Object> v = (Map<String, Object>) e.getValue();
                admins.put(vk, new AdminRecord(vk, String.valueOf(v.getOrDefault("mcNick", "")),
                        Role.fromString(String.valueOf(v.getOrDefault("role", "HELPER"))),
                        ((Number) v.getOrDefault("createdAt", 0)).longValue(),
                        ((Number) v.getOrDefault("updatedAt", 0)).longValue()));
            } catch (Exception ignored) {}
        }
    }

    public void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        for (AdminRecord a : admins.values()) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("mcNick", a.mcNick()); v.put("role", a.role().name()); v.put("createdAt", a.createdAt()); v.put("updatedAt", a.updatedAt());
            out.put(String.valueOf(a.vkId()), v);
        }
        root.put("admins", out);
        store.save(root);
    }

    public Optional<AdminRecord> find(long vkId) { return Optional.ofNullable(admins.get(vkId)); }
    public Collection<AdminRecord> all(){ return admins.values(); }

    public void upsert(long vkId, String mcNick, Role role) {
        long now = Instant.now().toEpochMilli();
        long created = admins.containsKey(vkId) ? admins.get(vkId).createdAt() : now;
        admins.put(vkId, new AdminRecord(vkId, mcNick, role, created, now));
    }

    public void remove(long vkId){ admins.remove(vkId); }
}
