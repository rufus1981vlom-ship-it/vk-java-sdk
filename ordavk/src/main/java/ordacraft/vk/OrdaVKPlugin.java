package ordacraft.vk;

import ordacraft.vk.admin.AdminRegistry;
import ordacraft.vk.admin.Role;
import ordacraft.vk.bridge.VkMessageRouter;
import ordacraft.vk.command.CommandPolicyService;
import ordacraft.vk.command.OrdaVkControlCommand;
import ordacraft.vk.command.OvkOnlineCommand;
import ordacraft.vk.command.TicketPlayerCommands;
import ordacraft.vk.config.ChatMode;
import ordacraft.vk.config.ConfigManager;
import ordacraft.vk.governance.GovernanceService;
import ordacraft.vk.listener.DangerousCommandListener;
import ordacraft.vk.listener.JoinQuitListener;
import ordacraft.vk.service.*;
import ordacraft.vk.storage.YamlFileStore;
import ordacraft.vk.support.PendingActionService;
import ordacraft.vk.support.PendingReplyService;
import ordacraft.vk.support.SupportTicketService;
import ordacraft.vk.vk.api.VkApiClient;
import ordacraft.vk.vk.polling.VkLongPollService;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Date;
import java.util.Map;

public class OrdaVKPlugin extends JavaPlugin {
    private AdminRegistry adminRegistry;
    private SupportTicketService ticketService;
    private PendingReplyService pendingReplyService;
    private PendingActionService pendingActionService;
    private AuditService auditService;

    private ConfigManager config;
    private LocalizationService i18n;
    private PermissionMatrixService matrix;
    private VkApiClient vkApiClient;
    private EventRelayService relay;
    private ConsoleDispatchService console;
    private GovernanceService governance;
    private CommandPolicyService policy;
    private VkMessageRouter router;
    private VkLongPollService poll;
    private PlanStatsService planStats;

    private volatile long lastIncomingMessageAt;
    private volatile long lastPollStartAt;
    private volatile long lastErrorAt;

    @Override
    public void onEnable() {
        config = new ConfigManager(this);
        config.reload();

        Path data = getDataFolder().toPath();
        adminRegistry = new AdminRegistry(new YamlFileStore(data.resolve("admins.yml")));
        ticketService = new SupportTicketService(new YamlFileStore(data.resolve("tickets.yml")));
        pendingReplyService = new PendingReplyService(new YamlFileStore(data.resolve("pending-replies.yml")));
        pendingActionService = new PendingActionService(new YamlFileStore(data.resolve("pending-actions.yml")));
        auditService = new AuditService(new YamlFileStore(data.resolve("audit-log.yml")));

        adminRegistry.load();
        ticketService.load();
        pendingReplyService.load();
        pendingActionService.load();

        rebuildRuntime(false);

        var ticketsCommand = new TicketPlayerCommands(
                ticketService,
                config.settings().supportCooldown(),
                config.settings().supportMaxOpen(),
                i18n,
                msg -> config.settings().chats().stream().filter(c -> c.mode() == ChatMode.SUPPORT).forEach(c -> {
                    try { vkApiClient.send(c.id(), msg); } catch (Exception ignored) {}
                }),
                msg -> config.settings().chats().stream().filter(c -> c.mode() == ChatMode.MMANAGE).forEach(c -> {
                    try { vkApiClient.send(c.id(), msg); } catch (Exception ignored) {}
                })
        );

        if (getCommand("helpop") != null) getCommand("helpop").setExecutor(ticketsCommand);
        if (getCommand("ac") != null) getCommand("ac").setExecutor(ticketsCommand);
        if (getCommand("report") != null) getCommand("report").setExecutor(ticketsCommand);
        if (getCommand("rep") != null) getCommand("rep").setExecutor(ticketsCommand);
        if (getCommand("ordavk") != null) getCommand("ordavk").setExecutor(new OrdaVkControlCommand(this));
        if (getCommand("ovk") != null) getCommand("ovk").setExecutor(new OvkOnlineCommand(planStats));

        getServer().getPluginManager().registerEvents(new JoinQuitListener(this, relay, pendingReplyService, pendingActionService, console, auditService, i18n, config.settings().joinDeliveryDelayTicks()), this);
        getServer().getPluginManager().registerEvents(new DangerousCommandListener(relay), this);
    }

    public synchronized String bootstrapAdmin(long vkId, String mcNick, Role role) {
        adminRegistry.upsert(vkId, mcNick, role);
        adminRegistry.save();
        return ChatColor.GREEN + "Bootstrap OK: vk=" + vkId + ", nick=" + mcNick + ", role=" + role.name().toLowerCase();
    }

    public synchronized String reloadManager() {
        try {
            if (poll != null) poll.stop();

            config.reload();
            adminRegistry.load();
            ticketService.load();
            pendingReplyService.load();
            pendingActionService.load();

            rebuildRuntime(true);
            return ChatColor.GREEN + "OrdaVK Manager reloaded successfully";
        } catch (Exception e) {
            getLogger().warning("Reload failed: " + e.getMessage());
            return ChatColor.RED + "Reload failed: " + e.getMessage();
        }
    }

    private void rebuildRuntime(boolean fromReload) {
        i18n = new LocalizationService(config.settings().language());
        matrix = new PermissionMatrixService(config.settings().commandMinRoles());

        vkApiClient = new VkApiClient(config.settings().token(), config.settings().apiVersion());
        planStats = new UnavailablePlanStatsService();
        relay = new EventRelayService(config.settings(), vkApiClient);
        console = new ConsoleDispatchService(this);
        governance = new GovernanceService(config.settings(), vkApiClient);
        policy = new CommandPolicyService(config.settings());
        router = new VkMessageRouter(config.settings(), vkApiClient, adminRegistry, ticketService, pendingReplyService, pendingActionService, policy, console, governance, relay, auditService, i18n, matrix);

        poll = new VkLongPollService(vkApiClient, config.settings().groupId(), config.settings().pollInterval(), m -> {
            lastIncomingMessageAt = System.currentTimeMillis();
            router.onMessage(m);
        });
        lastPollStartAt = System.currentTimeMillis();
        poll.start();

        if (fromReload) {
            relay.event("🔁 OrdaVK Manager reloaded");
        }
    }

    public boolean isLongPollActive() {
        return poll != null && poll.isRunning();
    }

    public int getPendingRepliesCount() {
        return pendingReplyService == null ? 0 : pendingReplyService.size();
    }

    public int getPendingActionsCount() {
        return pendingActionService == null ? 0 : pendingActionService.size();
    }

    public String doctorReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("[OrdaVK Doctor]\n");
        sb.append(config.settings().token() == null || config.settings().token().isBlank() ? "❌ VK token не задан\n" : "✅ VK token задан\n");
        sb.append(config.settings().groupId() <= 0 ? "❌ group-id не задан\n" : "✅ group-id: " + config.settings().groupId() + "\n");
        sb.append(config.settings().chats().isEmpty() ? "❌ не настроены VK-чаты\n" : "✅ чатов: " + config.settings().chats().size() + "\n");
        sb.append("✅ storage: admins/tickets/pending/actions инициализированы\n");
        return sb.toString();
    }

    public String statusReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("[OrdaVK Status]\n");
        sb.append("LongPoll: ").append(isLongPollActive() ? "активен" : "остановлен").append('\n');
        sb.append("Последний старт poll: ").append(lastPollStartAt == 0 ? "—" : new Date(lastPollStartAt)).append('\n');
        sb.append("Последнее входящее сообщение: ").append(lastIncomingMessageAt == 0 ? "—" : new Date(lastIncomingMessageAt)).append('\n');
        sb.append("Последняя ошибка: ").append(lastErrorAt == 0 ? "—" : new Date(lastErrorAt)).append('\n');
        sb.append("Pending replies: ").append(getPendingRepliesCount()).append('\n');
        sb.append("Pending actions: ").append(getPendingActionsCount());
        return sb.toString();
    }

    public String testVk() {
        try {
            vkApiClient.call("users.get", Map.of("user_ids", "1"));
            return "✅ VK API доступен";
        } catch (Exception e) {
            lastErrorAt = System.currentTimeMillis();
            return "❌ Ошибка VK API: " + e.getMessage();
        }
    }

    @Override
    public void onDisable() {
        if (poll != null) poll.stop();
        if (adminRegistry != null) adminRegistry.save();
        if (ticketService != null) ticketService.save();
        if (pendingReplyService != null) pendingReplyService.save();
        if (pendingActionService != null) pendingActionService.save();
        if (auditService != null) auditService.save();
    }
}
