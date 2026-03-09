package ordacraft.vk.support;

import ordacraft.vk.storage.YamlFileStore;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SupportTicketService {
    private final YamlFileStore store;
    private final Map<Integer, SupportTicket> tickets = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldownMap = new ConcurrentHashMap<>();
    private int sequence = 1;

    public SupportTicketService(YamlFileStore store){ this.store = store; }

    @SuppressWarnings("unchecked")
    public void load(){
        tickets.clear();
        Map<String,Object> root = store.load();
        Object rs = root.get("sequence");
        if(rs instanceof Number n) sequence = n.intValue();
        Object raw = root.get("tickets");
        if(!(raw instanceof Map<?,?> m)) return;
        for(var e:m.entrySet()){
            try{
                Map<String,Object> v=(Map<String,Object>)e.getValue();
                int id=Integer.parseInt(e.getKey().toString());
                tickets.put(id, new SupportTicket(
                        id,
                        TicketType.valueOf(String.valueOf(v.get("type"))),
                        UUID.fromString(String.valueOf(v.get("playerUuid"))),
                        String.valueOf(v.get("playerName")),
                        String.valueOf(v.getOrDefault("targetPlayerName", "")),
                        String.valueOf(v.get("text")),
                        ((Number) v.getOrDefault("createdAt", 0)).longValue(),
                        TicketStatus.valueOf(String.valueOf(v.getOrDefault("status", "OPEN"))),
                        v.get("responderVkId") == null ? null : ((Number) v.get("responderVkId")).longValue(),
                        Boolean.parseBoolean(String.valueOf(v.getOrDefault("closed", false)))
                ));
            }catch(Exception ignored){}
        }
    }

    public synchronized SupportTicket create(TicketType type, UUID uuid, String name, String target, String text){
        int id = sequence++;
        SupportTicket t = new SupportTicket(id, type, uuid, name, target, text, Instant.now().toEpochMilli(), TicketStatus.OPEN, null, false);
        tickets.put(id,t); return t;
    }

    public List<SupportTicket> openTickets(){ return tickets.values().stream().filter(t->t.status()!=TicketStatus.CLOSED).sorted(Comparator.comparingInt(SupportTicket::id)).toList(); }
    public Optional<SupportTicket> find(int id){ return Optional.ofNullable(tickets.get(id)); }

    public void markAnswered(int id, long responder, boolean close){
        SupportTicket t = tickets.get(id); if(t==null) return;
        tickets.put(id, new SupportTicket(t.id(), t.type(), t.playerUuid(), t.playerName(), t.targetPlayerName(), t.text(), t.createdAt(), close?TicketStatus.CLOSED:TicketStatus.ANSWERED, responder, close));
    }

    public void close(int id){
        SupportTicket t = tickets.get(id); if(t==null) return;
        tickets.put(id, new SupportTicket(t.id(), t.type(), t.playerUuid(), t.playerName(), t.targetPlayerName(), t.text(), t.createdAt(), TicketStatus.CLOSED, t.responderVkId(), true));
    }

    public boolean isCooldown(UUID uuid, int seconds){
        long last = cooldownMap.getOrDefault(uuid, 0L);
        return (Instant.now().toEpochMilli()-last) < seconds*1000L;
    }
    public void touch(UUID uuid){ cooldownMap.put(uuid, Instant.now().toEpochMilli()); }

    public long openBy(UUID uuid){ return tickets.values().stream().filter(t->t.playerUuid().equals(uuid)&&t.status()!=TicketStatus.CLOSED).count(); }

    public void save(){
        Map<String,Object> root = new LinkedHashMap<>(); root.put("sequence", sequence);
        Map<String,Object> out = new LinkedHashMap<>();
        for(var t:tickets.values()){
            Map<String,Object> v = new LinkedHashMap<>();
            v.put("type",t.type().name()); v.put("playerUuid",t.playerUuid().toString()); v.put("playerName",t.playerName()); v.put("targetPlayerName",t.targetPlayerName()); v.put("text",t.text());
            v.put("createdAt",t.createdAt()); v.put("status",t.status().name()); v.put("responderVkId",t.responderVkId()); v.put("closed",t.closed());
            out.put(String.valueOf(t.id()), v);
        }
        root.put("tickets", out); store.save(root);
    }
}
