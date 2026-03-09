package ordacraft.vk.service;

import ordacraft.vk.storage.YamlFileStore;

import java.time.Instant;
import java.util.*;

public class AuditService {
    private final YamlFileStore store;
    private final List<Map<String,Object>> entries = new ArrayList<>();
    public AuditService(YamlFileStore store){ this.store = store; }
    public synchronized void log(String action, String details){
        Map<String,Object> e = new LinkedHashMap<>();
        e.put("time", Instant.now().toEpochMilli()); e.put("action", action); e.put("details", details);
        entries.add(e);
    }
    public synchronized void save(){ Map<String,Object> root = new LinkedHashMap<>(); root.put("entries", entries); store.save(root); }
}
