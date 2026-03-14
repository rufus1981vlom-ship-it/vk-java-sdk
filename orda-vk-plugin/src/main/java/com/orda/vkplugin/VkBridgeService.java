package com.orda.vkplugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
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

    private final Map<ChatType, Integer> chatIds = new EnumMap<ChatType, Integer>(ChatType.class);
    private Map<Long, AdminData> admins = new HashMap<Long, AdminData>();
    private Map<Long, String> nicknames = new HashMap<Long, String>();
    private Map<Long, ChatBan> chatBans = new HashMap<Long, ChatBan>();

    private CommandPolicy commandPolicy;
    private BotAccessPolicy botAccessPolicy;

    private String lpServer;
    private String lpKey;
    private String lpTs;

    private final Map<Integer, Ticket> tickets = new LinkedHashMap<Integer, Ticket>();
    private int nextTicketId = 1;

    private final Map<UUID, Long> onlineSince = new HashMap<UUID, Long>();
    private final Map<UUID, Long> lastSeen = new HashMap<UUID, Long>();
    private final Map<UUID, NavigableMap<LocalDate, Long>> onlineStats = new HashMap<UUID, NavigableMap<LocalDate, Long>>();
    private final Map<String, Deque<CommandLogEntry>> commandLog = new HashMap<String, Deque<CommandLogEntry>>();
    private final Map<UUID, Deque<PendingMessage>> pendingByUuid = new HashMap<UUID, Deque<PendingMessage>>();

    private final Map<String, String> quickReplyTemplates = new HashMap<String, String>();
    private final Map<String, String> mutePresets = new HashMap<String, String>();
    private final Map<String, String> banPresets = new HashMap<String, String>();
    private final Map<String, String> warnPresets = new HashMap<String, String>();

    private final Map<String, Long> antiSpam = new HashMap<String, Long>();

    private int retentionCommandDays = 7;
    private int retentionPendingDays = 7;
    private int retentionClosedTicketsDays = 14;

    private String rawRole = "chief";

    public VkBridgeService(OrdaVkPlugin plugin, Gson gson) {
        this.plugin = plugin;
        this.gson = gson;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public void start() {
        reloadState();
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                pollLoop();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        running = false;
        if (executor != null) executor.shutdownNow();
    }

    public void notifyEvent(String message) {
        if (isDuplicate("evt:" + message, 1200L)) return;
        sendToChat(ChatType.EVENTS, "🛰 " + message);
    }

    public void onPlayerJoin(Player player) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        onlineSince.put(uuid, now);
        lastSeen.put(uuid, now);
        deliverPending(uuid, player.getName());
    }

    public void onPlayerQuit(Player player) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        Long started = onlineSince.remove(uuid);
        if (started != null) {
            long duration = Math.max(0, now - started.longValue());
            LocalDate date = LocalDate.now();
            NavigableMap<LocalDate, Long> stats = onlineStats.get(uuid);
            if (stats == null) {
                stats = new TreeMap<LocalDate, Long>();
                onlineStats.put(uuid, stats);
            }
            Long current = stats.get(date);
            stats.put(date, Long.valueOf((current == null ? 0L : current.longValue()) + duration));
        }
        lastSeen.put(uuid, now);
    }

    public void onPlayerCommand(String actor, String commandRaw) {
        String command = commandRaw.startsWith("/") ? commandRaw : "/" + commandRaw;
        String lower = command.toLowerCase(Locale.ROOT);

        if (!lower.startsWith("/reg") && !lower.startsWith("/l")) {
            String key = actor.toLowerCase(Locale.ROOT);
            Deque<CommandLogEntry> log = commandLog.get(key);
            if (log == null) {
                log = new ArrayDeque<CommandLogEntry>();
                commandLog.put(key, log);
            }
            log.addLast(new CommandLogEntry(Instant.now(), command));
        }

        if (lower.startsWith("/ac ") || lower.startsWith("/helpop ")) {
            createTicket(TicketCategory.SUPPORT, actor, tailAfterFirstSpace(command), true);
        } else if (lower.startsWith("/report ") || lower.startsWith("/rep ")) {
            createTicket(TicketCategory.REPORT, actor, tailAfterFirstSpace(command), true);
        }

        cleanupRuntimeData();
    }

    private void reloadState() {
        plugin.reloadConfig();
        apiVersion = plugin.getConfig().getString("vk.api-version", "5.199");
        accessToken = plugin.getConfig().getString("vk.token", "");
        groupId = plugin.getConfig().getInt("vk.group-id", 0);

        int legacyCommand = plugin.getConfig().getInt("vk.command-chat-id", 0);
        int legacyEvents = plugin.getConfig().getInt("vk.event-chat-id", 0);
        chatIds.put(ChatType.ADMMANAGE, Integer.valueOf(plugin.getConfig().getInt("vk.chats.ADMMANAGE", legacyCommand)));
        chatIds.put(ChatType.EVENTS, Integer.valueOf(plugin.getConfig().getInt("vk.chats.EVENTS", legacyEvents)));
        chatIds.put(ChatType.SUPPORT, Integer.valueOf(plugin.getConfig().getInt("vk.chats.SUPPORT", legacyCommand)));
        chatIds.put(ChatType.MODMANAGE, Integer.valueOf(plugin.getConfig().getInt("vk.chats.MODMANAGE", legacyCommand)));

        loadAdmins();
        loadNicknames();
        loadChatBans();
        commandPolicy = loadCommandPolicy();
        botAccessPolicy = loadBotAccessPolicy();
        loadTemplatesAndPresets();
        loadRetention();
    }

    private void loadTemplatesAndPresets() {
        quickReplyTemplates.clear();
        Map<String, String> defaults = new HashMap<String, String>();
        defaults.put("accepted", "Принято, уже в работе.");
        defaults.put("checking", "Проверяем, дайте пару минут.");
        defaults.put("needproof", "Нужны дополнительные доказательства.");
        defaults.put("closed", "Кейс закрыт. Спасибо за обращение.");

        ConfigurationSection templates = plugin.getConfig().getConfigurationSection("vk.reply-templates");
        if (templates == null) {
            quickReplyTemplates.putAll(defaults);
        } else {
            for (String key : templates.getKeys(false)) {
                quickReplyTemplates.put(key.toLowerCase(Locale.ROOT), templates.getString(key, ""));
            }
            for (Map.Entry<String, String> e : defaults.entrySet()) {
                if (!quickReplyTemplates.containsKey(e.getKey())) quickReplyTemplates.put(e.getKey(), e.getValue());
            }
        }

        fillPresetMap(mutePresets, "vk.punishment-presets.mute", "flood", "10m Flood");
        fillPresetMap(banPresets, "vk.punishment-presets.ban", "cheat", "7d Cheat client");
        fillPresetMap(warnPresets, "vk.punishment-presets.warn", "tox", "Toxic behavior");
    }

    private void fillPresetMap(Map<String, String> target, String path, String defaultKey, String defaultValue) {
        target.clear();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection(path);
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                target.put(key.toLowerCase(Locale.ROOT), sec.getString(key, ""));
            }
        }
        if (!target.containsKey(defaultKey)) target.put(defaultKey, defaultValue);
    }

    private void loadRetention() {
        retentionCommandDays = Math.max(1, plugin.getConfig().getInt("vk.retention.command-log-days", 7));
        retentionPendingDays = Math.max(1, plugin.getConfig().getInt("vk.retention.pending-days", 7));
        retentionClosedTicketsDays = Math.max(1, plugin.getConfig().getInt("vk.retention.closed-ticket-days", 14));
    }

    private void loadAdmins() {
        admins = new HashMap<Long, AdminData>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("vk.admins");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                long id = Long.parseLong(key);
                String base = "vk.admins." + key;
                admins.put(Long.valueOf(id), new AdminData(
                        clampLevel(plugin.getConfig().getInt(base + ".level", 1)),
                        plugin.getConfig().getString(base + ".nickname", "").trim()));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void loadNicknames() {
        nicknames = new HashMap<Long, String>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("vk.nicknames");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                long id = Long.parseLong(key);
                String nick = plugin.getConfig().getString("vk.nicknames." + key, "").trim();
                if (!nick.isEmpty()) nicknames.put(Long.valueOf(id), nick);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void loadChatBans() {
        chatBans = new HashMap<Long, ChatBan>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("vk.chat-bans");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                long id = Long.parseLong(key);
                String base = "vk.chat-bans." + key;
                chatBans.put(Long.valueOf(id), new ChatBan(
                        plugin.getConfig().getLong(base + ".until-epoch-ms", 0),
                        plugin.getConfig().getString(base + ".reason", "")));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private CommandPolicy loadCommandPolicy() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("vk.command-policy");
        if (sec == null) return CommandPolicy.defaultPolicy();
        return new CommandPolicy(
                sec.getBoolean("enabled", true),
                sec.getString("mode", "blacklist").toLowerCase(Locale.ROOT),
                normalizeSet(sec.getStringList("blacklist")),
                normalizeSet(sec.getStringList("whitelist")),
                normalizeSet(sec.getStringList("absolute-blacklist")),
                sec.getInt("chief-bypass-level", 4));
    }

    private BotAccessPolicy loadBotAccessPolicy() {
        Map<String, Integer> defaults = new HashMap<String, Integer>();
        defaults.put("admins", 2);
        defaults.put("admin_set", 3);
        defaults.put("admin_level", 3);
        defaults.put("admin_remove", 3);
        defaults.put("rnick", 2);
        defaults.put("chat_kick", 2);
        defaults.put("chat_ban", 3);
        defaults.put("chat_unban", 3);
        defaults.put("noname", 2);
        defaults.put("cmd", 4);
        defaults.put("tickets", 1);
        defaults.put("punish", 2);

        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("vk.bot-command-access");
        if (sec == null) return new BotAccessPolicy(defaults);

        Map<String, Integer> out = new HashMap<String, Integer>(defaults);
        for (String key : sec.getKeys(false)) {
            out.put(key, Integer.valueOf(clampLevel(sec.getInt(key, out.containsKey(key) ? out.get(key).intValue() : 4))));
        }
        return new BotAccessPolicy(out);
    }

    private Set<String> normalizeSet(List<String> list) {
        Set<String> out = new HashSet<String>();
        for (String entry : list) {
            if (entry == null || entry.trim().isEmpty()) continue;
            out.add(entry.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private void pollLoop() {
        if (!running) return;
        if (accessToken == null || accessToken.isEmpty() || groupId <= 0) return;

        try {
            ensureLongPoll();
            String url = lpServer + "?act=a_check&key=" + encode(lpKey) + "&ts=" + encode(lpTs) + "&wait=25&version=3";
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(35)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject body = gson.fromJson(response.body(), JsonObject.class);
            if (body == null) return;

            if (body.has("failed")) {
                int failed = body.get("failed").getAsInt();
                if (failed == 1 && body.has("ts")) lpTs = body.get("ts").getAsString();
                else lpServer = lpKey = lpTs = null;
                return;
            }

            if (body.has("ts")) lpTs = body.get("ts").getAsString();
            JsonArray updates = body.getAsJsonArray("updates");
            if (updates == null) return;

            for (int i = 0; i < updates.size(); i++) {
                onUpdate(updates.get(i).getAsJsonObject());
            }
        } catch (Exception ignored) {
        }
    }

    private void ensureLongPoll() throws IOException, InterruptedException {
        if (lpServer != null && lpKey != null && lpTs != null) return;
        JsonObject response = callVkMethod("groups.getLongPollServer", mapOf("group_id", String.valueOf(groupId)));
        JsonObject obj = response.getAsJsonObject("response");
        if (obj == null) throw new IllegalStateException("groups.getLongPollServer failed");
        lpServer = obj.get("server").getAsString();
        lpKey = obj.get("key").getAsString();
        lpTs = obj.get("ts").getAsString();
    }

    private void onUpdate(JsonObject update) {
        if (!"message_new".equals(update.has("type") ? update.get("type").getAsString() : "")) return;
        JsonObject object = update.getAsJsonObject("object");
        if (object == null) return;
        JsonObject message = object.getAsJsonObject("message");
        if (message == null) return;

        int peerId = message.get("peer_id").getAsInt();
        if (!isKnownPeer(peerId)) return;

        processInviteAction(message, peerId);

        String text = message.has("text") ? message.get("text").getAsString().trim() : "";
        if (text.isEmpty()) return;

        AdminData actor = admins.get(Long.valueOf(message.get("from_id").getAsLong()));
        if (actor == null) {
            sendMessage(peerId, "⛔ Нет доступа.");
            return;
        }

        handleCommand(peerId, actor.level, actor.nickname, text);
    }

    private boolean isKnownPeer(int peerId) {
        for (Integer chatId : chatIds.values()) {
            if (toPeerId(chatId.intValue()) == peerId) return true;
        }
        return false;
    }

    private void processInviteAction(JsonObject message, int peerId) {
        JsonObject action = message.getAsJsonObject("action");
        if (action == null) return;
        if (!"chat_invite_user".equals(action.has("type") ? action.get("type").getAsString() : "")) return;

        long invitedId = action.has("member_id") ? action.get("member_id").getAsLong() : 0;
        ChatBan ban = chatBans.get(Long.valueOf(invitedId));
        if (ban == null) return;

        if (ban.untilEpochMs > 0 && ban.untilEpochMs < System.currentTimeMillis()) {
            chatBans.remove(Long.valueOf(invitedId));
            plugin.getConfig().set("vk.chat-bans." + invitedId, null);
            plugin.saveConfig();
            return;
        }

        kickFromChat(invitedId, peerId - PEER_CHAT_BASE);
        sendMessage(peerId, "⛔ " + mentionById(invitedId) + " в бане чата.");
    }

    private void handleCommand(int peerId, int actorLevel, String actorNick, String text) {
        String lower = text.toLowerCase(Locale.ROOT);

        if (lower.equals("!help") || lower.equals("!help support") || lower.equals("!help mod") || lower.equals("!help admin")) {
            sendMessage(peerId, formatHelp(actorLevel, lower));
            return;
        }

        if (lower.equals("!admins") || lower.equals("!админы")) { requireAndRun(peerId, actorLevel, "admins", new Runnable() { @Override public void run() { sendMessage(peerId, formatAdmins()); } }); return; }
        if (lower.startsWith("!tickets")) { requireAndRun(peerId, actorLevel, "tickets", new Runnable() { @Override public void run() { sendMessage(peerId, formatTickets()); } }); return; }
        if (lower.startsWith("!ticket ")) { requireAndRun(peerId, actorLevel, "tickets", new Runnable() { @Override public void run() { handleTicket(peerId, text); } }); return; }
        if (lower.startsWith("!reply ")) { requireAndRun(peerId, actorLevel, "tickets", new Runnable() { @Override public void run() { handleReply(peerId, actorNick, text); } }); return; }
        if (lower.startsWith("!assign ")) { requireAndRun(peerId, actorLevel, "tickets", new Runnable() { @Override public void run() { handleAssign(peerId, text); } }); return; }
        if (lower.startsWith("!close ")) { requireAndRun(peerId, actorLevel, "tickets", new Runnable() { @Override public void run() { handleClose(peerId, actorNick, text); } }); return; }
        if (lower.startsWith("!move ")) { requireAndRun(peerId, actorLevel, "tickets", new Runnable() { @Override public void run() { handleMove(peerId, text); } }); return; }
        if (lower.startsWith("!r ")) { requireAndRun(peerId, actorLevel, "tickets", new Runnable() { @Override public void run() { handleTemplateReply(peerId, actorNick, text); } }); return; }

        if (lower.startsWith("!check tech ")) { requireAndRun(peerId, actorLevel, "tickets", new Runnable() { @Override public void run() { handleCheckTech(peerId, text); } }); return; }
        if (lower.startsWith("!check ")) { requireAndRun(peerId, actorLevel, "tickets", new Runnable() { @Override public void run() { handleCheck(peerId, text); } }); return; }
        if (lower.startsWith("!lookup ")) { requireAndRun(peerId, actorLevel, "tickets", new Runnable() { @Override public void run() { handleLookup(peerId, text); } }); return; }
        if (lower.startsWith("!staffstats ")) { requireAndRun(peerId, actorLevel, "tickets", new Runnable() { @Override public void run() { handleStaffStats(peerId, text); } }); return; }

        if (lower.startsWith("!admin set") || lower.startsWith("!admin сет")) { requireAndRun(peerId, actorLevel, "admin_set", new Runnable() { @Override public void run() { handleAdminSet(peerId, actorLevel, text); } }); return; }
        if (lower.startsWith("!admin level") || lower.startsWith("!admin уровень") || lower.startsWith("!admin левел")) { requireAndRun(peerId, actorLevel, "admin_level", new Runnable() { @Override public void run() { handleAdminLevel(peerId, actorLevel, text); } }); return; }
        if (lower.startsWith("!admin remove") || lower.startsWith("!admin удалить")) { requireAndRun(peerId, actorLevel, "admin_remove", new Runnable() { @Override public void run() { handleAdminRemove(peerId, actorLevel, text); } }); return; }
        if (lower.startsWith("!rnick") || lower.startsWith("!рник")) { requireAndRun(peerId, actorLevel, "rnick", new Runnable() { @Override public void run() { handleRnick(peerId, text); } }); return; }

        if (lower.startsWith("!kick") || lower.startsWith("!кик")) { requireAndRun(peerId, actorLevel, "chat_kick", new Runnable() { @Override public void run() { handleChatKick(peerId, text); } }); return; }
        if (lower.startsWith("!unban") || lower.startsWith("!разбан")) { requireAndRun(peerId, actorLevel, "chat_unban", new Runnable() { @Override public void run() { handleChatUnban(peerId, text); } }); return; }
        if (lower.startsWith("!ban") || lower.startsWith("!бан")) { requireAndRun(peerId, actorLevel, "chat_ban", new Runnable() { @Override public void run() { handleBan(peerId, actorNick, text); } }); return; }
        if (lower.startsWith("!mute ")) { requireAndRun(peerId, actorLevel, "punish", new Runnable() { @Override public void run() { handlePunish(peerId, actorNick, text, "mute"); } }); return; }
        if (lower.startsWith("!warn ")) { requireAndRun(peerId, actorLevel, "punish", new Runnable() { @Override public void run() { handlePunish(peerId, actorNick, text, "warn"); } }); return; }
        if (lower.equals("!noname") || lower.equals("!ноунэйм")) { requireAndRun(peerId, actorLevel, "noname", new Runnable() { @Override public void run() { handleNoName(peerId); } }); return; }
        if (lower.startsWith("!cmd ")) { requireAndRun(peerId, actorLevel, "cmd", new Runnable() { @Override public void run() { handleCmd(peerId, actorLevel, text.substring(5).trim()); } }); return; }

        sendMessage(peerId, "⛔ Неизвестная команда. Используйте !help");
    }

    private void requireAndRun(int peerId, int actorLevel, String key, Runnable action) {
        if (!botAccessPolicy.hasAccess(actorLevel, key)) {
            sendMessage(peerId, "⛔ Недостаточно прав.");
            return;
        }
        action.run();
    }

    private String formatHelp(int level, String mode) {
        if ("!help admin".equals(mode) && level < 3) return "⛔ Раздел admin недоступен.";
        if ("!help mod".equals(mode) && level < 2) return "⛔ Раздел mod недоступен.";

        List<String> rows = new ArrayList<String>();
        rows.add("🧭 OrdaVK Lite — быстрые действия");

        if ("!help".equals(mode) || "!help support".equals(mode)) {
            rows.add("Support: !tickets | !ticket <id> | !reply <id> <text> | !close <id>");
        }

        if (("!help".equals(mode) && level >= 2) || "!help mod".equals(mode)) {
            rows.add("Moderation: !assign <id> <vk_id> | !move <id> support|report | !r <id> <template>");
            rows.add("Player: !check <nick> | !lookup <nick> | !staffstats <vk|nick>");
        }

        if (("!help".equals(mode) && level >= 3) || "!help admin".equals(mode)) {
            rows.add("Admin: !admin set/level/remove | !rnick | !noname");
            rows.add("Raw/Punish: !cmd <command> | !mute | !ban | !warn");
        }

        return String.join("\n", rows);
    }

    private Ticket createTicket(TicketCategory category, String author, String body, boolean notify) {
        Ticket ticket = new Ticket(nextTicketId++, category, author, body);
        tickets.put(Integer.valueOf(ticket.id), ticket);

        if (notify) {
            ChatType chat = category == TicketCategory.SUPPORT ? ChatType.SUPPORT : ChatType.MODMANAGE;
            sendToChat(chat, "🎫 #" + ticket.id + " " + category.name().toLowerCase(Locale.ROOT) + " | " + author + "\n" + body);
        }

        return ticket;
    }

    private String formatTickets() {
        List<String> rows = new ArrayList<String>();
        for (Ticket ticket : tickets.values()) {
            if (ticket.status != TicketStatus.CLOSED) {
                rows.add("#" + ticket.id + " | " + ticket.category.name().toLowerCase(Locale.ROOT) + " | " + ticket.status + " | @" + ticket.author);
            }
        }
        if (rows.isEmpty()) return "🎫 Активных тикетов нет.";
        rows.add(0, "🎫 Открытые тикеты");
        return String.join("\n", rows);
    }

    private void handleTicket(int peerId, String text) {
        String[] args = text.split("\\s+");
        if (args.length != 2) {
            sendMessage(peerId, "Использование: !ticket <id>");
            return;
        }

        Ticket ticket = findTicket(args[1]);
        if (ticket == null) {
            sendMessage(peerId, "Тикет не найден.");
            return;
        }

        sendMessage(peerId, ticket.formatCard());
    }

    private void handleReply(int peerId, String actor, String text) {
        String[] args = text.split("\\s+", 3);
        if (args.length < 3) {
            sendMessage(peerId, "Использование: !reply <id> <text>");
            return;
        }

        Ticket ticket = findTicket(args[1]);
        if (ticket == null) {
            sendMessage(peerId, "Тикет не найден.");
            return;
        }

        ticket.status = TicketStatus.IN_PROGRESS;
        ticket.history.add("↪ " + actor + ": " + args[2]);
        ticket.touch();
        ticket.staffStats.replies += 1;

        sendMessage(peerId, "✅ Ответ добавлен в #" + ticket.id);

        OfflinePlayer target = Bukkit.getOfflinePlayer(ticket.author);
        Player online = target.getPlayer();
        if (online != null && online.isOnline()) {
            online.sendMessage("[Support] " + args[2]);
        } else {
            queuePending(target.getUniqueId(), ticket.id, args[2]);
        }
    }

    private void handleAssign(int peerId, String text) {
        String[] args = text.split("\\s+");
        if (args.length != 3) {
            sendMessage(peerId, "Использование: !assign <id> <vk_id>");
            return;
        }

        Ticket ticket = findTicket(args[1]);
        if (ticket == null) {
            sendMessage(peerId, "Тикет не найден.");
            return;
        }

        ticket.assignedVkId = args[2];
        ticket.history.add("👤 Ответственный: " + args[2]);
        ticket.touch();

        sendMessage(peerId, "✅ #" + ticket.id + " назначен на " + args[2]);
    }

    private void handleClose(int peerId, String actor, String text) {
        String[] args = text.split("\\s+");
        if (args.length != 2) {
            sendMessage(peerId, "Использование: !close <id>");
            return;
        }

        Ticket ticket = findTicket(args[1]);
        if (ticket == null) {
            sendMessage(peerId, "Тикет не найден.");
            return;
        }

        ticket.status = TicketStatus.CLOSED;
        ticket.history.add("✅ Закрыт: " + actor);
        ticket.touch();

        sendMessage(peerId, "✅ Тикет #" + ticket.id + " закрыт.");
    }

    private void handleMove(int peerId, String text) {
        String[] args = text.split("\\s+");
        if (args.length != 3) {
            sendMessage(peerId, "Использование: !move <id> support|report");
            return;
        }

        Ticket ticket = findTicket(args[1]);
        if (ticket == null) {
            sendMessage(peerId, "Тикет не найден.");
            return;
        }

        TicketCategory target = parseTicketCategory(args[2]);
        if (target == null) {
            sendMessage(peerId, "Категория: support|report");
            return;
        }
        if (ticket.category == target) {
            sendMessage(peerId, "Тикет уже в нужной категории.");
            return;
        }

        TicketCategory previous = ticket.category;
        ticket.category = target;
        ticket.history.add("🔁 Перенос: " + previous.name().toLowerCase(Locale.ROOT) + " -> " + target.name().toLowerCase(Locale.ROOT));
        ticket.touch();

        sendMessage(peerId, "✅ #" + ticket.id + " перенесён: " + previous.name().toLowerCase(Locale.ROOT) + " -> " + target.name().toLowerCase(Locale.ROOT));
        sendToChat(target == TicketCategory.SUPPORT ? ChatType.SUPPORT : ChatType.MODMANAGE,
                "🔁 Тикет #" + ticket.id + " перенесен сюда\nИз: " + previous.name().toLowerCase(Locale.ROOT) + "\nВ: " + target.name().toLowerCase(Locale.ROOT));
    }

    private TicketCategory parseTicketCategory(String raw) {
        if ("support".equalsIgnoreCase(raw)) return TicketCategory.SUPPORT;
        if ("report".equalsIgnoreCase(raw)) return TicketCategory.REPORT;
        return null;
    }

    private void handleTemplateReply(int peerId, String actor, String text) {
        String[] args = text.split("\\s+");
        if (args.length != 3) {
            sendMessage(peerId, "Использование: !r <id> <template>");
            return;
        }

        String template = quickReplyTemplates.get(args[2].toLowerCase(Locale.ROOT));
        if (template == null || template.trim().isEmpty()) {
            sendMessage(peerId, "Шаблон не найден. Доступные: " + String.join(", ", quickReplyTemplates.keySet()));
            return;
        }

        handleReply(peerId, actor, "!reply " + args[1] + " " + template);
    }

    private void handleCheck(int peerId, String text) {
        String[] args = text.split("\\s+");
        if (args.length != 2) {
            sendMessage(peerId, "Использование: !check <nick>");
            return;
        }

        OfflinePlayer player = Bukkit.getOfflinePlayer(args[1]);
        UUID uuid = player.getUniqueId();

        String[] lines = new String[] {
                "👤 " + args[1],
                "Статус: " + (player.isOnline() ? "online" : "offline"),
                "Last seen: " + formatLastSeen(lastSeen.containsKey(uuid) ? lastSeen.get(uuid).longValue() : player.getLastPlayed(), player.isOnline()),
                "Онлайн сегодня: " + formatDuration(onlineForDays(uuid, 1)),
                "Онлайн 7 дней: " + formatDuration(onlineForDays(uuid, 7)),
                "Онлайн 30 дней: " + formatDuration(onlineForDays(uuid, 30)),
                "Группа/роль: n/a",
                "Активные наказания: n/a",
                "Тикеты/репорты: " + countTicketsByAuthor(args[1])
        };

        sendMessage(peerId, String.join("\n", lines));
    }

    private void handleCheckTech(int peerId, String text) {
        String[] args = text.split("\\s+");
        if (args.length != 3) {
            sendMessage(peerId, "Использование: !check tech <nick>");
            return;
        }

        OfflinePlayer player = Bukkit.getOfflinePlayer(args[2]);
        sendMessage(peerId,
                "🧪 TECH " + args[2] + "\n" +
                "UUID: " + player.getUniqueId() + "\n" +
                "firstPlayed: " + player.getFirstPlayed() + "\n" +
                "lastPlayed: " + player.getLastPlayed());
    }

    private void handleLookup(int peerId, String text) {
        String[] args = text.split("\\s+");
        if (args.length != 2) {
            sendMessage(peerId, "Использование: !lookup <nick>");
            return;
        }

        cleanupRuntimeData();

        Deque<CommandLogEntry> entries = commandLog.get(args[1].toLowerCase(Locale.ROOT));
        if (entries == null || entries.isEmpty()) {
            sendMessage(peerId, "📜 За 7 дней нет команд.");
            return;
        }

        List<String> rows = new ArrayList<String>();
        rows.add("📜 Lookup " + args[1] + " (7д)");

        int limit = 10;
        int i = 0;
        for (CommandLogEntry entry : entries) {
            rows.add(formatAgo(entry.time) + " • " + entry.command);
            i++;
            if (i >= limit) break;
        }

        sendMessage(peerId, String.join("\n", rows));
    }

    private void handleStaffStats(int peerId, String text) {
        String[] args = text.split("\\s+");
        if (args.length != 2) {
            sendMessage(peerId, "Использование: !staffstats <vk_id|nick>");
            return;
        }

        StaffAggregation stats = collectStaffStats(args[1]);
        List<String> rows = new ArrayList<String>();
        rows.add("📊 StaffStats " + args[1]);
        rows.add("Support кейсы: " + stats.supportClosed);
        rows.add("Report кейсы: " + stats.reportClosed);
        rows.add("Ответов в тикетах: " + stats.replies);
        rows.add("Наказаний: " + stats.punishments);
        rows.add("Отменено старшими: " + stats.revertedPunishments);
        rows.add("1-й ответ (сред): " + (stats.firstResponseMinutes <= 0 ? "н/д" : stats.firstResponseMinutes + " мин"));
        rows.add("Рекомендация: " + stats.recommendation);
        sendMessage(peerId, String.join("\n", rows));
    }

    private StaffAggregation collectStaffStats(String key) {
        StaffAggregation s = new StaffAggregation();
        for (Ticket ticket : tickets.values()) {
            if (ticket.assignedVkId.equalsIgnoreCase(key) || ticket.author.equalsIgnoreCase(key)) {
                if (ticket.status == TicketStatus.CLOSED) {
                    if (ticket.category == TicketCategory.SUPPORT) s.supportClosed++;
                    else s.reportClosed++;
                }
                s.replies += ticket.staffStats.replies;
                s.punishments += ticket.staffStats.punishments;
                s.revertedPunishments += ticket.staffStats.revertedPunishments;
                if (ticket.staffStats.firstResponseMinutes > 0) {
                    s.firstResponseMinutes = s.firstResponseMinutes <= 0
                            ? ticket.staffStats.firstResponseMinutes
                            : (s.firstResponseMinutes + ticket.staffStats.firstResponseMinutes) / 2;
                }
            }
        }

        if (s.supportClosed + s.reportClosed >= 10 && s.firstResponseMinutes > 0 && s.firstResponseMinutes <= 5) {
            s.recommendation = "Сильный кандидат на повышение";
        } else if (s.supportClosed + s.reportClosed >= 5) {
            s.recommendation = "Стабильная работа";
        } else {
            s.recommendation = "Нужно больше данных";
        }
        return s;
    }

    private void handleBan(int peerId, String actor, String text) {
        String[] args = text.split("\\s+");
        if (args.length < 2) {
            sendMessage(peerId, "Использование: !ban <nick|@id> <preset|time reason>");
            return;
        }

        // @id / id123 -> чат-бан VK, иначе бан игрока
        if (args[1].startsWith("@") || args[1].startsWith("id")) {
            handleChatBan(peerId, text);
            return;
        }
        handlePunish(peerId, actor, text, "ban");
    }

    private void handlePunish(int peerId, String actor, String text, String type) {
        String[] args = text.split("\\s+", 3);
        if (args.length < 3) {
            sendMessage(peerId, "Использование: !" + type + " <nick> <preset|time reason>");
            return;
        }

        String target = args[1];
        String payload = args[2];

        String resolved = resolvePreset(type, payload);
        String command;
        if ("warn".equals(type)) command = "warn " + target + " " + resolved;
        else command = type + " " + target + " " + resolved;

        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
        });

        sendMessage(peerId, "✅ " + type.toUpperCase(Locale.ROOT) + " отправлен: " + target + " | " + resolved);
        sendToChat(ChatType.MODMANAGE, "⚖ " + actor + " -> /" + command);

        Ticket linked = findLatestReportByAuthor(target);
        if (linked != null) {
            linked.staffStats.punishments += 1;
            linked.history.add("⚖ Наказание: " + type + " " + target + " " + resolved);
            linked.touch();
        }
    }

    private String resolvePreset(String type, String payload) {
        Map<String, String> source;
        if ("mute".equals(type)) source = mutePresets;
        else if ("ban".equals(type)) source = banPresets;
        else source = warnPresets;

        String preset = source.get(payload.toLowerCase(Locale.ROOT));
        return preset == null || preset.trim().isEmpty() ? payload : preset;
    }

    private Ticket findLatestReportByAuthor(String author) {
        Ticket latest = null;
        for (Ticket t : tickets.values()) {
            if (!t.author.equalsIgnoreCase(author)) continue;
            if (t.category != TicketCategory.REPORT) continue;
            latest = t;
        }
        return latest;
    }

    private void handleAdminSet(int peerId, int actorLevel, String text) {
        String[] p = text.split("\\s+");
        if (p.length < 4 || p.length > 5) { sendMessage(peerId, "Использование: !admin set @screen|@id123|id123 НИК [1-4]"); return; }
        UserProfile user = resolveUser(p[2]);
        if (user == null) { sendMessage(peerId, "Не удалось определить пользователя VK."); return; }
        int level = p.length == 5 ? parseLevel(p[4], -1) : 1;
        if (level < 1 || level > 4) { sendMessage(peerId, "Уровень должен быть 1..4."); return; }
        if (!canAssignLevel(actorLevel, level)) { sendMessage(peerId, "Слишком низкий уровень для назначения этого уровня."); return; }

        AdminData current = admins.get(Long.valueOf(user.id));
        if (current != null && !canModifyTarget(actorLevel, current.level)) { sendMessage(peerId, "Нельзя изменить администратора с таким уровнем."); return; }

        admins.put(Long.valueOf(user.id), new AdminData(level, p[3]));
        nicknames.put(Long.valueOf(user.id), p[3]);
        persistAdmin(user.id, level, p[3]);
        persistNickname(user.id, p[3]);
        sendMessage(peerId, "✅ Назначено: " + user.mention + " — " + p[3] + " (lvl " + level + ")");
    }

    private void handleAdminLevel(int peerId, int actorLevel, String text) {
        String[] p = text.split("\\s+");
        if (p.length != 4) { sendMessage(peerId, "Использование: !admin level @id123 1..4"); return; }
        UserProfile user = resolveUser(p[2]);
        if (user == null || !admins.containsKey(Long.valueOf(user.id))) { sendMessage(peerId, "Администратор не найден."); return; }
        int level = parseLevel(p[3], -1);
        if (level < 1 || level > 4) { sendMessage(peerId, "Уровень должен быть 1..4."); return; }
        AdminData target = admins.get(Long.valueOf(user.id));
        if (!canModifyTarget(actorLevel, target.level) || !canAssignLevel(actorLevel, level)) { sendMessage(peerId, "Недостаточно прав."); return; }
        target.level = level;
        persistAdmin(user.id, target.level, target.nickname);
        sendMessage(peerId, "✅ Уровень обновлён: " + user.mention + " -> " + level);
    }

    private void handleAdminRemove(int peerId, int actorLevel, String text) {
        String[] p = text.split("\\s+");
        if (p.length != 3) { sendMessage(peerId, "Использование: !admin remove @id123"); return; }
        UserProfile user = resolveUser(p[2]);
        if (user == null || !admins.containsKey(Long.valueOf(user.id))) { sendMessage(peerId, "Администратор не найден."); return; }

        AdminData target = admins.get(Long.valueOf(user.id));
        if (!canModifyTarget(actorLevel, target.level)) { sendMessage(peerId, "Слишком низкий уровень администратора."); return; }

        admins.remove(Long.valueOf(user.id));
        plugin.getConfig().set("vk.admins." + user.id, null);
        plugin.saveConfig();

        for (Integer chatId : chatIds.values()) {
            kickFromChat(user.id, chatId.intValue());
        }

        if (!target.nickname.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + target.nickname + " parent set default");
                }
            });
        }

        sendMessage(peerId, "🗑 Удалено: " + user.mention + " (из всех бесед бота)");
    }

    private void handleRnick(int peerId, String text) {
        String[] p = text.split("\\s+");
        if (p.length != 3) { sendMessage(peerId, "Использование: !rnick @id123 НИК"); return; }
        UserProfile user = resolveUser(p[1]);
        if (user == null) { sendMessage(peerId, "Пользователь не найден."); return; }

        nicknames.put(Long.valueOf(user.id), p[2]);
        persistNickname(user.id, p[2]);

        AdminData admin = admins.get(Long.valueOf(user.id));
        if (admin != null) {
            admin.nickname = p[2];
            persistAdmin(user.id, admin.level, admin.nickname);
        }

        sendMessage(peerId, "✅ Ник обновлен: " + user.mention + " -> " + p[2]);
    }

    private void handleChatKick(int peerId, String text) {
        String[] p = text.split("\\s+", 3);
        if (p.length < 2) { sendMessage(peerId, "Использование: !kick @id123 причина"); return; }
        UserProfile user = resolveUser(p[1]);
        if (user == null) { sendMessage(peerId, "Пользователь не найден."); return; }

        boolean ok = kickFromChat(user.id, chatIds.get(ChatType.ADMMANAGE).intValue());
        if (ok) sendMessage(peerId, "✅ Кик: " + user.mention + ". Причина: " + (p.length > 2 ? p[2] : "не указана"));
        else sendMessage(peerId, "Не удалось кикнуть " + user.mention);
    }

    private void handleChatBan(int peerId, String text) {
        String[] p = text.split("\\s+");
        if (p.length < 2) { sendMessage(peerId, "Использование: !ban @id123 причина [1d]"); return; }
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

        chatBans.put(Long.valueOf(user.id), new ChatBan(until, reason));
        persistChatBan(user.id, until, reason);
        kickFromChat(user.id, chatIds.get(ChatType.ADMMANAGE).intValue());
        sendMessage(peerId, "✅ Бан беседы: " + user.mention + (until > 0 ? " до " + formatLastSeen(until, false) : " навсегда"));
    }

    private void handleChatUnban(int peerId, String text) {
        String[] p = text.split("\\s+");
        if (p.length != 2) { sendMessage(peerId, "Использование: !unban @id123"); return; }
        UserProfile user = resolveUser(p[1]);
        if (user == null) { sendMessage(peerId, "Пользователь не найден."); return; }

        chatBans.remove(Long.valueOf(user.id));
        plugin.getConfig().set("vk.chat-bans." + user.id, null);
        plugin.saveConfig();
        sendMessage(peerId, "✅ Разбан беседы: " + user.mention);
    }

    private void handleNoName(int peerId) {
        try {
            int chatId = chatIds.get(ChatType.ADMMANAGE).intValue();
            JsonObject response = callVkMethod("messages.getConversationMembers", mapOf("peer_id", String.valueOf(toPeerId(chatId))));
            JsonObject obj = response.getAsJsonObject("response");
            if (obj == null) { sendMessage(peerId, "Не удалось получить участников беседы."); return; }

            JsonArray items = obj.getAsJsonArray("items");
            if (items == null || items.size() == 0) { sendMessage(peerId, "Участники беседы не найдены."); return; }

            List<String> missing = new ArrayList<String>();
            for (int i = 0; i < items.size(); i++) {
                long memberId = items.get(i).getAsJsonObject().get("member_id").getAsLong();
                if (memberId <= 0) continue;
                if (!nicknames.containsKey(Long.valueOf(memberId))) missing.add(mentionById(memberId));
            }

            if (missing.isEmpty()) sendMessage(peerId, "✅ У всех участников есть привязка ника.");
            else sendMessage(peerId, "Без ника:\n" + String.join("\n", missing));
        } catch (Exception e) {
            sendMessage(peerId, "Ошибка VK API при !noname");
        }
    }

    private void handleCmd(int peerId, int actorLevel, String command) {
        String deny = commandPolicy.denyReason(command, actorLevel);
        if (deny != null) {
            sendMessage(peerId, deny);
            if (deny.contains("Абсолютный")) sendToChat(ChatType.EVENTS, "🚫 raw-blocked: /" + command);
            return;
        }

        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
        });

        sendMessage(peerId, "✅ Команда отправлена.");
        sendToChat(ChatType.EVENTS, "⚙ raw: /" + command + " [" + rawRole + "]");
    }

    private void queuePending(UUID uuid, int ticketId, String text) {
        if (uuid == null) return;
        Deque<PendingMessage> queue = pendingByUuid.get(uuid);
        if (queue == null) {
            queue = new ArrayDeque<PendingMessage>();
            pendingByUuid.put(uuid, queue);
        }

        for (PendingMessage msg : queue) {
            if (msg.ticketId == ticketId && msg.body.equals(text)) return;
        }

        queue.addLast(new PendingMessage(ticketId, text, System.currentTimeMillis()));
    }

    private void deliverPending(UUID uuid, String playerName) {
        Deque<PendingMessage> queue = pendingByUuid.remove(uuid);
        if (queue == null || queue.isEmpty()) return;

        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;

        while (!queue.isEmpty()) {
            PendingMessage msg = queue.removeFirst();
            player.sendMessage("[Support] #" + msg.ticketId + " " + msg.body);
        }

        notifyEvent("Pending доставлен для " + playerName + " (" + queue.size() + ")");
    }

    private void cleanupRuntimeData() {
        Instant commandBorder = Instant.now().minus(retentionCommandDays, ChronoUnit.DAYS);
        for (Deque<CommandLogEntry> queue : commandLog.values()) {
            while (!queue.isEmpty() && queue.peekFirst().time.isBefore(commandBorder)) queue.removeFirst();
        }

        long pendingBorder = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionPendingDays);
        for (Deque<PendingMessage> queue : pendingByUuid.values()) {
            while (!queue.isEmpty() && queue.peekFirst().createdAtMs < pendingBorder) queue.removeFirst();
        }

        long closeBorder = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionClosedTicketsDays);
        List<Integer> remove = new ArrayList<Integer>();
        for (Ticket ticket : tickets.values()) {
            if (ticket.status == TicketStatus.CLOSED && ticket.updatedAtMs < closeBorder) remove.add(Integer.valueOf(ticket.id));
        }
        for (Integer id : remove) tickets.remove(id);
    }

    private Ticket findTicket(String rawId) {
        try {
            return tickets.get(Integer.valueOf(Integer.parseInt(rawId)));
        } catch (Exception e) {
            return null;
        }
    }

    private int countTicketsByAuthor(String author) {
        int count = 0;
        for (Ticket ticket : tickets.values()) {
            if (ticket.author.equalsIgnoreCase(author)) count++;
        }
        return count;
    }

    private String formatLastSeen(long epochMillis, boolean online) {
        if (online) return "сейчас";
        if (epochMillis <= 0) return "неизвестно";

        Instant when = Instant.ofEpochMilli(epochMillis);
        Instant now = Instant.now();
        long minutes = Duration.between(when, now).toMinutes();

        if (minutes < 1) return "только что";
        if (minutes < 60) return minutes + " минут назад";

        ZonedDateTime z = when.atZone(ZoneId.systemDefault());
        LocalDate date = z.toLocalDate();
        LocalDate today = LocalDate.now();
        LocalTime time = z.toLocalTime().truncatedTo(ChronoUnit.MINUTES);

        if (date.equals(today)) return "сегодня в " + padTime(time);
        if (date.equals(today.minusDays(1))) return "вчера в " + padTime(time);

        long days = ChronoUnit.DAYS.between(date, today);
        return days + " дня назад";
    }

    private String formatAgo(Instant instant) {
        long minutes = Duration.between(instant, Instant.now()).toMinutes();
        if (minutes < 1) return "сейчас";
        if (minutes < 60) return minutes + "м назад";
        long hours = minutes / 60;
        if (hours < 24) return hours + "ч назад";
        long days = hours / 24;
        return days + "д назад";
    }

    private String padTime(LocalTime time) {
        String hh = time.getHour() < 10 ? "0" + time.getHour() : String.valueOf(time.getHour());
        String mm = time.getMinute() < 10 ? "0" + time.getMinute() : String.valueOf(time.getMinute());
        return hh + ":" + mm;
    }

    private String formatDuration(long millis) {
        long totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millis);
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        return hours + "ч " + minutes + "м";
    }

    private long onlineForDays(UUID uuid, int days) {
        LocalDate from = LocalDate.now().minusDays(days - 1L);
        long total = 0L;

        NavigableMap<LocalDate, Long> stats = onlineStats.get(uuid);
        if (stats != null) {
            for (Map.Entry<LocalDate, Long> e : stats.tailMap(from, true).entrySet()) {
                total += e.getValue().longValue();
            }
        }

        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            Long start = onlineSince.get(uuid);
            if (start != null) total += Math.max(0, System.currentTimeMillis() - start.longValue());
        }

        return total;
    }

    private boolean kickFromChat(long userId, int chatId) {
        if (chatId <= 0) return false;
        try {
            callVkMethod("messages.removeChatUser", mapOf("chat_id", String.valueOf(chatId), "member_id", String.valueOf(userId)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void sendToChat(ChatType chatType, String message) {
        Integer chatId = chatIds.get(chatType);
        if (chatId == null) return;
        int peerId = toPeerId(chatId.intValue());
        if (peerId > 0) sendMessage(peerId, message);
    }

    private void sendMessage(int peerId, String message) {
        if (accessToken == null || accessToken.isEmpty()) return;
        try {
            Map<String, String> params = new HashMap<String, String>();
            params.put("peer_id", String.valueOf(peerId));
            params.put("random_id", String.valueOf(UUID.randomUUID().hashCode()));
            params.put("message", message);
            callVkMethod("messages.send", params);
        } catch (Exception ignored) {
        }
    }

    private JsonObject callVkMethod(String method, Map<String, String> params) throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder("https://api.vk.com/method/").append(method).append('?');
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.charAt(sb.length() - 1) != '?') sb.append('&');
            sb.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        if (sb.charAt(sb.length() - 1) != '?') sb.append('&');
        sb.append("access_token=").append(encode(accessToken)).append("&v=").append(encode(apiVersion));

        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(sb.toString())).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        JsonObject body = gson.fromJson(response.body(), JsonObject.class);
        if (body != null && body.has("error")) throw new IllegalStateException(body.get("error").toString());
        return body;
    }

    private UserProfile resolveUser(String token) {
        UserProfile mention = parseByMention(token);
        if (mention != null) return mention;
        return loadUser(token.startsWith("id") ? token : token.replace("@", ""));
    }

    private UserProfile parseByMention(String mention) {
        Matcher m = VK_MENTION_PATTERN.matcher(mention.trim());
        if (!m.matches()) return null;
        if (m.group(1) != null && m.group(2) != null) return loadUser("id" + m.group(2));
        if (m.group(3) != null) return loadUser(m.group(3));
        return null;
    }

    private UserProfile loadUser(String idOrScreen) {
        try {
            JsonObject response = callVkMethod("users.get", mapOf("user_ids", idOrScreen));
            JsonArray arr = response.getAsJsonArray("response");
            if (arr == null || arr.size() == 0) return null;
            JsonObject u = arr.get(0).getAsJsonObject();
            long id = u.get("id").getAsLong();
            String first = u.has("first_name") ? u.get("first_name").getAsString() : "VK";
            String last = u.has("last_name") ? u.get("last_name").getAsString() : "User";
            String screen = u.has("screen_name") ? u.get("screen_name").getAsString() : "id" + id;
            return new UserProfile(id, "[https://vk.com/" + screen + "|" + first + " " + last + "]");
        } catch (Exception e) {
            return null;
        }
    }

    private String mentionById(long id) {
        UserProfile p = loadUser("id" + id);
        return p == null ? "@id" + id : p.mention;
    }

    private String formatAdmins() {
        if (admins.isEmpty()) return "Администраторы не настроены.";
        List<String> rows = new ArrayList<String>();
        rows.add("👥 Администраторы");
        for (Map.Entry<Long, AdminData> e : admins.entrySet()) {
            rows.add(mentionById(e.getKey().longValue()) + " — " + e.getValue().nickname + " (lvl " + e.getValue().level + ")");
        }
        return String.join("\n", rows);
    }

    private boolean canAssignLevel(int actor, int target) { return actor >= 4 || (actor >= 3 && target == 1); }
    private boolean canModifyTarget(int actor, int target) { return actor >= 4 || actor > target; }
    private int parseLevel(String raw, int def) { try { return clampLevel(Integer.parseInt(raw)); } catch (Exception e) { return def; } }
    private int clampLevel(int value) { return Math.max(1, Math.min(4, value)); }

    private void persistAdmin(long id, int level, String nick) {
        String base = "vk.admins." + id;
        plugin.getConfig().set(base + ".level", level);
        plugin.getConfig().set(base + ".nickname", nick);
        plugin.saveConfig();
    }

    private void persistNickname(long id, String nick) {
        plugin.getConfig().set("vk.nicknames." + id, nick);
        plugin.saveConfig();
    }

    private void persistChatBan(long id, long until, String reason) {
        String base = "vk.chat-bans." + id;
        plugin.getConfig().set(base + ".until-epoch-ms", until);
        plugin.getConfig().set(base + ".reason", reason);
        plugin.saveConfig();
    }

    private String joinRange(String[] arr, int from, int to) {
        if (from >= to) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private String tailAfterFirstSpace(String input) {
        int idx = input.indexOf(' ');
        if (idx < 0 || idx + 1 >= input.length()) return "";
        return input.substring(idx + 1);
    }

    private int toPeerId(int chatId) {
        return chatId <= 0 ? 0 : PEER_CHAT_BASE + chatId;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private boolean isDuplicate(String key, long thresholdMs) {
        long now = System.currentTimeMillis();
        Long prev = antiSpam.get(key);
        antiSpam.put(key, now);
        return prev != null && (now - prev.longValue()) <= thresholdMs;
    }

    private Map<String, String> mapOf(String... kv) {
        Map<String, String> out = new HashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put(kv[i], kv[i + 1]);
        }
        return out;
    }

    private enum ChatType { ADMMANAGE, EVENTS, SUPPORT, MODMANAGE }
    private enum TicketCategory { SUPPORT, REPORT }
    private enum TicketStatus { OPEN, IN_PROGRESS, WAITING_PLAYER, CLOSED }

    private static class AdminData {
        private int level;
        private String nickname;
        private AdminData(int level, String nickname) { this.level = level; this.nickname = nickname; }
    }

    private static class ChatBan {
        private final long untilEpochMs;
        private final String reason;
        private ChatBan(long untilEpochMs, String reason) { this.untilEpochMs = untilEpochMs; this.reason = reason; }
    }

    private static class UserProfile {
        private final long id;
        private final String mention;
        private UserProfile(long id, String mention) { this.id = id; this.mention = mention; }
    }

    private static class PendingMessage {
        private final int ticketId;
        private final String body;
        private final long createdAtMs;
        private PendingMessage(int ticketId, String body, long createdAtMs) { this.ticketId = ticketId; this.body = body; this.createdAtMs = createdAtMs; }
    }

    private static class CommandLogEntry {
        private final Instant time;
        private final String command;
        private CommandLogEntry(Instant time, String command) { this.time = time; this.command = command; }
    }

    private static class StaffTicketStats {
        private int replies;
        private int punishments;
        private int revertedPunishments;
        private int firstResponseMinutes;
    }

    private static class StaffAggregation {
        private int supportClosed;
        private int reportClosed;
        private int replies;
        private int punishments;
        private int revertedPunishments;
        private int firstResponseMinutes;
        private String recommendation = "Нужно больше данных";
    }

    private static class Ticket {
        private final int id;
        private TicketCategory category;
        private TicketStatus status;
        private final String author;
        private final List<String> history;
        private String assignedVkId;
        private final long createdAtMs;
        private long updatedAtMs;
        private final StaffTicketStats staffStats = new StaffTicketStats();

        private Ticket(int id, TicketCategory category, String author, String body) {
            this.id = id;
            this.category = category;
            this.status = TicketStatus.OPEN;
            this.author = author;
            this.assignedVkId = "-";
            this.history = new ArrayList<String>();
            this.createdAtMs = System.currentTimeMillis();
            this.updatedAtMs = this.createdAtMs;
            this.history.add("📝 " + body);
        }

        private void touch() {
            this.updatedAtMs = System.currentTimeMillis();
            if (staffStats.firstResponseMinutes <= 0 && history.size() > 1) {
                staffStats.firstResponseMinutes = (int) Math.max(1L, Duration.ofMillis(updatedAtMs - createdAtMs).toMinutes());
            }
        }

        private String formatCard() {
            List<String> rows = new ArrayList<String>();
            rows.add("🎫 Тикет #" + id);
            rows.add("Категория: " + category.name().toLowerCase(Locale.ROOT));
            rows.add("Статус: " + status);
            rows.add("Автор: " + author);
            rows.add("Ответственный: " + assignedVkId);
            rows.add("История:");

            int start = Math.max(0, history.size() - 5);
            for (int i = start; i < history.size(); i++) {
                rows.add(history.get(i));
            }
            return String.join("\n", rows);
        }
    }

    private static class BotAccessPolicy {
        private final Map<String, Integer> levels;
        private BotAccessPolicy(Map<String, Integer> levels) { this.levels = levels; }
        private boolean hasAccess(int actorLevel, String key) { return actorLevel >= (levels.containsKey(key) ? levels.get(key).intValue() : 4); }
    }

    private static class CommandPolicy {
        private final boolean enabled;
        private final String mode;
        private final Set<String> blacklist;
        private final Set<String> whitelist;
        private final Set<String> absoluteBlacklist;
        private final int chiefBypassLevel;

        private CommandPolicy(boolean enabled, String mode, Set<String> blacklist, Set<String> whitelist, Set<String> absoluteBlacklist, int chiefBypassLevel) {
            this.enabled = enabled;
            this.mode = mode;
            this.blacklist = blacklist;
            this.whitelist = whitelist;
            this.absoluteBlacklist = absoluteBlacklist;
            this.chiefBypassLevel = chiefBypassLevel;
        }

        private static CommandPolicy defaultPolicy() {
            return new CommandPolicy(
                    true,
                    "blacklist",
                    new HashSet<String>(Arrays.asList("op", "deop", "stop", "reload")),
                    new HashSet<String>(),
                    new HashSet<String>(Arrays.asList("stop", "reload confirm")),
                    4);
        }

        private String denyReason(String command, int actorLevel) {
            String cmd = command.trim().toLowerCase(Locale.ROOT);
            if (cmd.isEmpty()) return "Пустая команда.";
            if (startsWithAny(cmd, absoluteBlacklist)) return "⛔ Абсолютный запрет для этой команды.";
            if (!enabled) return null;
            if ("blacklist".equals(mode) && startsWithAny(cmd, blacklist)) return "Команда в blacklist.";
            if ("whitelist".equals(mode) && actorLevel < chiefBypassLevel && !startsWithAny(cmd, whitelist)) return "Команда не в whitelist.";
            return null;
        }

        private boolean startsWithAny(String command, Set<String> prefixes) {
            for (String prefix : prefixes) {
                if (command.startsWith(prefix)) return true;
            }
            return false;
        }
    }
}
