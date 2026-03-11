package ordacraft.vk;

import ordacraft.vk.admin.AdminRegistry;
import ordacraft.vk.admin.Role;
import ordacraft.vk.bridge.VkMessageRouter;
import ordacraft.vk.command.CommandPolicyService;
import ordacraft.vk.command.OrdaVkControlCommand;
import ordacraft.vk.command.TicketPlayerCommands;
import ordacraft.vk.config.ConfigManager;
import ordacraft.vk.governance.GovernanceService;
import ordacraft.vk.listener.DangerousCommandListener;
import ordacraft.vk.listener.JoinQuitListener;
import ordacraft.vk.service.*;
import ordacraft.vk.storage.YamlFileStore;
import ordacraft.vk.support.PendingReplyService;
import ordacraft.vk.support.SupportTicketService;
import ordacraft.vk.vk.api.VkApiClient;
import ordacraft.vk.vk.polling.VkLongPollService;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

public class OrdaVKPlugin extends JavaPlugin {
    private AdminRegistry adminRegistry;
    private SupportTicketService ticketService;
    private PendingReplyService pendingReplyService;
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

    @Override
    public void onEnable() {
        config = new ConfigManager(this);
        config.reload();

        Path data = getDataFolder().toPath();
        adminRegistry = new AdminRegistry(new YamlFileStore(data.resolve("admins.yml")));
        ticketService = new SupportTicketService(new YamlFileStore(data.resolve("tickets.yml")));
        pendingReplyService = new PendingReplyService(new YamlFileStore(data.resolve("pending-replies.yml")));
        auditService = new AuditService(new YamlFileStore(data.resolve("audit-log.yml")));

        adminRegistry.load();
        ticketService.load();
        pendingReplyService.load();

        rebuildRuntime(false);

        var ticketsCommand = new TicketPlayerCommands(ticketService, config.settings().supportCooldown(), config.settings().supportMaxOpen(), i18n,
                msg -> config.settings().chats().stream().filter(c -> c.mode().name().equals("SUPPORT")).forEach(c -> {
                    try { vkApiClient.send(c.id(), msg); } catch (Exception ignored) {}
                }));

        if (getCommand("helpop") != null) getCommand("helpop").setExecutor(ticketsCommand);
        if (getCommand("report") != null) getCommand("report").setExecutor(ticketsCommand);
        if (getCommand("ordavk") != null) getCommand("ordavk").setExecutor(new OrdaVkControlCommand(this));

        getServer().getPluginManager().registerEvents(new JoinQuitListener(relay, pendingReplyService, i18n), this);
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
        relay = new EventRelayService(config.settings(), vkApiClient);
        console = new ConsoleDispatchService(this);
        governance = new GovernanceService(config.settings(), vkApiClient);
        policy = new CommandPolicyService(config.settings());
        router = new VkMessageRouter(config.settings(), vkApiClient, adminRegistry, ticketService, pendingReplyService, policy, console, governance, relay, i18n, matrix);

        poll = new VkLongPollService(vkApiClient, config.settings().groupId(), config.settings().pollInterval(), router::onMessage);
        poll.start();

        if (fromReload) {
            relay.event("🔁 OrdaVK Manager reloaded");
        }
    }

    @Override
    public void onDisable() {
        if (poll != null) poll.stop();
        if (adminRegistry != null) adminRegistry.save();
        if (ticketService != null) ticketService.save();
        if (pendingReplyService != null) pendingReplyService.save();
        if (auditService != null) auditService.save();
    }
}
