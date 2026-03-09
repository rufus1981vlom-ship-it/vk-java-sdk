package ordacraft.vk;

import ordacraft.vk.admin.AdminRegistry;
import ordacraft.vk.bridge.VkMessageRouter;
import ordacraft.vk.command.CommandPolicyService;
import ordacraft.vk.command.TicketPlayerCommands;
import ordacraft.vk.config.ConfigManager;
import ordacraft.vk.governance.GovernanceService;
import ordacraft.vk.listener.JoinQuitListener;
import ordacraft.vk.service.*;
import ordacraft.vk.storage.YamlFileStore;
import ordacraft.vk.support.PendingReplyService;
import ordacraft.vk.support.SupportTicketService;
import ordacraft.vk.vk.api.VkApiClient;
import ordacraft.vk.vk.polling.VkLongPollService;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

public class OrdaVKPlugin extends JavaPlugin {
    private AdminRegistry adminRegistry;
    private SupportTicketService ticketService;
    private PendingReplyService pendingReplyService;
    private AuditService auditService;
    private VkLongPollService poll;

    @Override
    public void onEnable() {
        ConfigManager config = new ConfigManager(this);
        config.reload();

        Path data = getDataFolder().toPath();
        adminRegistry = new AdminRegistry(new YamlFileStore(data.resolve("admins.yml")));
        ticketService = new SupportTicketService(new YamlFileStore(data.resolve("tickets.yml")));
        pendingReplyService = new PendingReplyService(new YamlFileStore(data.resolve("pending-replies.yml")));
        auditService = new AuditService(new YamlFileStore(data.resolve("audit-log.yml")));

        adminRegistry.load(); ticketService.load(); pendingReplyService.load();

        LocalizationService i18n = new LocalizationService(config.settings().language());
        PermissionMatrixService matrix = new PermissionMatrixService(config.settings().commandMinRoles());

        VkApiClient vkApiClient = new VkApiClient(config.settings().token(), config.settings().apiVersion());
        EventRelayService relay = new EventRelayService(config.settings(), vkApiClient);
        ConsoleDispatchService console = new ConsoleDispatchService(this);
        GovernanceService governance = new GovernanceService(config.settings(), vkApiClient, adminRegistry);
        CommandPolicyService policy = new CommandPolicyService(config.settings());
        VkMessageRouter router = new VkMessageRouter(config.settings(), vkApiClient, adminRegistry, ticketService, pendingReplyService, policy, console, governance, relay, i18n, matrix);

        poll = new VkLongPollService(router::onMessage);
        poll.start();

        var ticketsCommand = new TicketPlayerCommands(ticketService, config.settings().supportCooldown(), config.settings().supportMaxOpen(), i18n,
                msg -> config.settings().chats().stream().filter(c -> c.mode().name().equals("SUPPORT")).forEach(c -> {
                    try { vkApiClient.send(c.id(), msg); } catch (Exception ignored) {}
                }));

        getCommand("helpop").setExecutor(ticketsCommand);
        getCommand("report").setExecutor(ticketsCommand);
        getServer().getPluginManager().registerEvents(new JoinQuitListener(relay, pendingReplyService, i18n), this);
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
