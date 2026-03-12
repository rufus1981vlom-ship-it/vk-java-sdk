package ordacraft.vk.support;

import ordacraft.vk.storage.YamlFileStore;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PendingReplyService {
    private final YamlFileStore store;
    private final Map<UUID, PendingReply> pending = new ConcurrentHashMap<>();

    public PendingReplyService(YamlFileStore store){ this.store = store; }

    @SuppressWarnings("unchecked")
    public void load(){
        pending.clear();
        Map<String,Object> root = store.load();
        Object raw = root.get("pending"); if(!(raw instanceof Map<?,?> m)) return;
        for(var e:m.entrySet()){
            try{
                Map<String,Object> v=(Map<String,Object>)e.getValue();
                UUID uuid=UUID.fromString(e.getKey().toString());
                pending.put(uuid,new PendingReply(uuid, String.valueOf(v.get("playerName")), ((Number)v.get("ticketId")).intValue(), String.valueOf(v.get("replyText")), ((Number)v.get("responderVkId")).longValue(), String.valueOf(v.get("responderName")), ((Number)v.get("createdAt")).longValue()));
            }catch(Exception ignored){}
        }
    }

    public Optional<PendingReply> get(UUID uuid){ return Optional.ofNullable(pending.get(uuid)); }
    public Optional<PendingReply> take(UUID uuid){ return Optional.ofNullable(pending.remove(uuid)); }
    public void remove(UUID uuid){ pending.remove(uuid); }
    public void put(PendingReply r){ pending.put(r.playerUuid(), r); }

    public int size(){ return pending.size(); }

    public void save(){
        Map<String,Object> root=new LinkedHashMap<>(); Map<String,Object> out=new LinkedHashMap<>();
        for(var r: pending.values()){
            Map<String,Object> v=new LinkedHashMap<>();
            v.put("playerName",r.playerName()); v.put("ticketId",r.ticketId()); v.put("replyText",r.replyText()); v.put("responderVkId",r.responderVkId()); v.put("responderName",r.responderName()); v.put("createdAt",r.createdAt());
            out.put(r.playerUuid().toString(), v);
        }
        root.put("pending", out); store.save(root);
    }
}
