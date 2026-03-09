package ordacraft.vk;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import ordacraft.vk.admin.AdminRegistry;
import ordacraft.vk.admin.Role;
import ordacraft.vk.bridge.VkMessageRouter;
import ordacraft.vk.command.CommandPolicyService;
import ordacraft.vk.config.ChatMode;
import ordacraft.vk.config.PluginSettings;
import ordacraft.vk.config.VkChatConfig;
import ordacraft.vk.governance.GovernanceService;
import ordacraft.vk.service.ConsoleDispatchService;
import ordacraft.vk.service.EventRelayService;
import ordacraft.vk.service.LocalizationService;
import ordacraft.vk.service.PermissionMatrixService;
import ordacraft.vk.storage.YamlFileStore;
import ordacraft.vk.support.PendingReplyService;
import ordacraft.vk.support.SupportTicketService;
import ordacraft.vk.vk.api.VkApiClient;
import ordacraft.vk.vk.model.VkIncomingMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class VkMessageRouterTest {
    private ServerMock server;
    private TestVkApi api;
    private AdminRegistry admins;
    private PluginSettings settings;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        Path d = Files.createTempDirectory("ordavk-router");
        admins = new AdminRegistry(new YamlFileStore(d.resolve("admins.yml")));
        admins.load();

        Map<String, Role> matrix = new HashMap<>();
        matrix.put("manage.help", Role.HELPER);
        matrix.put("manage.online", Role.HELPER);
        matrix.put("manage.status", Role.HELPER);
        matrix.put("manage.check", Role.HELPER);
        matrix.put("manage.kick", Role.MODER);
        matrix.put("manage.mute", Role.MODER);
        matrix.put("manage.ban", Role.MODER);
        matrix.put("manage.admins", Role.ADMIN);
        matrix.put("manage.admin.info", Role.ADMIN);
        matrix.put("manage.admin.add", Role.ADMIN);
        matrix.put("manage.admin.set", Role.ADMIN);
        matrix.put("manage.admin.remove", Role.ADMIN);
        matrix.put("manage.cmd", Role.CHIEF);
        matrix.put("support.list", Role.HELPER);
        matrix.put("support.info", Role.HELPER);
        matrix.put("support.close", Role.HELPER);
        matrix.put("support.reply", Role.HELPER);

        settings = new PluginSettings("", 1, "5.199", 1,
                List.of(new VkChatConfig(2000000001L, ChatMode.MANAGE), new VkChatConfig(2000000002L, ChatMode.EVENTS)),
                "whitelist", Set.of("say"), Set.of("op"), Set.of("chief"),
                10, 5, false, Set.of(), false, "ru", matrix);

        api = new TestVkApi();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void helpContainsNewCommandsAndNoVkKick() {
        VkMessageRouter router = newRouter();
        admins.upsert(10L, "Boss", Role.CHIEF);
        router.onMessage(new VkIncomingMessage(2000000001L, 10L, "!help"));
        String reply = api.lastReply();
        assertTrue(reply.contains("!online"));
        assertTrue(reply.contains("!status"));
        assertTrue(reply.contains("!check"));
        assertTrue(reply.contains("!admin add"));
        assertFalse(reply.contains("!vk kick"));
    }

    @Test
    void onlineStatusAndCheckCommandsWork() {
        VkMessageRouter router = newRouter();
        admins.upsert(11L, "Helper", Role.HELPER);
        PlayerMock steve = server.addPlayer("Steve");

        router.onMessage(new VkIncomingMessage(2000000001L, 11L, "!online"));
        assertTrue(api.lastReply().contains("Онлайн: 1"));
        assertTrue(api.lastReply().contains("Steve"));

        router.onMessage(new VkIncomingMessage(2000000001L, 11L, "!status"));
        assertTrue(api.lastReply().contains("Онлайн: 1/"));
        assertTrue(api.lastReply().contains("TPS:"));

        router.onMessage(new VkIncomingMessage(2000000001L, 11L, "!check Steve"));
        assertTrue(api.lastReply().contains("Игрок: Steve"));
        assertTrue(api.lastReply().contains("Статус: онлайн"));
        assertNotNull(steve.getUniqueId());
    }

    @Test
    void adminInfoAddSetAndRemoveFlow() {
        VkMessageRouter router = newRouter();
        admins.upsert(100L, "Chief", Role.CHIEF);

        router.onMessage(new VkIncomingMessage(2000000001L, 100L, "!admin add 200 Steve moder"));
        assertTrue(api.lastReply().contains("Админ добавлен"));
        assertTrue(admins.find(200L).isPresent());

        router.onMessage(new VkIncomingMessage(2000000001L, 100L, "!admin info 200"));
        assertTrue(api.lastReply().contains("VK ID: 200"));
        assertTrue(api.lastReply().contains("Роль: moder"));

        router.onMessage(new VkIncomingMessage(2000000001L, 100L, "!admin set 200 admin"));
        assertEquals(Role.ADMIN, admins.find(200L).orElseThrow().role());

        router.onMessage(new VkIncomingMessage(2000000001L, 100L, "!admin remove 200"));
        String summary = api.lastReply();
        assertTrue(summary.contains("Администратор удалён"));
        assertTrue(summary.contains("Игровая группа сброшена: да"));
        assertTrue(summary.contains("Удалён из бесед:"));
        assertTrue(summary.contains("Не удалось:"));
        assertTrue(api.consoleCommands.stream().anyMatch(c -> c.equals("lp user Steve parent set default")));
        assertTrue(admins.find(200L).isEmpty());
    }

    @Test
    void cannotRemoveEqualOrHigherRole() {
        VkMessageRouter router = newRouter();
        admins.upsert(300L, "AdminA", Role.ADMIN);
        admins.upsert(301L, "AdminB", Role.ADMIN);

        router.onMessage(new VkIncomingMessage(2000000001L, 300L, "!admin remove 301"));
        assertTrue(api.lastReply().contains("равной или более высокой"));
        assertTrue(admins.find(301L).isPresent());
    }

    @Test
    void removeWithoutNickStillRemovesFromAdmins() {
        VkMessageRouter router = newRouter();
        admins.upsert(400L, "Chief", Role.CHIEF);
        admins.upsert(401L, "", Role.HELPER);

        router.onMessage(new VkIncomingMessage(2000000001L, 400L, "!admin remove 401"));
        String summary = api.lastReply();
        assertTrue(summary.contains("Игровая группа сброшена: нет"));
        assertTrue(admins.find(401L).isEmpty());
    }

    private VkMessageRouter newRouter() {
        SupportTicketService tickets = new SupportTicketService(new YamlFileStore(Path.of("/tmp/ordavk-tickets.yml")));
        PendingReplyService pending = new PendingReplyService(new YamlFileStore(Path.of("/tmp/ordavk-pending.yml")));
        CommandPolicyService cmdPolicy = new CommandPolicyService(settings);
        GovernanceService governance = new GovernanceService(settings, api);
        EventRelayService relay = new EventRelayService(settings, api);
        return new VkMessageRouter(
                settings,
                api,
                admins,
                tickets,
                pending,
                cmdPolicy,
                new TestConsoleDispatchService(api),
                governance,
                relay,
                new LocalizationService("ru"),
                new PermissionMatrixService(settings.commandMinRoles())
        );
    }

    private static final class TestConsoleDispatchService extends ConsoleDispatchService {
        private final TestVkApi api;

        private TestConsoleDispatchService(TestVkApi api) {
            super(null);
            this.api = api;
        }

        @Override
        public void dispatch(String command) {
            api.consoleCommands.add(command);
        }
    }

    private static final class TestVkApi extends VkApiClient {
        private final List<String> replies = new ArrayList<>();
        private final List<String> consoleCommands = new ArrayList<>();

        private TestVkApi() {
            super("", "5.199");
        }

        @Override
        public void send(long peerId, String message) {
            replies.add(message);
        }

        @Override
        public GovernanceService.RemoveStatus removeChatUserDetailed(long chatId, long memberId) {
            if (chatId % 2 == 0) return GovernanceService.RemoveStatus.REMOVED;
            return GovernanceService.RemoveStatus.NOT_FOUND;
        }

        private String lastReply() {
            return replies.isEmpty() ? "" : replies.get(replies.size() - 1);
        }
    }
}
