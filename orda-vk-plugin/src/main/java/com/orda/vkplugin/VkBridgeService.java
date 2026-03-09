package com.orda.vkplugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VkBridgeService {
    private static final int PEER_CHAT_BASE = 2_000_000_000;
    private static final Pattern VK_MENTION_PATTERN = Pattern.compile("@(?:(id)(\\d+)|([a-zA-Z0-9_.]+))(?:\\(.*\\))?");
    private static final Pattern DAYS_PATTERN = Pattern.compile("(\\d+)d", Pattern.CASE_INSENSITIVE);

    private final OrdaVkPlugin plugin;
    private final Gson gson;
    private final HttpClient httpClient;

    private ScheduledExecutorService executor;
    private volatile boolean running;

    private String apiVersion;
    private String accessToken;
    private int groupId;
    private int commandChatId;
    private int eventChatId;

    private Map<Long, AdminData> admins;
    private Map<Long, String> nicknames;
    private Map<Long, ChatBan> chatBans;

    private CommandPolicy commandPolicy;
    private BotAccessPolicy botAccessPolicy;

    private String lpServer;
    private String lpKey;
    private String lpTs;

    public VkBridgeService(OrdaVkPlugin plugin, Gson gson) {
        this.plugin = plugin;
        this.gson = gson;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public void start() {
        reloadState();
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::pollLoop, 1, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        running = false;
        if (executor != null) executor.shutdownNow();
    }

    public void notifyEvent(String message) {
        int peerId = toPeerId(eventChatId);
        if (peerId > 0) sendMessage(peerId, message);
    }

    private void reloadState() {
        plugin.reloadConfig();
        apiVersion = plugin.getConfig().getString("vk.api-version", "5.199");
        accessToken = plugin.getConfig().getString("vk.token", "");
        groupId = plugin.getConfig().getInt("vk.group-id", 0);
        commandChatId = plugin.getConfig().getInt("vk.command-chat-id", 0);
        eventChatId = plugin.getConfig().getInt("vk.event-chat-id", 0);

        loadAdmins();
        loadNicknames();
        loadChatBans();
        commandPolicy = loadCommandPolicy();
        botAccessPolicy = loadBotAccessPolicy();
    }

    private void loadAdmins() {
        admins = new HashMap<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("vk.admins");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                long id = Long.parseLong(key);
                String base = "vk.admins." + key;
                admins.put(id, new AdminData(
                        clampLevel(plugin.getConfig().getInt(base + ".level", 1)),
                        plugin.getConfig().getString(base + ".nickname", "").trim()
                ));
            } catch (NumberFormatException ignored) {}
        }
    }

    private void loadNicknames() {
        nicknames = new HashMap<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("vk.nicknames");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                long id = Long.parseLong(key);
                String n = plugin.getConfig().getString("vk.nicknames." + key, "").trim();
                if (!n.isEmpty()) nicknames.put(id, n);
            } catch (NumberFormatException ignored) {}
        }
    }

    private void loadChatBans() {
        chatBans = new HashMap<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("vk.chat-bans");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                long id = Long.parseLong(key);
                String base = "vk.chat-bans." + key;
                chatBans.put(id, new ChatBan(
                        plugin.getConfig().getLong(base + ".until-epoch-ms", 0),
                        plugin.getConfig().getString(base + ".reason", "")
                ));
            } catch (NumberFormatException ignored) {}
        }
    }

    private CommandPolicy loadCommandPolicy() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("vk.command-policy");
        if (section == null) return CommandPolicy.defaultPolicy();
        return new CommandPolicy(
                section.getBoolean("enabled", true),
                section.getString("mode", "blacklist").toLowerCase(Locale.ROOT),
                normalizeSet(section.getStringList("blacklist")),
                normalizeSet(section.getStringList("whitelist"))
        );
    }

    private BotAccessPolicy loadBotAccessPolicy() {
        Map<String, Integer> defaults = new HashMap<>();
        defaults.put("admins", 2); defaults.put("admin_set", 3); defaults.put("admin_level", 3); defaults.put("admin_remove", 3);
        defaults.put("rnick", 2); defaults.put("chat_kick", 2); defaults.put("chat_ban", 3); defaults.put("chat_unban", 3);
        defaults.put("noname", 2); defaults.put("cmd", 1);
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("vk.bot-command-access");
        if (section != null) for (String k : defaults.keySet()) defaults.put(k, clampLevel(section.getInt(k, defaults.get(k))));
        return new BotAccessPolicy(defaults);
    }

    private Set<String> normalizeSet(List<String> values) {
        Set<String> out = new HashSet<>();
        for (String v : values) if (v != null && !v.trim().isEmpty()) out.add(v.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    private void pollLoop() {
        if (!running || accessToken.isEmpty() || groupId <= 0) return;
        try {
            ensureLongPollServer();
            String url = lpServer + "?act=a_check&key=" + encode(lpKey) + "&ts=" + encode(lpTs) + "&wait=25";
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(35)).GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonObject body = gson.fromJson(response.body(), JsonObject.class);
            if (body == null) return;
            if (body.has("failed")) { lpServer = null; lpKey = null; lpTs = null; return; }
            lpTs = body.get("ts").getAsString();
            JsonArray updates = body.getAsJsonArray("updates");
            if (updates == null) return;
            for (JsonElement update : updates) onUpdate(update.getAsJsonObject());
        } catch (Exception e) {
            plugin.getLogger().warning("VK long poll error: " + e.getMessage());
        }
    }

    private void ensureLongPollServer() throws IOException, InterruptedException {
        if (lpServer != null && lpKey != null && lpTs != null) return;
        JsonObject response = callVkMethod("groups.getLongPollServer", Map.of("group_id", String.valueOf(groupId)));
        JsonObject obj = response.getAsJsonObject("response");
        if (obj == null) throw new IllegalStateException("groups.getLongPollServer failed");
        lpServer = obj.get("server").getAsString();
        lpKey = obj.get("key").getAsString();
        lpTs = obj.get("ts").getAsString();
    }

    private void onUpdate(JsonObject update) {
        if (!"message_new".equals(update.has("type") ? update.get("type").getAsString() : "")) return;
        JsonObject message = update.getAsJsonObject("object").getAsJsonObject("message");
        if (message == null) return;
        int peerId = message.get("peer_id").getAsInt();
        if (peerId != toPeerId(commandChatId)) return;
        processInviteAction(message, peerId);

        String text = message.has("text") ? message.get("text").getAsString().trim() : "";
        if (text.isEmpty()) return;

        AdminData actor = admins.get(message.get("from_id").getAsLong());
        if (actor == null) { sendMessage(peerId, "У вас нет уровня администратора в Orda VK."); return; }
        handleCommand(peerId, actor.level, text);
    }

    private void processInviteAction(JsonObject message, int peerId) {
        JsonObject action = message.getAsJsonObject("action");
        if (action == null) return;
        if (!"chat_invite_user".equals(action.has("type") ? action.get("type").getAsString() : "")) return;
        long invitedId = action.has("member_id") ? action.get("member_id").getAsLong() : 0;
        ChatBan ban = chatBans.get(invitedId);
        if (ban == null) return;
        if (ban.untilEpochMs > 0 && ban.untilEpochMs < System.currentTimeMillis()) {
            chatBans.remove(invitedId); plugin.getConfig().set("vk.chat-bans." + invitedId, null); plugin.saveConfig(); return;
        }
        kickFromCommandChat(invitedId);
        sendMessage(peerId, mentionById(invitedId) + " в бан-листе беседы и был удалён.");
    }

    private void handleCommand(int peerId, int actorLevel, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (equalsAny(lower, "!help", "!хелп", "!помощь")) { sendMessage(peerId, "См. README: !админы, !admin сет, !рник, !кик, !бан, !разбан, !ноунэйм, !cmd"); return; }
        if (equalsAny(lower, "!admins", "!админы")) { requireAndRun(peerId, actorLevel, "admins", () -> sendMessage(peerId, formatAdmins())); return; }
        if (lower.startsWith("!admin set") || lower.startsWith("!admin сет")) { requireAndRun(peerId, actorLevel, "admin_set", () -> handleAdminSet(peerId, actorLevel, text)); return; }
        if (lower.startsWith("!admin level") || lower.startsWith("!admin уровень") || lower.startsWith("!admin левел")) { requireAndRun(peerId, actorLevel, "admin_level", () -> handleAdminLevel(peerId, actorLevel, text)); return; }
        if (lower.startsWith("!admin remove") || lower.startsWith("!admin удалить")) { requireAndRun(peerId, actorLevel, "admin_remove", () -> handleAdminRemove(peerId, actorLevel, text)); return; }
        if (lower.startsWith("!rnick") || lower.startsWith("!рник")) { requireAndRun(peerId, actorLevel, "rnick", () -> handleRnick(peerId, text)); return; }
        if (lower.startsWith("!kick") || lower.startsWith("!кик")) { requireAndRun(peerId, actorLevel, "chat_kick", () -> handleChatKick(peerId, text)); return; }
        if (lower.startsWith("!ban") || lower.startsWith("!бан")) { requireAndRun(peerId, actorLevel, "chat_ban", () -> handleChatBan(peerId, text)); return; }
        if (lower.startsWith("!unban") || lower.startsWith("!разбан")) { requireAndRun(peerId, actorLevel, "chat_unban", () -> handleChatUnban(peerId, text)); return; }
        if (equalsAny(lower, "!noname", "!ноунэйм")) { requireAndRun(peerId, actorLevel, "noname", () -> handleNoName(peerId)); return; }
        if (lower.startsWith("!cmd ")) { requireAndRun(peerId, actorLevel, "cmd", () -> handleCmd(peerId, text.substring(5).trim())); return; }
        sendMessage(peerId, "Неизвестная команда. Используйте !help");
    }

    private void requireAndRun(int peerId, int actorLevel, String key, Runnable action) {
        if (!botAccessPolicy.hasAccess(actorLevel, key)) { sendMessage(peerId, "Слишком низкий уровень администратора для этой команды."); return; }
        action.run();
    }

    private boolean equalsAny(String value, String... c) { for (String x : c) if (value.equals(x)) return true; return false; }

    private void handleAdminSet(int peerId, int actorLevel, String text) {
        String[] p = text.split("\\s+");
        if (p.length < 4 || p.length > 5) { sendMessage(peerId, "Использование: !admin set @screen|@id123|id123 НИК [1-4]"); return; }
        UserProfile user = resolveUser(p[2]);
        if (user == null) { sendMessage(peerId, "Не удалось определить пользователя VK."); return; }
        int level = p.length == 5 ? parseLevel(p[4], -1) : 1;
        if (level < 1 || level > 4) { sendMessage(peerId, "Уровень должен быть 1..4."); return; }
        if (!canAssignLevel(actorLevel, level)) { sendMessage(peerId, "Слишком низкий уровень для назначения этого уровня."); return; }
        AdminData current = admins.get(user.id);
        if (current != null && !canModifyTarget(actorLevel, current.level)) { sendMessage(peerId, "Нельзя изменить администратора с таким уровнем."); return; }
        admins.put(user.id, new AdminData(level, p[3]));
        nicknames.put(user.id, p[3]);
        persistAdmin(user.id, level, p[3]);
        persistNickname(user.id, p[3]);
        sendMessage(peerId, "Назначено: " + user.mention + " - " + p[3] + " (lvl " + level + ")");
    }

    private void handleAdminLevel(int peerId, int actorLevel, String text) {
        String[] p = text.split("\\s+");
        if (p.length != 4) { sendMessage(peerId, "Использование: !admin level @id123 1..4"); return; }
        UserProfile user = resolveUser(p[2]);
        if (user == null || !admins.containsKey(user.id)) { sendMessage(peerId, "Администратор не найден."); return; }
        int level = parseLevel(p[3], -1);
        if (level < 1 || level > 4) { sendMessage(peerId, "Уровень должен быть 1..4."); return; }
        AdminData target = admins.get(user.id);
        if (!canModifyTarget(actorLevel, target.level) || !canAssignLevel(actorLevel, level)) { sendMessage(peerId, "Недостаточно прав."); return; }
        target.level = level;
        persistAdmin(user.id, target.level, target.nickname);
        sendMessage(peerId, "Уровень обновлён: " + user.mention + " -> " + level);
    }

    private void handleAdminRemove(int peerId, int actorLevel, String text) {
        String[] p = text.split("\\s+");
        if (p.length != 3) { sendMessage(peerId, "Использование: !admin remove @id123"); return; }
        UserProfile user = resolveUser(p[2]);
        if (user == null || !admins.containsKey(user.id)) { sendMessage(peerId, "Администратор не найден."); return; }
        AdminData target = admins.get(user.id);
        if (!canModifyTarget(actorLevel, target.level)) { sendMessage(peerId, "Слишком низкий уровень администратора."); return; }
        admins.remove(user.id);
        plugin.getConfig().set("vk.admins." + user.id, null);
        plugin.saveConfig();
        if (!target.nickname.isEmpty()) Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + target.nickname + " parent set default"));
        sendMessage(peerId, "Удалено: " + user.mention + ". Для ника " + target.nickname + " выставлен default.");
    }

    private void handleRnick(int peerId, String text) {
        String[] p = text.split("\\s+");
        if (p.length != 3) { sendMessage(peerId, "Использование: !rnick @id123 НИК"); return; }
        UserProfile user = resolveUser(p[1]);
        if (user == null) { sendMessage(peerId, "Пользователь не найден."); return; }
        nicknames.put(user.id, p[2]);
        persistNickname(user.id, p[2]);
        AdminData admin = admins.get(user.id);
        if (admin != null) { admin.nickname = p[2]; persistAdmin(user.id, admin.level, admin.nickname); }
        sendMessage(peerId, "Ник обновлен: " + user.mention + " -> " + p[2]);
    }

    private void handleChatKick(int peerId, String text) {
        String[] p = text.split("\\s+", 3);
        if (p.length < 2) { sendMessage(peerId, "Использование: !кик @id123 причина"); return; }
        UserProfile user = resolveUser(p[1]);
        if (user == null) { sendMessage(peerId, "Пользователь не найден."); return; }
        if (kickFromCommandChat(user.id)) sendMessage(peerId, "Кик: " + user.mention + ". Причина: " + (p.length > 2 ? p[2] : "не указана"));
        else sendMessage(peerId, "Не удалось кикнуть " + user.mention);
    }

    private void handleChatBan(int peerId, String text) {
        String[] p = text.split("\\s+");
        if (p.length < 2) { sendMessage(peerId, "Использование: !бан @id123 причина [1d]"); return; }
        UserProfile user = resolveUser(p[1]);
        if (user == null) { sendMessage(peerId, "Пользователь не найден."); return; }
        long until = 0;
        String reason;
        if (p.length >= 3 && DAYS_PATTERN.matcher(p[p.length - 1]).matches()) {
            int days = Integer.parseInt(p[p.length - 1].substring(0, p[p.length - 1].length() - 1));
            until = Instant.now().plus(days, ChronoUnit.DAYS).toEpochMilli();
            reason = joinRange(p, 2, p.length - 1);
        } else {
            reason = joinRange(p, 2, p.length);
        }
        chatBans.put(user.id, new ChatBan(until, reason));
        persistChatBan(user.id, until, reason);
        kickFromCommandChat(user.id);
        sendMessage(peerId, "Бан беседы: " + user.mention + (until > 0 ? " до " + Instant.ofEpochMilli(until) : " навсегда") + ".");
    }

    private void handleChatUnban(int peerId, String text) {
        String[] p = text.split("\\s+");
        if (p.length != 2) { sendMessage(peerId, "Использование: !разбан @id123"); return; }
        UserProfile user = resolveUser(p[1]);
        if (user == null) { sendMessage(peerId, "Пользователь не найден."); return; }
        chatBans.remove(user.id);
        plugin.getConfig().set("vk.chat-bans." + user.id, null);
        plugin.saveConfig();
        sendMessage(peerId, "Разбан беседы: " + user.mention);
    }

    private void handleNoName(int peerId) {
        try {
            JsonObject response = callVkMethod("messages.getConversationMembers", Map.of("peer_id", String.valueOf(toPeerId(commandChatId))));
            JsonObject obj = response.getAsJsonObject("response");
            if (obj == null) { sendMessage(peerId, "Не удалось получить участников беседы."); return; }
            List<String> unknown = new ArrayList<>();
            JsonArray items = obj.getAsJsonArray("items");
            if (items != null) {
                for (JsonElement e : items) {
                    long memberId = e.getAsJsonObject().get("member_id").getAsLong();
                    if (memberId > 0 && !nicknames.containsKey(memberId)) unknown.add(mentionById(memberId));
                }
            }
            sendMessage(peerId, unknown.isEmpty() ? "У всех участников беседы указан ник." : "Без ника: " + String.join(", ", unknown));
        } catch (Exception e) {
            sendMessage(peerId, "Ошибка команды no-name: " + e.getMessage());
        }
    }

    private void handleCmd(int peerId, String command) {
        if (command.isEmpty()) { sendMessage(peerId, "Пустая команда."); return; }
        String deny = commandPolicy.denyReason(command);
        if (deny != null) { sendMessage(peerId, deny); return; }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            sendMessage(peerId, "Команда отправлена!");
        });
    }

    private boolean kickFromCommandChat(long vkId) {
        try {
            callVkMethod("messages.removeChatUser", Map.of("chat_id", String.valueOf(commandChatId), "member_id", String.valueOf(vkId)));
            return true;
        } catch (Exception e) { return false; }
    }

    private UserProfile resolveUser(String token) {
        String t = token.trim();
        Matcher m = VK_MENTION_PATTERN.matcher(t);
        if (m.matches()) {
            if (m.group(2) != null) return loadUser("id" + m.group(2));
            if (m.group(3) != null) return loadUser(m.group(3));
        }
        if (t.startsWith("id")) return loadUser(t);
        try { return loadUser("id" + Long.parseLong(t)); } catch (NumberFormatException e) { return loadUser(t.replace("@", "")); }
    }

    private UserProfile loadUser(String userId) {
        try {
            JsonObject response = callVkMethod("users.get", Map.of("user_ids", userId));
            JsonArray arr = response.getAsJsonArray("response");
            if (arr == null || arr.isEmpty()) return null;
            JsonObject u = arr.get(0).getAsJsonObject();
            long id = u.get("id").getAsLong();
            String first = u.has("first_name") ? u.get("first_name").getAsString() : "VK";
            String last = u.has("last_name") ? u.get("last_name").getAsString() : "User";
            String screen = u.has("screen_name") ? u.get("screen_name").getAsString() : "id" + id;
            return new UserProfile(id, "[https://vk.com/" + screen + "|" + first + " " + last + "]");
        } catch (Exception e) { return null; }
    }

    private String mentionById(long id) {
        UserProfile p = loadUser("id" + id);
        return p == null ? "@id" + id : p.mention;
    }

    private String formatAdmins() {
        if (admins.isEmpty()) return "Администраторы не настроены.";
        List<String> out = new ArrayList<>();
        for (Map.Entry<Long, AdminData> e : admins.entrySet()) out.add(mentionById(e.getKey()) + " - " + e.getValue().nickname + " (lvl " + e.getValue().level + ")");
        return "Администраторы:\n" + String.join("\n", out);
    }

    private int parseLevel(String raw, int def) { try { return clampLevel(Integer.parseInt(raw)); } catch (NumberFormatException e) { return def; } }
    private boolean canAssignLevel(int actor, int target) { return actor >= 4 || (actor >= 3 && target == 1); }
    private boolean canModifyTarget(int actor, int target) { return actor >= 4 || actor > target; }
    private int clampLevel(int v) { return Math.max(1, Math.min(4, v)); }

    private void persistAdmin(long id, int level, String nick) { String b = "vk.admins." + id; plugin.getConfig().set(b + ".level", level); plugin.getConfig().set(b + ".nickname", nick); plugin.saveConfig(); }
    private void persistNickname(long id, String nick) { plugin.getConfig().set("vk.nicknames." + id, nick); plugin.saveConfig(); }
    private void persistChatBan(long id, long until, String reason) { String b = "vk.chat-bans." + id; plugin.getConfig().set(b + ".until-epoch-ms", until); plugin.getConfig().set(b + ".reason", reason); plugin.saveConfig(); }

    private String joinRange(String[] arr, int from, int to) { if (from >= to) return ""; StringBuilder sb = new StringBuilder(); for (int i = from; i < to; i++) { if (sb.length() > 0) sb.append(' '); sb.append(arr[i]); } return sb.toString(); }

    private void sendMessage(int peerId, String message) {
        if (accessToken.isEmpty()) return;
        try {
            Map<String, String> params = new HashMap<>();
            params.put("peer_id", String.valueOf(peerId));
            params.put("random_id", String.valueOf(UUID.randomUUID().hashCode()));
            params.put("message", message);
            callVkMethod("messages.send", params);
        } catch (Exception ignored) {}
    }

    private JsonObject callVkMethod(String method, Map<String, String> params) throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder("https://api.vk.com/method/").append(method).append('?');
        for (Map.Entry<String, String> e : params.entrySet()) { if (sb.charAt(sb.length() - 1) != '?') sb.append('&'); sb.append(encode(e.getKey())).append('=').append(encode(e.getValue())); }
        if (sb.charAt(sb.length() - 1) != '?') sb.append('&');
        sb.append("access_token=").append(encode(accessToken)).append("&v=").append(encode(apiVersion));
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create(sb.toString())).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonObject body = gson.fromJson(response.body(), JsonObject.class);
        if (body != null && body.has("error")) throw new IllegalStateException(body.get("error").toString());
        return body;
    }

    private int toPeerId(int chatId) { return chatId <= 0 ? 0 : PEER_CHAT_BASE + chatId; }
    private String encode(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    private static class AdminData { private int level; private String nickname; private AdminData(int l, String n) { level = l; nickname = n; } }
    private static class ChatBan { private final long untilEpochMs; private final String reason; private ChatBan(long u, String r) { untilEpochMs = u; reason = r; } }
    private static class UserProfile { private final long id; private final String mention; private UserProfile(long id, String mention) { this.id = id; this.mention = mention; } }
    private static class BotAccessPolicy { private final Map<String, Integer> levels; private BotAccessPolicy(Map<String, Integer> l) { levels = l; } private boolean hasAccess(int actor, String key) { return actor >= levels.getOrDefault(key, 4); } }

    private static class CommandPolicy {
        private final boolean enabled; private final String mode; private final Set<String> blacklist; private final Set<String> whitelist;
        private CommandPolicy(boolean enabled, String mode, Set<String> blacklist, Set<String> whitelist) { this.enabled = enabled; this.mode = mode; this.blacklist = blacklist; this.whitelist = whitelist; }
        private static CommandPolicy defaultPolicy() { return new CommandPolicy(true, "blacklist", new HashSet<>(Set.of("op", "deop", "stop", "reload")), new HashSet<>()); }
        private String denyReason(String command) {
            String c = command.trim().toLowerCase(Locale.ROOT); if (c.isEmpty()) return "Пустая команда.";
            if (enabled && "blacklist".equals(mode) && startsWithAny(c, blacklist)) return "Слишком низкий уровень администратора / вам запрещено использовать эту команду.";
            if (enabled && "whitelist".equals(mode) && !startsWithAny(c, whitelist)) return "Команда не в whitelist.";
            return null;
        }
        private boolean startsWithAny(String cmd, Set<String> set) { for (String s : set) if (cmd.startsWith(s)) return true; return false; }
    }
}
