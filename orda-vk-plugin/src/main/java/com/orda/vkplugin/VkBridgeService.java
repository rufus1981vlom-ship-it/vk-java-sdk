package com.orda.vkplugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

    private final Map<ChatType, Integer> chatIds = new EnumMap<>(ChatType.class);
    private Map<Long, AdminData> admins = new HashMap<>();
    private Map<Long, String> nicknames = new HashMap<>();
    private Map<Long, ChatBan> chatBans = new HashMap<>();

    private CommandPolicy commandPolicy;
    private BotAccessPolicy botAccessPolicy;

    private String lpServer;
    private String lpKey;
    private String lpTs;

    private final Map<Integer, Ticket> tickets = new LinkedHashMap<>();
    private int nextTicketId = 1;

    private final Map<UUID, Long> onlineSince = new HashMap<>();
    private final Map<UUID, Long> lastSeen = new HashMap<>();
    private final Map<UUID, NavigableMap<LocalDate, Long>> onlineStats = new HashMap<>();
    private final Map<String, Deque<CommandLogEntry>> commandLog = new HashMap<>();
    private final Map<UUID, Deque<PendingMessage>> pendingByUuid = new HashMap<>();

    private final List<AuditRecord> auditLog = new ArrayList<>();
    private final List<DisciplineRecord> discipline = new ArrayList<>();
    private final List<PunishmentRecord> punishments = new ArrayList<>();

    private final Map<String, String> quickReplyTemplates = new HashMap<>();
    private final Map<String, String> mutePresets = new HashMap<>();
    private final Map<String, String> banPresets = new HashMap<>();
    private final Map<String, String> warnPresets = new HashMap<>();

    private final Map<String, Long> antiSpam = new HashMap<>();

    private int retentionCommandDays = 7;
    private int retentionPendingDays = 7;
    private int retentionClosedTicketsDays = 14;

    private File dbFile;

    public VkBridgeService(OrdaVkPlugin plugin, Gson gson) {
        this.plugin = plugin;
        this.gson = gson;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public void start() {
        reloadState();
        ensureDatabase();
        loadRuntimeDataFromDb();
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::pollLoop, 1, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        running = false;
        if (executor != null) executor.shutdownNow();
    }

    public void notifyEvent(String message) {
        if (isDuplicate("evt:" + message, 1000L)) return;
        sendToChat(ChatType.EVENTS, "🛰 " + message);
    }

    public void onPlayerJoin(Player player) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        onlineSince.put(uuid, now);
        lastSeen.put(uuid, now);
        deliverPending(uuid);
    }

    public void onPlayerQuit(Player player) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        Long start = onlineSince.remove(uuid);
        if (start != null) {
            long delta = Math.max(0, now - start);
            onlineStats.computeIfAbsent(uuid, k -> new TreeMap<>()).merge(LocalDate.now(), delta, Long::sum);
        }
        lastSeen.put(uuid, now);
    }

    public void onPlayerCommand(String actor, String raw) {
        String command = raw.startsWith("/") ? raw : "/" + raw;
        String lower = command.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("/reg") && !lower.startsWith("/l")) {
            commandLog.computeIfAbsent(actor.toLowerCase(Locale.ROOT), k -> new ArrayDeque<>()).addLast(new CommandLogEntry(Instant.now(), command));
        }

        if (lower.startsWith("/ac ") || lower.startsWith("/helpop ")) {
            createTicket(TicketCategory.SUPPORT, actor, tailAfterSpace(command), true);
        } else if (lower.startsWith("/report ") || lower.startsWith("/rep ")) {
            createTicket(TicketCategory.REPORT, actor, tailAfterSpace(command), true);
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
        chatIds.put(ChatType.ADMMANAGE, plugin.getConfig().getInt("vk.chats.ADMMANAGE", legacyCommand));
        chatIds.put(ChatType.EVENTS, plugin.getConfig().getInt("vk.chats.EVENTS", legacyEvents));
        chatIds.put(ChatType.SUPPORT, plugin.getConfig().getInt("vk.chats.SUPPORT", legacyCommand));
        chatIds.put(ChatType.MODMANAGE, plugin.getConfig().getInt("vk.chats.MODMANAGE", legacyCommand));

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
        quickReplyTemplates.put("accepted", "Принято, уже в работе.");
        quickReplyTemplates.put("checking", "Проверяем, дайте пару минут.");
        quickReplyTemplates.put("needproof", "Нужны дополнительные доказательства.");
        quickReplyTemplates.put("closed", "Кейс закрыт. Спасибо за обращение.");

        ConfigurationSection templates = plugin.getConfig().getConfigurationSection("vk.reply-templates");
        if (templates != null) for (String key : templates.getKeys(false)) quickReplyTemplates.put(key.toLowerCase(Locale.ROOT), templates.getString(key, ""));

        fillPreset(mutePresets, "vk.punishment-presets.mute", "flood", "10m Flood");
        fillPreset(banPresets, "vk.punishment-presets.ban", "cheat", "7d Cheat client");
        fillPreset(warnPresets, "vk.punishment-presets.warn", "tox", "Toxic behavior");
    }

    private void fillPreset(Map<String, String> target, String path, String defaultKey, String defaultValue) {
        target.clear();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection(path);
        if (sec != null) for (String key : sec.getKeys(false)) target.put(key.toLowerCase(Locale.ROOT), sec.getString(key, ""));
        if (!target.containsKey(defaultKey)) target.put(defaultKey, defaultValue);
    }

    private void loadRetention() {
        retentionCommandDays = Math.max(1, plugin.getConfig().getInt("vk.retention.command-log-days", 7));
        retentionPendingDays = Math.max(1, plugin.getConfig().getInt("vk.retention.pending-days", 7));
        retentionClosedTicketsDays = Math.max(1, plugin.getConfig().getInt("vk.retention.closed-ticket-days", 14));
    }

    private void loadAdmins() {
        admins = new HashMap<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("vk.admins");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                long id = Long.parseLong(key);
                String base = "vk.admins." + key;
                admins.put(id, new AdminData(clampLevel(plugin.getConfig().getInt(base + ".level", 1)), plugin.getConfig().getString(base + ".nickname", "").trim()));
            } catch (Exception ignored) {
            }
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
            } catch (Exception ignored) {
            }
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
                chatBans.put(id, new ChatBan(plugin.getConfig().getLong(base + ".until-epoch-ms", 0), plugin.getConfig().getString(base + ".reason", "")));
            } catch (Exception ignored) {
            }
        }
    }

    private CommandPolicy loadCommandPolicy() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("vk.command-policy");
        if (sec == null) return CommandPolicy.defaultPolicy();
        return new CommandPolicy(sec.getBoolean("enabled", true),
                sec.getString("mode", "blacklist").toLowerCase(Locale.ROOT),
                normalizeSet(sec.getStringList("blacklist")),
                normalizeSet(sec.getStringList("whitelist")),
                normalizeSet(sec.getStringList("absolute-blacklist")),
                sec.getInt("chief-bypass-level", 4));
    }

    private BotAccessPolicy loadBotAccessPolicy() {
        Map<String, Integer> defaults = new HashMap<>();
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
        defaults.put("audit", 3);
        defaults.put("discipline", 3);

        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("vk.bot-command-access");
        if (sec == null) return new BotAccessPolicy(defaults);
        Map<String, Integer> out = new HashMap<>(defaults);
        for (String key : sec.getKeys(false)) out.put(key, clampLevel(sec.getInt(key, out.getOrDefault(key, 4))));
        return new BotAccessPolicy(out);
    }

    private Set<String> normalizeSet(List<String> list) {
        Set<String> out = new HashSet<>();
        for (String entry : list) if (entry != null && !entry.trim().isEmpty()) out.add(entry.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    private void pollLoop() {
        if (!running || accessToken == null || accessToken.isEmpty() || groupId <= 0) return;
        try {
            ensureLongPoll();
            String url = lpServer + "?act=a_check&key=" + encode(lpKey) + "&ts=" + encode(lpTs) + "&wait=25&version=3";
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(35)).GET().build(), HttpResponse.BodyHandlers.ofString());
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
            for (int i = 0; i < updates.size(); i++) onUpdate(updates.get(i).getAsJsonObject());
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
        JsonObject message = update.getAsJsonObject("object").getAsJsonObject("message");
        if (message == null) return;
        int peerId = message.get("peer_id").getAsInt();
        if (!isKnownPeer(peerId)) return;

        processInviteAction(message, peerId);

        String text = message.has("text") ? message.get("text").getAsString().trim() : "";
        if (text.isEmpty()) return;

        AdminData actor = admins.get(message.get("from_id").getAsLong());
        if (actor == null) {
            sendMessage(peerId, "⛔ Нет доступа.");
            return;
        }
        handleCommand(peerId, actor.level, actor.nickname, text);
    }

    private boolean isKnownPeer(int peerId) {
        for (Integer chatId : chatIds.values()) if (toPeerId(chatId) == peerId) return true;
        return false;
    }

    private void processInviteAction(JsonObject message, int peerId) {
        JsonObject action = message.getAsJsonObject("action");
        if (action == null) return;
        if (!"chat_invite_user".equals(action.has("type") ? action.get("type").getAsString() : "")) return;
        long invitedId = action.has("member_id") ? action.get("member_id").getAsLong() : 0;
        ChatBan ban = chatBans.get(invitedId);
        if (ban == null) return;
        if (ban.untilEpochMs > 0 && ban.untilEpochMs < System.currentTimeMillis()) {
            chatBans.remove(invitedId);
            plugin.getConfig().set("vk.chat-bans." + invitedId, null);
            plugin.saveConfig();
            return;
        }
        kickFromChat(invitedId, peerId - PEER_CHAT_BASE);
        sendMessage(peerId, "⛔ " + mentionById(invitedId) + " в бане чата.");
    }

    private void handleCommand(int peerId, int actorLevel, String actorNick, String text) {
        String lower = text.toLowerCase(Locale.ROOT);

        if (lower.equals("!help") || lower.equals("!help support") || lower.equals("!help mod") || lower.equals("!help admin") || lower.equals("!help punish") || lower.equals("!help audit")) { sendMessage(peerId, formatHelp(actorLevel, lower)); return; }

        if (lower.equals("!admins") || lower.equals("!админы")) { requireAndRun(peerId, actorLevel, "admins", () -> sendMessage(peerId, formatAdmins())); return; }

        if (lower.startsWith("!tickets")) { requireAndRun(peerId, actorLevel, "tickets", () -> handleTickets(peerId, actorNick, text)); return; }
        if (lower.startsWith("!ticket ")) { requireAndRun(peerId, actorLevel, "tickets", () -> handleTicket(peerId, text)); return; }
        if (lower.startsWith("!take ")) { requireAndRun(peerId, actorLevel, "tickets", () -> handleTake(peerId, actorNick, text)); return; }
        if (lower.startsWith("!unassign ")) { requireAndRun(peerId, actorLevel, "tickets", () -> handleUnassign(peerId, actorNick, text)); return; }
        if (lower.startsWith("!replyclose ")) { requireAndRun(peerId, actorLevel, "tickets", () -> handleReplyClose(peerId, actorNick, text)); return; }
        if (lower.startsWith("!reply ")) { requireAndRun(peerId, actorLevel, "tickets", () -> handleReply(peerId, actorNick, text)); return; }
        if (lower.startsWith("!assign ")) { requireAndRun(peerId, actorLevel, "tickets", () -> handleAssign(peerId, actorNick, text)); return; }
        if (lower.startsWith("!close ")) { requireAndRun(peerId, actorLevel, "tickets", () -> handleClose(peerId, actorNick, text)); return; }
        if (lower.startsWith("!reopen ")) { requireAndRun(peerId, actorLevel, "tickets", () -> handleReopen(peerId, actorNick, text)); return; }
        if (lower.startsWith("!move ")) { requireAndRun(peerId, actorLevel, "tickets", () -> handleMove(peerId, actorNick, text)); return; }
        if (lower.startsWith("!rlist")) { requireAndRun(peerId, actorLevel, "tickets", () -> sendMessage(peerId, "📚 Шаблоны: " + String.join(", ", quickReplyTemplates.keySet()))); return; }
        if (lower.startsWith("!r ")) { requireAndRun(peerId, actorLevel, "tickets", () -> handleTemplateReply(peerId, actorNick, text)); return; }

        if (lower.startsWith("!check tech ")) { requireAndRun(peerId, actorLevel, "tickets", () -> handleCheckTech(peerId, text)); return; }
        if (lower.startsWith("!check ")) { requireAndRun(peerId, actorLevel, "tickets", () -> handleCheck(peerId, text)); return; }
        if (lower.startsWith("!lookup ")) { requireAndRun(peerId, actorLevel, "tickets", () -> handleLookup(peerId, text)); return; }
        if (lower.startsWith("!staffstats ")) { requireAndRun(peerId, actorLevel, "tickets", () -> handleStaffStats(peerId, text)); return; }

        if (lower.equals("!audit") || lower.startsWith("!audit ")) { requireAndRun(peerId, actorLevel, "audit", () -> handleAudit(peerId, text)); return; }
        if (lower.startsWith("!staffnote ") || lower.startsWith("!staffwarn ") || lower.startsWith("!staffreprimand ") || lower.startsWith("!staffdiscipline ") || lower.startsWith("!staffforgive ")) {
            requireAndRun(peerId, actorLevel, "discipline", () -> handleDiscipline(peerId, actorNick, text));
            return;
        }

        if (lower.startsWith("!punishlog ")) { requireAndRun(peerId, actorLevel, "punish", () -> handlePunishLog(peerId, text)); return; }
        if (lower.startsWith("!unmute ")) { requireAndRun(peerId, actorLevel, "punish", () -> handleReversePunish(peerId, actorNick, text, "unmute")); return; }
        if (lower.startsWith("!pardon ")) { requireAndRun(peerId, actorLevel, "punish", () -> handleReversePunish(peerId, actorNick, text, "pardon")); return; }
        if (lower.startsWith("!unwarn ")) { requireAndRun(peerId, actorLevel, "punish", () -> handleReversePunish(peerId, actorNick, text, "unwarn")); return; }

        if (lower.startsWith("!vkick ")) { requireAndRun(peerId, actorLevel, "chat_kick", () -> handleChatKick(peerId, text.replaceFirst("(?i)!vkick", "!kick"))); return; }
        if (lower.startsWith("!vkban ")) { requireAndRun(peerId, actorLevel, "chat_ban", () -> handleChatBan(peerId, text.replaceFirst("(?i)!vkban", "!ban"))); return; }
        if (lower.startsWith("!vkunban ")) { requireAndRun(peerId, actorLevel, "chat_unban", () -> handleChatUnban(peerId, text.replaceFirst("(?i)!vkunban", "!unban"))); return; }

        if (lower.startsWith("!admin set") || lower.startsWith("!admin сет")) { requireAndRun(peerId, actorLevel, "admin_set", () -> handleAdminSet(peerId, actorLevel, actorNick, text)); return; }
        if (lower.startsWith("!admin level") || lower.startsWith("!admin уровень") || lower.startsWith("!admin левел")) { requireAndRun(peerId, actorLevel, "admin_level", () -> handleAdminLevel(peerId, actorLevel, actorNick, text)); return; }
        if (lower.startsWith("!admin remove") || lower.startsWith("!admin удалить")) { requireAndRun(peerId, actorLevel, "admin_remove", () -> handleAdminRemove(peerId, actorLevel, actorNick, text)); return; }
        if (lower.startsWith("!rnick") || lower.startsWith("!рник")) { requireAndRun(peerId, actorLevel, "rnick", () -> handleRnick(peerId, actorNick, text)); return; }

        if (lower.startsWith("!kick") || lower.startsWith("!кик")) { requireAndRun(peerId, actorLevel, "chat_kick", () -> handleChatKick(peerId, text)); return; }
        if (lower.startsWith("!unban") || lower.startsWith("!разбан")) { requireAndRun(peerId, actorLevel, "chat_unban", () -> handleChatUnban(peerId, text)); return; }
        if (lower.startsWith("!ban") || lower.startsWith("!бан")) { requireAndRun(peerId, actorLevel, "chat_ban", () -> handleBan(peerId, actorNick, text)); return; }
        if (lower.startsWith("!mute ")) { requireAndRun(peerId, actorLevel, "punish", () -> handlePunish(peerId, actorNick, text, "mute")); return; }
        if (lower.startsWith("!warn ")) { requireAndRun(peerId, actorLevel, "punish", () -> handlePunish(peerId, actorNick, text, "warn")); return; }
        if (lower.equals("!noname") || lower.equals("!ноунэйм")) { requireAndRun(peerId, actorLevel, "noname", () -> handleNoName(peerId)); return; }
        if (lower.startsWith("!cmd ")) { requireAndRun(peerId, actorLevel, "cmd", () -> handleCmd(peerId, actorLevel, actorNick, text.substring(5).trim())); return; }

        sendMessage(peerId, "⛔ Неизвестная команда. !help");
    }

    private void requireAndRun(int peerId, int actorLevel, String key, Runnable action) {
        if (!botAccessPolicy.hasAccess(actorLevel, key)) {
            sendMessage(peerId, "⛔ Недостаточно прав.");
            return;
        }
        action.run();
    }

    private String formatHelp(int level, String mode) {
        List<String> rows = new ArrayList<>();
        rows.add("🧭 OrdaVK PRO menu");
        if (mode.equals("!help") || mode.equals("!help support")) rows.add("Support: !tickets !ticket !reply !close !take");
        if (mode.equals("!help") || mode.equals("!help mod")) rows.add("Mod: !move !assign !unassign !reopen !r !rlist !check !lookup");
        if (mode.equals("!help") || mode.equals("!help punish")) rows.add("Punish: !mute !ban !warn !unmute !pardon !punishlog");
        if (mode.equals("!help") || mode.equals("!help audit")) rows.add("Audit: !audit recent|ticket|player|punish, discipline commands");
        if ((mode.equals("!help") || mode.equals("!help admin")) && level >= 3) rows.add("Admin: !admin set/level/remove !rnick !cmd !vkban/!vkick/!vkunban");
        if (mode.equals("!help admin") && level < 3) return "⛔ Раздел admin недоступен";
        return String.join("\n", rows);
    }

    private Ticket createTicket(TicketCategory category, String author, String body, boolean notify) {
        Ticket t = new Ticket(nextTicketId++, category, author, body);
        tickets.put(t.id, t);
        dbTicketUpsert(t);
        if (notify) sendToChat(category == TicketCategory.SUPPORT ? ChatType.SUPPORT : ChatType.MODMANAGE,
                "🎫 #" + t.id + " " + category.name().toLowerCase(Locale.ROOT) + " | " + author + "\n" + body);
        return t;
    }

    private void handleTickets(int peerId, String actorNick, String text) {
        String[] p = text.split("\\s+");
        String filter = p.length >= 2 ? p[1].toLowerCase(Locale.ROOT) : "open";

        List<Ticket> list = new ArrayList<>(tickets.values());
        list.removeIf(t -> t.status == TicketStatus.CLOSED);
        if (filter.equals("mine")) list.removeIf(t -> !actorNick.equalsIgnoreCase(t.assignedVkId));
        else if (filter.equals("unassigned")) list.removeIf(t -> !"-".equals(t.assignedVkId));
        else if (filter.equals("support")) list.removeIf(t -> t.category != TicketCategory.SUPPORT);
        else if (filter.equals("report")) list.removeIf(t -> t.category != TicketCategory.REPORT);
        else if (filter.equals("open")) { /*default*/ }

        list.sort(Comparator.comparingInt(t -> ticketPriority(t.status)));

        if (list.isEmpty()) { sendMessage(peerId, "🎫 Тикетов по фильтру нет."); return; }
        List<String> rows = new ArrayList<>();
        rows.add("🎫 Tickets [" + filter + "]");
        for (Ticket t : list) rows.add("#" + t.id + " | " + t.category.name().toLowerCase(Locale.ROOT) + " | " + t.status + " | @" + t.author + " | " + t.assignedVkId);
        sendMessage(peerId, String.join("\n", rows));
    }

    private int ticketPriority(TicketStatus s) {
        if (s == TicketStatus.OPEN) return 1;
        if (s == TicketStatus.WAITING_PLAYER) return 2;
        if (s == TicketStatus.IN_PROGRESS) return 3;
        return 4;
    }

    private void handleTicket(int peerId, String text) {
        String[] p = text.split("\\s+");
        boolean full = p.length == 3 && (p[1].equalsIgnoreCase("full") || p[2].equalsIgnoreCase("full"));
        String idRaw = p.length >= 2 ? (p[1].equalsIgnoreCase("full") ? p[2] : p[1]) : "";
        Ticket t = findTicket(idRaw);
        if (t == null) { sendMessage(peerId, "Тикет не найден"); return; }
        sendMessage(peerId, t.formatCard(full));
    }

    private void handleTake(int peerId, String actor, String text) {
        String[] p = text.split("\\s+");
        if (p.length != 2) { sendMessage(peerId, "Использование: !take <id>"); return; }
        Ticket t = findTicket(p[1]);
        if (t == null) { sendMessage(peerId, "Тикет не найден"); return; }
        String old = t.assignedVkId;
        t.assignedVkId = actor;
        t.history.add("👤 take: " + old + " -> " + actor);
        t.touch();
        dbTicketUpsert(t);
        audit("ticket", actor, "take #" + t.id + " " + old + "->" + actor);
        sendMessage(peerId, "✅ #" + t.id + " назначен на вас");
    }

    private void handleUnassign(int peerId, String actor, String text) {
        String[] p = text.split("\\s+");
        if (p.length != 2) { sendMessage(peerId, "Использование: !unassign <id>"); return; }
        Ticket t = findTicket(p[1]);
        if (t == null) { sendMessage(peerId, "Тикет не найден"); return; }
        String old = t.assignedVkId;
        t.assignedVkId = "-";
        t.history.add("👤 unassign: " + old + " -> -");
        t.touch();
        dbTicketUpsert(t);
        audit("ticket", actor, "unassign #" + t.id + " from " + old);
        sendMessage(peerId, "✅ Назначение снято: #" + t.id);
    }

    private void handleReply(int peerId, String actor, String text) {
        String[] p = text.split("\\s+", 3);
        if (p.length < 3) { sendMessage(peerId, "Использование: !reply <id> <text>"); return; }
        Ticket t = findTicket(p[1]);
        if (t == null) { sendMessage(peerId, "Тикет не найден"); return; }
        t.status = TicketStatus.IN_PROGRESS;
        t.history.add("↪ " + actor + ": " + p[2]);
        t.touch();
        t.staff.replies++;
        dbTicketUpsert(t);
        audit("ticket", actor, "reply #" + t.id);
        sendMessage(peerId, "✅ Ответ добавлен в #" + t.id);

        OfflinePlayer target = Bukkit.getOfflinePlayer(t.author);
        Player pl = target.getPlayer();
        if (pl != null && pl.isOnline()) pl.sendMessage("[Support] " + p[2]);
        else queuePending(target.getUniqueId(), t.id, p[2]);
    }

    private void handleReplyClose(int peerId, String actor, String text) {
        String[] p = text.split("\\s+", 3);
        if (p.length < 3) { sendMessage(peerId, "Использование: !replyclose <id> <text>"); return; }
        handleReply(peerId, actor, "!reply " + p[1] + " " + p[2]);
        handleClose(peerId, actor, "!close " + p[1] + " auto");
    }

    private void handleAssign(int peerId, String actor, String text) {
        String[] p = text.split("\\s+");
        if (p.length != 3) { sendMessage(peerId, "Использование: !assign <id> <vk_id>"); return; }
        Ticket t = findTicket(p[1]);
        if (t == null) { sendMessage(peerId, "Тикет не найден"); return; }
        String old = t.assignedVkId;
        t.assignedVkId = p[2];
        t.history.add("👤 assign: " + old + " -> " + p[2]);
        t.touch();
        dbTicketUpsert(t);
        audit("ticket", actor, "assign #" + t.id + " " + old + "->" + p[2]);
        sendMessage(peerId, "✅ #" + t.id + " назначен на " + p[2] + " (было: " + old + ")");
    }

    private void handleClose(int peerId, String actor, String text) {
        String[] p = text.split("\\s+", 3);
        if (p.length < 2) { sendMessage(peerId, "Использование: !close <id> [comment]"); return; }
        Ticket t = findTicket(p[1]);
        if (t == null) { sendMessage(peerId, "Тикет не найден"); return; }
        String comment = p.length >= 3 ? p[2] : "closed";
        t.status = TicketStatus.CLOSED;
        t.history.add("✅ close by " + actor + ": " + comment);
        t.touch();
        dbTicketUpsert(t);
        audit("ticket", actor, "close #" + t.id + " " + comment);
        sendMessage(peerId, "✅ Тикет #" + t.id + " закрыт");
    }

    private void handleReopen(int peerId, String actor, String text) {
        String[] p = text.split("\\s+");
        if (p.length != 2) { sendMessage(peerId, "Использование: !reopen <id>"); return; }
        Ticket t = findTicket(p[1]);
        if (t == null) { sendMessage(peerId, "Тикет не найден"); return; }
        t.status = TicketStatus.OPEN;
        t.history.add("♻ reopen by " + actor);
        t.touch();
        dbTicketUpsert(t);
        audit("ticket", actor, "reopen #" + t.id);
        sendMessage(peerId, "✅ Тикет #" + t.id + " открыт снова");
    }

    private void handleMove(int peerId, String actor, String text) {
        String[] p = text.split("\\s+", 4);
        if (p.length < 3) { sendMessage(peerId, "Использование: !move <id> support|report [--reason ...]"); return; }
        Ticket t = findTicket(p[1]);
        if (t == null) { sendMessage(peerId, "Тикет не найден"); return; }
        TicketCategory target = parseCategory(p[2]);
        if (target == null) { sendMessage(peerId, "Категория: support|report"); return; }
        TicketCategory from = t.category;
        if (from == target) { sendMessage(peerId, "Уже в этой категории"); return; }
        String reason = (p.length >= 4 && p[3].startsWith("--reason")) ? p[3].replaceFirst("--reason", "").trim() : "";
        t.category = target;
        t.history.add("🔁 move by " + actor + ": " + from.name().toLowerCase(Locale.ROOT) + " -> " + target.name().toLowerCase(Locale.ROOT) + (reason.isEmpty() ? "" : " | " + reason));
        t.touch();
        dbTicketUpsert(t);
        audit("ticket", actor, "move #" + t.id + " " + from + "->" + target + " " + reason);
        sendMessage(peerId, "✅ #" + t.id + " перенесён " + from.name().toLowerCase(Locale.ROOT) + " -> " + target.name().toLowerCase(Locale.ROOT));
        sendToChat(target == TicketCategory.SUPPORT ? ChatType.SUPPORT : ChatType.MODMANAGE,
                "🔁 #" + t.id + " перемещён сюда\nИз: " + from.name().toLowerCase(Locale.ROOT) + "\nВ: " + target.name().toLowerCase(Locale.ROOT));
    }

    private TicketCategory parseCategory(String value) {
        if ("support".equalsIgnoreCase(value)) return TicketCategory.SUPPORT;
        if ("report".equalsIgnoreCase(value)) return TicketCategory.REPORT;
        return null;
    }

    private void handleTemplateReply(int peerId, String actor, String text) {
        String[] p = text.split("\\s+");
        if (p.length != 3) { sendMessage(peerId, "Использование: !r <id> <template>"); return; }
        Ticket t = findTicket(p[1]);
        if (t == null) { sendMessage(peerId, "Тикет не найден"); return; }
        String template = quickReplyTemplates.get(p[2].toLowerCase(Locale.ROOT));
        if (template == null) { sendMessage(peerId, "Шаблон не найден. !rlist"); return; }
        String msg = template.replace("{player}", t.author).replace("{ticket}", String.valueOf(t.id)).replace("{staff}", actor);
        handleReply(peerId, actor, "!reply " + t.id + " " + msg);
    }

    private void handleCheck(int peerId, String text) {
        String[] p = text.split("\\s+");
        if (p.length != 2) { sendMessage(peerId, "Использование: !check <nick>"); return; }
        String nick = p[1];
        OfflinePlayer op = Bukkit.getOfflinePlayer(nick);
        UUID uuid = op.getUniqueId();
        boolean online = op.isOnline();

        String role = resolveRole(nick, online ? op.getPlayer() : null);
        int activePun = (int) punishments.stream().filter(x -> x.target.equalsIgnoreCase(nick) && !x.reverted).count();
        long supportCnt = tickets.values().stream().filter(t -> t.author.equalsIgnoreCase(nick) && t.category == TicketCategory.SUPPORT).count();
        long reportCnt = tickets.values().stream().filter(t -> t.author.equalsIgnoreCase(nick) && t.category == TicketCategory.REPORT).count();

        List<String> rows = new ArrayList<>();
        rows.add("👤 " + nick);
        rows.add("Статус: " + (online ? "online" : "offline"));
        rows.add("Last seen: " + formatLastSeen(lastSeen.getOrDefault(uuid, op.getLastPlayed()), online));
        rows.add("Онлайн сегодня: " + formatDuration(onlineForDays(uuid, 1)));
        rows.add("Онлайн 7 дней: " + formatDuration(onlineForDays(uuid, 7)));
        rows.add("Онлайн 30 дней: " + formatDuration(onlineForDays(uuid, 30)));
        rows.add("Группа/роль: " + role);
        rows.add("Активные наказания: " + activePun);
        rows.add("Тикеты/репорты: " + supportCnt + "/" + reportCnt);
        sendMessage(peerId, String.join("\n", rows));
    }

    private String resolveRole(String nick, Player online) {
        for (AdminData data : admins.values()) {
            if (data.nickname.equalsIgnoreCase(nick)) return "vk-admin-lvl-" + data.level;
        }
        if (online != null) {
            if (online.hasPermission("group.admin")) return "admin";
            if (online.hasPermission("group.moder")) return "moder";
            if (online.hasPermission("group.helper")) return "helper";
        }
        return "player";
    }

    private void handleCheckTech(int peerId, String text) {
        String[] p = text.split("\\s+");
        if (p.length != 3) { sendMessage(peerId, "Использование: !check tech <nick>"); return; }
        String nick = p[2];
        OfflinePlayer op = Bukkit.getOfflinePlayer(nick);
        long pun = punishments.stream().filter(x -> x.target.equalsIgnoreCase(nick)).count();
        long tick = tickets.values().stream().filter(t -> t.author.equalsIgnoreCase(nick)).count();
        sendMessage(peerId, "🧪 TECH " + nick + "\nUUID: " + op.getUniqueId() + "\nfirstPlayed: " + op.getFirstPlayed() + "\nlastPlayed: " + op.getLastPlayed() + "\nTickets: " + tick + "\nPunishments: " + pun);
    }

    private void handleLookup(int peerId, String text) {
        String[] p = text.split("\\s+");
        if (p.length < 2) { sendMessage(peerId, "Использование: !lookup <nick> [limit|punish|tickets]"); return; }
        String nick = p[1].toLowerCase(Locale.ROOT);
        String mode = p.length >= 3 ? p[2].toLowerCase(Locale.ROOT) : "command";
        int limit = 10;
        if (p.length >= 3) {
            try { limit = Math.max(1, Math.min(50, Integer.parseInt(p[2]))); mode = "command"; } catch (Exception ignored) {}
        }

        if (mode.equals("tickets")) {
            List<String> rows = new ArrayList<>();
            rows.add("📜 Lookup tickets " + p[1]);
            int c = 0;
            for (Ticket t : tickets.values()) {
                if (!t.author.equalsIgnoreCase(p[1])) continue;
                rows.add("#" + t.id + " " + t.category.name().toLowerCase(Locale.ROOT) + " " + t.status);
                if (++c >= limit) break;
            }
            if (c == 0) rows.add("нет записей");
            sendMessage(peerId, String.join("\n", rows));
            return;
        }

        if (mode.equals("punish")) {
            List<String> rows = new ArrayList<>();
            rows.add("📜 Lookup punish " + p[1]);
            int c = 0;
            for (int i = punishments.size() - 1; i >= 0; i--) {
                PunishmentRecord pr = punishments.get(i);
                if (!pr.target.equalsIgnoreCase(p[1])) continue;
                rows.add(formatAgo(pr.time) + " • " + pr.type + " • " + pr.reason + (pr.reverted ? " (reverted)" : ""));
                if (++c >= limit) break;
            }
            if (c == 0) rows.add("нет записей");
            sendMessage(peerId, String.join("\n", rows));
            return;
        }

        Deque<CommandLogEntry> log = commandLog.get(nick);
        if (log == null || log.isEmpty()) { sendMessage(peerId, "📜 Команд нет."); return; }
        List<String> rows = new ArrayList<>();
        rows.add("📜 Lookup command " + p[1]);
        int c = 0;
        for (CommandLogEntry e : log) {
            rows.add(formatAgo(e.time) + " • " + e.command);
            if (++c >= limit) break;
        }
        sendMessage(peerId, String.join("\n", rows));
    }

    private void handleStaffStats(int peerId, String text) {
        String[] p = text.split("\\s+");
        if (p.length < 2) { sendMessage(peerId, "Использование: !staffstats <vk_id|nick> [full]"); return; }
        String key = p[1];
        boolean full = p.length >= 3 && p[2].equalsIgnoreCase("full");

        StaffAggregation s = new StaffAggregation();
        for (Ticket t : tickets.values()) {
            if (key.equalsIgnoreCase(t.assignedVkId) || key.equalsIgnoreCase(t.author)) {
                if (t.status == TicketStatus.CLOSED) {
                    if (t.category == TicketCategory.SUPPORT) s.closedSupport++; else s.closedReport++;
                }
                s.replies += t.staff.replies;
                s.firstResponseMinutes += t.staff.firstResponseMinutes;
                s.firstResponseSamples += t.staff.firstResponseMinutes > 0 ? 1 : 0;
            }
        }
        for (PunishmentRecord pr : punishments) {
            if (key.equalsIgnoreCase(pr.staff)) {
                s.punishments++;
                if (pr.reverted) s.reverted++;
            }
        }
        for (DisciplineRecord dr : discipline) if (key.equalsIgnoreCase(dr.target) && !dr.forgiven) s.activeDiscipline++;

        int avgFirst = s.firstResponseSamples == 0 ? 0 : s.firstResponseMinutes / s.firstResponseSamples;
        String quality = s.reverted == 0 ? "стабильно" : (s.reverted <= 2 ? "нормально" : "нужен контроль");
        String recommendation = (s.closedSupport + s.closedReport >= 10 && avgFirst > 0 && avgFirst <= 5 && s.reverted <= 1) ? "к повышению" : "наблюдение";

        List<String> rows = new ArrayList<>();
        rows.add("📊 StaffStats " + key);
        rows.add("Closed support/report: " + s.closedSupport + "/" + s.closedReport);
        rows.add("Replies: " + s.replies);
        rows.add("First response avg: " + (avgFirst == 0 ? "н/д" : avgFirst + " мин"));
        rows.add("Punishments/reverted: " + s.punishments + "/" + s.reverted);
        rows.add("Active discipline: " + s.activeDiscipline);
        rows.add("Quality: " + quality);
        rows.add("Recommendation: " + recommendation);
        if (full) rows.add("Online d/7d/30d: n/a");
        sendMessage(peerId, String.join("\n", rows));
    }

    private void handleAudit(int peerId, String text) {
        String[] p = text.split("\\s+");
        String mode = p.length >= 2 ? p[1].toLowerCase(Locale.ROOT) : "recent";
        List<String> rows = new ArrayList<>();
        rows.add("🧾 Audit " + mode);

        int limit = 12;
        for (int i = auditLog.size() - 1; i >= 0 && rows.size() <= limit; i--) {
            AuditRecord r = auditLog.get(i);
            if (mode.equals("recent") || mode.equals("all")) rows.add(formatAgo(r.time) + " | " + r.actor + " | " + r.action);
            else if (mode.equals("ticket") && p.length >= 3 && r.scope.equals("ticket") && r.action.contains("#" + p[2])) rows.add(formatAgo(r.time) + " | " + r.actor + " | " + r.action);
            else if (mode.equals("player") && p.length >= 3 && r.action.toLowerCase(Locale.ROOT).contains(p[2].toLowerCase(Locale.ROOT))) rows.add(formatAgo(r.time) + " | " + r.actor + " | " + r.action);
            else if (mode.equals("punish") && p.length >= 3 && r.scope.equals("punish") && r.action.toLowerCase(Locale.ROOT).contains(p[2].toLowerCase(Locale.ROOT))) rows.add(formatAgo(r.time) + " | " + r.actor + " | " + r.action);
            else if (!mode.equals("recent") && !mode.equals("ticket") && !mode.equals("player") && !mode.equals("punish") && (r.actor.equalsIgnoreCase(mode) || r.actor.equalsIgnoreCase(p.length >= 2 ? p[1] : ""))) rows.add(formatAgo(r.time) + " | " + r.actor + " | " + r.action);
        }

        if (rows.size() == 1) rows.add("нет записей");
        sendMessage(peerId, String.join("\n", rows));
    }

    private void handleDiscipline(int peerId, String actor, String text) {
        String[] p = text.split("\\s+", 3);
        String cmd = p[0].toLowerCase(Locale.ROOT);

        if (cmd.equals("!staffdiscipline")) {
            if (p.length < 2) { sendMessage(peerId, "Использование: !staffdiscipline <vk_id|nick>"); return; }
            List<String> rows = new ArrayList<>();
            rows.add("📘 Discipline " + p[1]);
            for (DisciplineRecord r : discipline) if (r.target.equalsIgnoreCase(p[1])) rows.add("#" + r.id + " " + r.type + " | " + (r.forgiven ? "forgiven" : "active") + " | " + r.text);
            if (rows.size() == 1) rows.add("нет записей");
            sendMessage(peerId, String.join("\n", rows));
            return;
        }

        if (cmd.equals("!staffforgive")) {
            if (p.length < 2) { sendMessage(peerId, "Использование: !staffforgive <record_id>"); return; }
            DisciplineRecord record = null;
            for (DisciplineRecord d : discipline) if (String.valueOf(d.id).equals(p[1])) { record = d; break; }
            if (record == null) { sendMessage(peerId, "Запись не найдена"); return; }
            record.forgiven = true;
            dbDiscipline(record);
            audit("discipline", actor, "forgive #" + record.id);
            sendMessage(peerId, "✅ Дисциплинарная запись #" + record.id + " погашена");
            return;
        }

        if (p.length < 3) { sendMessage(peerId, "Использование: !staffnote|!staffwarn|!staffreprimand <vk_id|nick> <text>"); return; }
        DisciplineType type = cmd.equals("!staffwarn") ? DisciplineType.WARNING : cmd.equals("!staffreprimand") ? DisciplineType.REPRIMAND : DisciplineType.NOTE;
        DisciplineRecord rec = new DisciplineRecord(nextDisciplineId(), p[1], type, p[2], actor);
        discipline.add(rec);
        dbDiscipline(rec);
        audit("discipline", actor, type.name().toLowerCase(Locale.ROOT) + " -> " + p[1] + " | " + p[2]);
        sendMessage(peerId, "✅ " + type.name().toLowerCase(Locale.ROOT) + " записан для " + p[1] + " (#" + rec.id + ")");
    }

    private void handlePunishLog(int peerId, String text) {
        String[] p = text.split("\\s+");
        if (p.length != 2) { sendMessage(peerId, "Использование: !punishlog <nick>"); return; }
        List<String> rows = new ArrayList<>();
        rows.add("⚖ Punishlog " + p[1]);
        int c = 0;
        for (int i = punishments.size() - 1; i >= 0; i--) {
            PunishmentRecord r = punishments.get(i);
            if (!r.target.equalsIgnoreCase(p[1])) continue;
            rows.add(formatAgo(r.time) + " | " + r.type + " | " + r.reason + (r.reverted ? " (reverted)" : ""));
            if (++c >= 12) break;
        }
        if (c == 0) rows.add("нет записей");
        sendMessage(peerId, String.join("\n", rows));
    }

    private void handleReversePunish(int peerId, String actor, String text, String type) {
        String[] p = text.split("\\s+", 3);
        if (p.length < 2) { sendMessage(peerId, "Использование: !" + type + " <nick> [reason]"); return; }
        String reason = p.length >= 3 ? p[2] : "manual";
        String cmd = type + " " + p[1] + (reason.isEmpty() ? "" : " " + reason);
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));

        PunishmentRecord target = null;
        for (int i = punishments.size() - 1; i >= 0; i--) {
            PunishmentRecord x = punishments.get(i);
            if (x.target.equalsIgnoreCase(p[1]) && !x.reverted) { target = x; break; }
        }
        if (target != null) target.reverted = true;
        dbPunishment(target);

        audit("punish", actor, type + " " + p[1] + " " + reason);
        sendToChat(ChatType.MODMANAGE, "♻ " + actor + " -> /" + cmd);
        sendMessage(peerId, "✅ Отмена отправлена: " + type + " " + p[1]);
    }

    private void handleBan(int peerId, String actor, String text) {
        String[] p = text.split("\\s+");
        if (p.length < 2) { sendMessage(peerId, "Использование: !ban <nick|@id> <preset|time reason>"); return; }
        if (p[1].startsWith("@") || p[1].startsWith("id")) {
            handleChatBan(peerId, text);
            return;
        }
        handlePunish(peerId, actor, text, "ban");
    }

    private void handlePunish(int peerId, String actor, String text, String type) {
        String[] p = text.split("\\s+", 3);
        if (p.length < 3) { sendMessage(peerId, "Использование: !" + type + " <nick> <preset|time reason>"); return; }
        String target = p[1];
        String payload = resolvePreset(type, p[2]);
        String cmd = type + " " + target + " " + payload;
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));

        PunishmentRecord rec = new PunishmentRecord(type, actor, target, payload, Instant.now());
        punishments.add(rec);
        dbPunishment(rec);

        Ticket linked = findLatestReportByAuthor(target);
        if (linked != null) {
            linked.staff.punishments++;
            linked.history.add("⚖ " + type + " " + payload + " by " + actor);
            linked.touch();
            dbTicketUpsert(linked);
        }

        audit("punish", actor, type + " " + target + " " + payload);
        sendToChat(ChatType.MODMANAGE, "⚖ " + actor + " -> /" + cmd);
        sendMessage(peerId, "✅ " + type.toUpperCase(Locale.ROOT) + " отправлен: " + target);
    }

    private String resolvePreset(String type, String payload) {
        Map<String, String> source = type.equals("mute") ? mutePresets : type.equals("ban") ? banPresets : warnPresets;
        return source.getOrDefault(payload.toLowerCase(Locale.ROOT), payload);
    }

    private Ticket findLatestReportByAuthor(String author) {
        Ticket latest = null;
        for (Ticket t : tickets.values()) if (t.category == TicketCategory.REPORT && t.author.equalsIgnoreCase(author)) latest = t;
        return latest;
    }

    private void handleAdminSet(int peerId, int actorLevel, String actor, String text) {
        String[] p = text.split("\\s+");
        if (p.length < 4 || p.length > 5) { sendMessage(peerId, "Использование: !admin set @user nick [1-4]"); return; }
        UserProfile user = resolveUser(p[2]);
        if (user == null) { sendMessage(peerId, "Не удалось определить пользователя"); return; }
        int level = p.length == 5 ? parseLevel(p[4], -1) : 1;
        if (level < 1 || level > 4) { sendMessage(peerId, "Уровень 1..4"); return; }
        if (!canAssignLevel(actorLevel, level)) { sendMessage(peerId, "Недостаточно прав"); return; }
        AdminData current = admins.get(user.id);
        if (current != null && !canModifyTarget(actorLevel, current.level)) { sendMessage(peerId, "Недостаточно прав"); return; }

        admins.put(user.id, new AdminData(level, p[3]));
        nicknames.put(user.id, p[3]);
        persistAdmin(user.id, level, p[3]);
        persistNickname(user.id, p[3]);

        audit("admin", actor, "set " + user.id + " nick=" + p[3] + " lvl=" + level);
        sendMessage(peerId, "✅ Назначено: " + user.mention + " (lvl " + level + ")");
    }

    private void handleAdminLevel(int peerId, int actorLevel, String actor, String text) {
        String[] p = text.split("\\s+");
        if (p.length != 4) { sendMessage(peerId, "Использование: !admin level @id 1..4"); return; }
        UserProfile user = resolveUser(p[2]);
        if (user == null || !admins.containsKey(user.id)) { sendMessage(peerId, "Администратор не найден"); return; }
        int level = parseLevel(p[3], -1);
        if (level < 1 || level > 4) { sendMessage(peerId, "Уровень 1..4"); return; }
        AdminData target = admins.get(user.id);
        if (!canModifyTarget(actorLevel, target.level) || !canAssignLevel(actorLevel, level)) { sendMessage(peerId, "Недостаточно прав"); return; }
        int old = target.level;
        target.level = level;
        persistAdmin(user.id, target.level, target.nickname);
        audit("admin", actor, "level " + user.id + " " + old + "->" + level);
        sendMessage(peerId, "✅ Уровень обновлён: " + old + " -> " + level);
    }

    private void handleAdminRemove(int peerId, int actorLevel, String actor, String text) {
        String[] p = text.split("\\s+");
        if (p.length != 3) { sendMessage(peerId, "Использование: !admin remove @id"); return; }
        UserProfile user = resolveUser(p[2]);
        if (user == null || !admins.containsKey(user.id)) { sendMessage(peerId, "Администратор не найден"); return; }
        AdminData target = admins.get(user.id);
        if (!canModifyTarget(actorLevel, target.level)) { sendMessage(peerId, "Недостаточно прав"); return; }

        admins.remove(user.id);
        plugin.getConfig().set("vk.admins." + user.id, null);
        plugin.saveConfig();

        int removed = 0;
        for (Integer chatId : chatIds.values()) if (kickFromChat(user.id, chatId)) removed++;

        if (!target.nickname.isEmpty()) Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + target.nickname + " parent set default"));
        audit("admin", actor, "remove " + user.id + " fromChats=" + removed);
        sendMessage(peerId, "🗑 Удалено: " + user.mention + " | чатов: " + removed);
    }

    private void handleRnick(int peerId, String actor, String text) {
        String[] p = text.split("\\s+");
        if (p.length != 3) { sendMessage(peerId, "Использование: !rnick @user nick"); return; }
        UserProfile user = resolveUser(p[1]);
        if (user == null) { sendMessage(peerId, "Пользователь не найден"); return; }
        String old = nicknames.getOrDefault(user.id, "-");
        nicknames.put(user.id, p[2]);
        persistNickname(user.id, p[2]);
        AdminData admin = admins.get(user.id);
        if (admin != null) { admin.nickname = p[2]; persistAdmin(user.id, admin.level, admin.nickname); }
        audit("admin", actor, "rnick " + user.id + " " + old + "->" + p[2]);
        sendMessage(peerId, "✅ Ник обновлен: " + old + " -> " + p[2]);
    }

    private void handleChatKick(int peerId, String text) {
        String[] p = text.split("\\s+", 3);
        if (p.length < 2) { sendMessage(peerId, "Использование: !kick @id [reason]"); return; }
        UserProfile user = resolveUser(p[1]);
        if (user == null) { sendMessage(peerId, "Пользователь не найден"); return; }
        boolean ok = kickFromChat(user.id, chatIds.get(ChatType.ADMMANAGE));
        sendMessage(peerId, ok ? "✅ Кик: " + user.mention : "Не удалось кикнуть " + user.mention);
    }

    private void handleChatBan(int peerId, String text) {
        String[] p = text.split("\\s+");
        if (p.length < 2) { sendMessage(peerId, "Использование: !ban @id reason [1d]"); return; }
        UserProfile user = resolveUser(p[1]);
        if (user == null) { sendMessage(peerId, "Пользователь не найден"); return; }

        long until = 0;
        String reason;
        if (p.length >= 3 && DAYS_PATTERN.matcher(p[p.length - 1]).matches()) {
            int days = Integer.parseInt(p[p.length - 1].substring(0, p[p.length - 1].length() - 1));
            until = Instant.now().plus(days, ChronoUnit.DAYS).toEpochMilli();
            reason = joinRange(p, 2, p.length - 1);
        } else reason = joinRange(p, 2, p.length);

        chatBans.put(user.id, new ChatBan(until, reason));
        persistChatBan(user.id, until, reason);
        kickFromChat(user.id, chatIds.get(ChatType.ADMMANAGE));
        sendMessage(peerId, "✅ VK ban: " + user.mention + (until > 0 ? " до " + formatLastSeen(until, false) : " навсегда"));
    }

    private void handleChatUnban(int peerId, String text) {
        String[] p = text.split("\\s+");
        if (p.length != 2) { sendMessage(peerId, "Использование: !unban @id"); return; }
        UserProfile user = resolveUser(p[1]);
        if (user == null) { sendMessage(peerId, "Пользователь не найден"); return; }
        chatBans.remove(user.id);
        plugin.getConfig().set("vk.chat-bans." + user.id, null);
        plugin.saveConfig();
        sendMessage(peerId, "✅ VK unban: " + user.mention);
    }

    private void handleNoName(int peerId) {
        try {
            JsonObject response = callVkMethod("messages.getConversationMembers", mapOf("peer_id", String.valueOf(toPeerId(chatIds.get(ChatType.ADMMANAGE)))));
            JsonObject obj = response.getAsJsonObject("response");
            if (obj == null) { sendMessage(peerId, "Не удалось получить участников"); return; }
            JsonArray items = obj.getAsJsonArray("items");
            List<String> missing = new ArrayList<>();
            if (items != null) for (int i = 0; i < items.size(); i++) {
                long memberId = items.get(i).getAsJsonObject().get("member_id").getAsLong();
                if (memberId > 0 && !nicknames.containsKey(memberId)) missing.add(mentionById(memberId));
            }
            sendMessage(peerId, missing.isEmpty() ? "✅ У всех есть ник" : "Без ника:\n" + String.join("\n", missing));
        } catch (Exception e) {
            sendMessage(peerId, "Ошибка VK API при !noname");
        }
    }

    private void handleCmd(int peerId, int actorLevel, String actor, String command) {
        String deny = commandPolicy.denyReason(command, actorLevel);
        if (deny != null) {
            sendMessage(peerId, deny);
            audit("raw", actor, "blocked /" + command + " reason=" + deny);
            sendToChat(ChatType.EVENTS, "🚫 raw blocked: /" + command);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        sendMessage(peerId, "✅ raw отправлен");
        audit("raw", actor, "allow /" + command);
        sendToChat(ChatType.EVENTS, "⚙ raw: /" + command + " by " + actor);
    }

    private void audit(String scope, String actor, String action) {
        AuditRecord rec = new AuditRecord(scope, actor, action, Instant.now());
        auditLog.add(rec);
        dbAudit(rec);
    }

    private void queuePending(UUID uuid, int ticketId, String body) {
        if (uuid == null) return;
        Deque<PendingMessage> q = pendingByUuid.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        for (PendingMessage m : q) if (m.ticketId == ticketId && m.body.equals(body)) return;
        PendingMessage m = new PendingMessage(ticketId, body, System.currentTimeMillis());
        q.addLast(m);
        dbPending(uuid, m);
    }

    private void deliverPending(UUID uuid) {
        Deque<PendingMessage> q = pendingByUuid.remove(uuid);
        if (q == null || q.isEmpty()) return;
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;
        int delivered = 0;
        while (!q.isEmpty()) {
            PendingMessage m = q.removeFirst();
            player.sendMessage("[Support] #" + m.ticketId + " " + m.body);
            delivered++;
        }
        dbPendingDelete(uuid);
        notifyEvent("Pending доставлен: " + player.getName() + " x" + delivered);
    }

    private void cleanupRuntimeData() {
        Instant commandBorder = Instant.now().minus(retentionCommandDays, ChronoUnit.DAYS);
        for (Deque<CommandLogEntry> queue : commandLog.values()) while (!queue.isEmpty() && queue.peekFirst().time.isBefore(commandBorder)) queue.removeFirst();

        long pendingBorder = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionPendingDays);
        for (Deque<PendingMessage> queue : pendingByUuid.values()) while (!queue.isEmpty() && queue.peekFirst().createdAtMs < pendingBorder) queue.removeFirst();

        long closeBorder = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionClosedTicketsDays);
        List<Integer> delete = new ArrayList<>();
        for (Ticket t : tickets.values()) if (t.status == TicketStatus.CLOSED && t.updatedAtMs < closeBorder) delete.add(t.id);
        for (Integer id : delete) { tickets.remove(id); dbTicketDelete(id); }
    }

    private Ticket findTicket(String raw) {
        try { return tickets.get(Integer.parseInt(raw)); } catch (Exception e) { return null; }
    }

    private long onlineForDays(UUID uuid, int days) {
        long total = 0;
        LocalDate from = LocalDate.now().minusDays(days - 1L);
        NavigableMap<LocalDate, Long> map = onlineStats.get(uuid);
        if (map != null) for (Map.Entry<LocalDate, Long> e : map.tailMap(from, true).entrySet()) total += e.getValue();
        Player p = Bukkit.getPlayer(uuid);
        if (p != null && p.isOnline()) {
            Long start = onlineSince.get(uuid);
            if (start != null) total += Math.max(0, System.currentTimeMillis() - start);
        }
        return total;
    }

    private String formatLastSeen(long epochMillis, boolean online) {
        if (online) return "сейчас";
        if (epochMillis <= 0) return "неизвестно";
        Instant when = Instant.ofEpochMilli(epochMillis);
        long minutes = Duration.between(when, Instant.now()).toMinutes();
        if (minutes < 1) return "только что";
        if (minutes < 60) return minutes + " минут назад";

        ZonedDateTime z = when.atZone(ZoneId.systemDefault());
        LocalDate day = z.toLocalDate();
        LocalDate today = LocalDate.now();
        LocalTime t = z.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
        if (day.equals(today)) return "сегодня в " + pad2(t.getHour()) + ":" + pad2(t.getMinute());
        if (day.equals(today.minusDays(1))) return "вчера в " + pad2(t.getHour()) + ":" + pad2(t.getMinute());
        return ChronoUnit.DAYS.between(day, today) + " дня назад";
    }

    private String formatAgo(Instant when) {
        long min = Duration.between(when, Instant.now()).toMinutes();
        if (min < 1) return "сейчас";
        if (min < 60) return min + "м назад";
        long h = min / 60;
        if (h < 24) return h + "ч назад";
        return (h / 24) + "д назад";
    }

    private String formatDuration(long millis) {
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis);
        return (minutes / 60) + "ч " + (minutes % 60) + "м";
    }

    private String pad2(int x) { return x < 10 ? "0" + x : String.valueOf(x); }

    private boolean kickFromChat(long userId, int chatId) {
        if (chatId <= 0) return false;
        try {
            callVkMethod("messages.removeChatUser", mapOf("chat_id", String.valueOf(chatId), "member_id", String.valueOf(userId)));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void sendToChat(ChatType type, String message) {
        int peerId = toPeerId(chatIds.getOrDefault(type, 0));
        if (peerId > 0) sendMessage(peerId, message);
    }

    private void sendMessage(int peerId, String message) {
        if (accessToken == null || accessToken.isEmpty()) return;
        try {
            Map<String, String> params = new HashMap<>();
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

        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create(sb.toString())).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonObject body = gson.fromJson(response.body(), JsonObject.class);
        if (body != null && body.has("error")) throw new IllegalStateException(body.get("error").toString());
        return body;
    }

    private UserProfile resolveUser(String token) {
        UserProfile p = parseMention(token);
        return p != null ? p : loadUser(token.startsWith("id") ? token : token.replace("@", ""));
    }

    private UserProfile parseMention(String mention) {
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
        if (admins.isEmpty()) return "Администраторы не настроены";
        List<String> rows = new ArrayList<>();
        rows.add("👥 Администраторы");
        for (Map.Entry<Long, AdminData> e : admins.entrySet()) rows.add(mentionById(e.getKey()) + " — " + e.getValue().nickname + " (lvl " + e.getValue().level + ")");
        return String.join("\n", rows);
    }

    private int parseLevel(String raw, int def) { try { return clampLevel(Integer.parseInt(raw)); } catch (Exception e) { return def; } }
    private boolean canAssignLevel(int actor, int target) { return actor >= 4 || (actor >= 3 && target == 1); }
    private boolean canModifyTarget(int actor, int target) { return actor >= 4 || actor > target; }
    private int clampLevel(int x) { return Math.max(1, Math.min(4, x)); }

    private void persistAdmin(long id, int level, String nick) {
        String b = "vk.admins." + id;
        plugin.getConfig().set(b + ".level", level);
        plugin.getConfig().set(b + ".nickname", nick);
        plugin.saveConfig();
    }

    private void persistNickname(long id, String nick) {
        plugin.getConfig().set("vk.nicknames." + id, nick);
        plugin.saveConfig();
    }

    private void persistChatBan(long id, long until, String reason) {
        String b = "vk.chat-bans." + id;
        plugin.getConfig().set(b + ".until-epoch-ms", until);
        plugin.getConfig().set(b + ".reason", reason);
        plugin.saveConfig();
    }

    private String joinRange(String[] arr, int from, int to) {
        if (from >= to) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) { if (sb.length() > 0) sb.append(' '); sb.append(arr[i]); }
        return sb.toString();
    }

    private String tailAfterSpace(String x) {
        int i = x.indexOf(' ');
        return (i < 0 || i + 1 >= x.length()) ? "" : x.substring(i + 1);
    }

    private int toPeerId(int chatId) { return chatId <= 0 ? 0 : PEER_CHAT_BASE + chatId; }
    private String encode(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    private boolean isDuplicate(String key, long thresholdMs) {
        long now = System.currentTimeMillis();
        Long prev = antiSpam.put(key, now);
        return prev != null && now - prev <= thresholdMs;
    }

    private Map<String, String> mapOf(String... kv) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) map.put(kv[i], kv[i + 1]);
        return map;
    }

    /* SQLite */
    private void ensureDatabase() {
        try {
            dbFile = new File(plugin.getDataFolder(), "ordavk-pro.db");
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            Class.forName("org.sqlite.JDBC");
            try (Connection c = db()) {
                Statement st = c.createStatement();
                st.executeUpdate("create table if not exists tickets(id integer primary key, category text, status text, author text, assigned text, created_ms integer, updated_ms integer, first_response integer, replies integer, punish integer, reverted integer)");
                st.executeUpdate("create table if not exists ticket_history(ticket_id integer, idx integer, line text, primary key(ticket_id, idx))");
                st.executeUpdate("create table if not exists audit_log(id integer primary key autoincrement, ts integer, scope text, actor text, action text)");
                st.executeUpdate("create table if not exists punishments(id integer primary key autoincrement, ts integer, type text, staff text, target text, reason text, reverted integer)");
                st.executeUpdate("create table if not exists discipline(id integer primary key, ts integer, target text, type text, text text, actor text, forgiven integer)");
                st.executeUpdate("create table if not exists pending(uuid text primary key, payload text, updated_ms integer)");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("SQLite init failed: " + e.getMessage());
        }
    }

    private Connection db() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
    }

    private void loadRuntimeDataFromDb() {
        if (dbFile == null || !dbFile.exists()) return;
        try (Connection c = db()) {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select * from tickets")) {
                while (rs.next()) {
                    Ticket t = new Ticket(rs.getInt("id"), TicketCategory.valueOf(rs.getString("category")), rs.getString("author"), "restored");
                    t.status = TicketStatus.valueOf(rs.getString("status"));
                    t.assignedVkId = rs.getString("assigned");
                    t.updatedAtMs = rs.getLong("updated_ms");
                    t.createdAtMs = rs.getLong("created_ms");
                    t.staff.firstResponseMinutes = rs.getInt("first_response");
                    t.staff.replies = rs.getInt("replies");
                    t.staff.punishments = rs.getInt("punish");
                    t.staff.revertedPunishments = rs.getInt("reverted");
                    t.history.clear();
                    tickets.put(t.id, t);
                    nextTicketId = Math.max(nextTicketId, t.id + 1);
                }
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select * from ticket_history order by ticket_id, idx")) {
                while (rs.next()) {
                    Ticket t = tickets.get(rs.getInt("ticket_id"));
                    if (t != null) t.history.add(rs.getString("line"));
                }
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select * from audit_log order by id")) {
                while (rs.next()) auditLog.add(new AuditRecord(rs.getString("scope"), rs.getString("actor"), rs.getString("action"), Instant.ofEpochMilli(rs.getLong("ts"))));
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select * from punishments order by id")) {
                while (rs.next()) {
                    PunishmentRecord pr = new PunishmentRecord(rs.getString("type"), rs.getString("staff"), rs.getString("target"), rs.getString("reason"), Instant.ofEpochMilli(rs.getLong("ts")));
                    pr.reverted = rs.getInt("reverted") == 1;
                    punishments.add(pr);
                }
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select * from discipline order by id")) {
                while (rs.next()) {
                    DisciplineRecord dr = new DisciplineRecord(rs.getInt("id"), rs.getString("target"), DisciplineType.valueOf(rs.getString("type")), rs.getString("text"), rs.getString("actor"));
                    dr.forgiven = rs.getInt("forgiven") == 1;
                    dr.time = Instant.ofEpochMilli(rs.getLong("ts"));
                    discipline.add(dr);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("SQLite load failed: " + e.getMessage());
        }
    }

    private void dbTicketUpsert(Ticket t) {
        try (Connection c = db()) {
            PreparedStatement ps = c.prepareStatement("insert or replace into tickets(id,category,status,author,assigned,created_ms,updated_ms,first_response,replies,punish,reverted) values(?,?,?,?,?,?,?,?,?,?,?)");
            ps.setInt(1, t.id);
            ps.setString(2, t.category.name());
            ps.setString(3, t.status.name());
            ps.setString(4, t.author);
            ps.setString(5, t.assignedVkId);
            ps.setLong(6, t.createdAtMs);
            ps.setLong(7, t.updatedAtMs);
            ps.setInt(8, t.staff.firstResponseMinutes);
            ps.setInt(9, t.staff.replies);
            ps.setInt(10, t.staff.punishments);
            ps.setInt(11, t.staff.revertedPunishments);
            ps.executeUpdate();
            c.createStatement().executeUpdate("delete from ticket_history where ticket_id=" + t.id);
            PreparedStatement hs = c.prepareStatement("insert into ticket_history(ticket_id,idx,line) values(?,?,?)");
            for (int i = 0; i < t.history.size(); i++) {
                hs.setInt(1, t.id);
                hs.setInt(2, i);
                hs.setString(3, t.history.get(i));
                hs.addBatch();
            }
            hs.executeBatch();
        } catch (Exception ignored) {
        }
    }

    private void dbTicketDelete(int id) {
        try (Connection c = db()) {
            c.createStatement().executeUpdate("delete from tickets where id=" + id);
            c.createStatement().executeUpdate("delete from ticket_history where ticket_id=" + id);
        } catch (Exception ignored) {
        }
    }

    private void dbAudit(AuditRecord r) {
        try (Connection c = db()) {
            PreparedStatement ps = c.prepareStatement("insert into audit_log(ts,scope,actor,action) values(?,?,?,?)");
            ps.setLong(1, r.time.toEpochMilli());
            ps.setString(2, r.scope);
            ps.setString(3, r.actor);
            ps.setString(4, r.action);
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    private void dbPunishment(PunishmentRecord r) {
        if (r == null) return;
        try (Connection c = db()) {
            PreparedStatement ps = c.prepareStatement("insert into punishments(ts,type,staff,target,reason,reverted) values(?,?,?,?,?,?)");
            ps.setLong(1, r.time.toEpochMilli());
            ps.setString(2, r.type);
            ps.setString(3, r.staff);
            ps.setString(4, r.target);
            ps.setString(5, r.reason);
            ps.setInt(6, r.reverted ? 1 : 0);
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    private void dbDiscipline(DisciplineRecord r) {
        try (Connection c = db()) {
            PreparedStatement ps = c.prepareStatement("insert or replace into discipline(id,ts,target,type,text,actor,forgiven) values(?,?,?,?,?,?,?)");
            ps.setInt(1, r.id);
            ps.setLong(2, r.time.toEpochMilli());
            ps.setString(3, r.target);
            ps.setString(4, r.type.name());
            ps.setString(5, r.text);
            ps.setString(6, r.actor);
            ps.setInt(7, r.forgiven ? 1 : 0);
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    private void dbPending(UUID uuid, PendingMessage m) {
        try (Connection c = db()) {
            PreparedStatement ps = c.prepareStatement("insert or replace into pending(uuid,payload,updated_ms) values(?,?,?)");
            ps.setString(1, uuid.toString());
            ps.setString(2, m.ticketId + "|" + m.body.replace("|", " "));
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    private void dbPendingDelete(UUID uuid) {
        try (Connection c = db()) {
            c.createStatement().executeUpdate("delete from pending where uuid='" + uuid + "'");
        } catch (Exception ignored) {
        }
    }

    private int nextDisciplineId() {
        int max = 0;
        for (DisciplineRecord r : discipline) max = Math.max(max, r.id);
        return max + 1;
    }

    private enum ChatType { ADMMANAGE, EVENTS, SUPPORT, MODMANAGE }
    private enum TicketCategory { SUPPORT, REPORT }
    private enum TicketStatus { OPEN, IN_PROGRESS, WAITING_PLAYER, CLOSED }
    private enum DisciplineType { NOTE, WARNING, REPRIMAND }

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

    private static class CommandLogEntry {
        private final Instant time;
        private final String command;
        private CommandLogEntry(Instant time, String command) { this.time = time; this.command = command; }
    }

    private static class PendingMessage {
        private final int ticketId;
        private final String body;
        private final long createdAtMs;
        private PendingMessage(int ticketId, String body, long createdAtMs) { this.ticketId = ticketId; this.body = body; this.createdAtMs = createdAtMs; }
    }

    private static class StaffTicketStats {
        private int replies;
        private int punishments;
        private int revertedPunishments;
        private int firstResponseMinutes;
    }

    private static class Ticket {
        private final int id;
        private TicketCategory category;
        private TicketStatus status;
        private final String author;
        private String assignedVkId = "-";
        private long createdAtMs = System.currentTimeMillis();
        private long updatedAtMs = createdAtMs;
        private final StaffTicketStats staff = new StaffTicketStats();
        private final List<String> history = new ArrayList<>();

        private Ticket(int id, TicketCategory category, String author, String body) {
            this.id = id;
            this.category = category;
            this.status = TicketStatus.OPEN;
            this.author = author;
            this.history.add("📝 " + body);
        }

        private void touch() {
            this.updatedAtMs = System.currentTimeMillis();
            if (staff.firstResponseMinutes <= 0 && history.size() > 1) {
                staff.firstResponseMinutes = (int) Math.max(1, Duration.ofMillis(updatedAtMs - createdAtMs).toMinutes());
            }
        }

        private String formatCard(boolean full) {
            List<String> rows = new ArrayList<>();
            rows.add("🎫 Ticket #" + id);
            rows.add("Category: " + category.name().toLowerCase(Locale.ROOT));
            rows.add("Status: " + status);
            rows.add("Author: " + author);
            rows.add("Assigned: " + assignedVkId);
            rows.add("Created: " + Instant.ofEpochMilli(createdAtMs));
            rows.add("Updated: " + Instant.ofEpochMilli(updatedAtMs));
            rows.add("First response: " + (staff.firstResponseMinutes <= 0 ? "н/д" : staff.firstResponseMinutes + " мин"));
            rows.add("History:");
            int size = history.size();
            int from = Math.max(0, size - (full ? 8 : 5));
            for (int i = from; i < size; i++) rows.add(history.get(i));
            return String.join("\n", rows);
        }
    }

    private static class AuditRecord {
        private final String scope;
        private final String actor;
        private final String action;
        private final Instant time;
        private AuditRecord(String scope, String actor, String action, Instant time) {
            this.scope = scope;
            this.actor = actor;
            this.action = action;
            this.time = time;
        }
    }

    private static class DisciplineRecord {
        private final int id;
        private final String target;
        private final DisciplineType type;
        private final String text;
        private final String actor;
        private Instant time;
        private boolean forgiven;

        private DisciplineRecord(int id, String target, DisciplineType type, String text, String actor) {
            this.id = id;
            this.target = target;
            this.type = type;
            this.text = text;
            this.actor = actor;
            this.time = Instant.now();
        }
    }

    private static class PunishmentRecord {
        private final String type;
        private final String staff;
        private final String target;
        private final String reason;
        private final Instant time;
        private boolean reverted;

        private PunishmentRecord(String type, String staff, String target, String reason, Instant time) {
            this.type = type;
            this.staff = staff;
            this.target = target;
            this.reason = reason;
            this.time = time;
        }
    }

    private static class StaffAggregation {
        private int closedSupport;
        private int closedReport;
        private int replies;
        private int firstResponseMinutes;
        private int firstResponseSamples;
        private int punishments;
        private int reverted;
        private int activeDiscipline;
    }

    private static class BotAccessPolicy {
        private final Map<String, Integer> levels;
        private BotAccessPolicy(Map<String, Integer> levels) { this.levels = levels; }
        private boolean hasAccess(int actorLevel, String key) { return actorLevel >= levels.getOrDefault(key, 4); }
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
            return new CommandPolicy(true, "blacklist", new HashSet<>(Arrays.asList("op", "deop", "stop", "reload")), new HashSet<>(), new HashSet<>(Arrays.asList("stop", "reload confirm")), 4);
        }

        private String denyReason(String command, int actorLevel) {
            String cmd = command.trim().toLowerCase(Locale.ROOT);
            if (cmd.isEmpty()) return "Пустая команда";
            if (startsWithAny(cmd, absoluteBlacklist)) return "⛔ Абсолютный запрет";
            if (!enabled) return null;
            if ("blacklist".equals(mode) && startsWithAny(cmd, blacklist)) return "Команда в blacklist";
            if ("whitelist".equals(mode) && actorLevel < chiefBypassLevel && !startsWithAny(cmd, whitelist)) return "Команда не в whitelist";
            return null;
        }

        private boolean startsWithAny(String cmd, Set<String> set) {
            for (String x : set) if (cmd.startsWith(x)) return true;
            return false;
        }
    }
}
