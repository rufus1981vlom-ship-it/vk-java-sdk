package ordacraft.vk;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import ordacraft.vk.admin.AdminRecord;
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
        matrix.put("manage.admin.rname", Role.ADMIN);
        matrix.put("manage.cmd", Role.CHIEF);
        matrix.put("support.list", Role.HELPER);
        matrix.put("support.info", Role.HELPER);
        matrix.put("support.close", Role.HELPER);
        matrix.put("support.reply", Role.HELPER);

        settings = new PluginSettings("", 1, "5.199", 1,
                List.of(new VkChatConfig(2000000001L, ChatMode.MANAGE), new VkChatConfig(2000000002L, ChatMode.EVENTS)),
                "whitelist", Set.of("say"), Set.of("op"), Set.of("chief"),
                10, 5, 50, false, Set.of(), false, "ru", matrix);

        api = new TestVkApi();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }


    @Test
    void helpWorksWhenManageChatConfiguredAsChatIdWithoutPeerOffset() {
        Map<String, Role> matrix = new HashMap<>(settings.commandMinRoles());
        PluginSettings chatIdSettings = new PluginSettings("", 1, "5.199", 1,
                List.of(new VkChatConfig(1L, ChatMode.MANAGE), new VkChatConfig(2L, ChatMode.EVENTS)),
                "whitelist", Set.of("say"), Set.of("op"), Set.of("chief"),
                10, 5, 50, false, Set.of(), false, "ru", matrix);

        SupportTicketService tickets = new SupportTicketService(new YamlFileStore(Path.of("/tmp/ordavk-tickets2.yml")));
        PendingReplyService pending = new PendingReplyService(new YamlFileStore(Path.of("/tmp/ordavk-pending2.yml")));
        VkMessageRouter router = new VkMessageRouter(
                chatIdSettings,
                api,
                admins,
                tickets,
                pending,
                new CommandPolicyService(chatIdSettings),
                new TestConsoleDispatchService(api),
                new GovernanceService(chatIdSettings, api),
                new EventRelayService(chatIdSettings, api),
                new LocalizationService("ru"),
                new PermissionMatrixService(chatIdSettings.commandMinRoles())
        );

        admins.upsert(10L, "Boss", Role.CHIEF);
        router.onMessage(new VkIncomingMessage(2000000001L, 10L, "!help"));
        assertTrue(api.lastReply().contains("!online"));
    }


    @Test
    void plainTextMessagesAreIgnoredInManageAndSupportChats() {
        PluginSettings withSupport = new PluginSettings("", 1, "5.199", 1,
                List.of(new VkChatConfig(2000000001L, ChatMode.MANAGE),
                        new VkChatConfig(2000000002L, ChatMode.EVENTS),
                        new VkChatConfig(2000000003L, ChatMode.SUPPORT)),
                "whitelist", Set.of("say"), Set.of("op"), Set.of("chief"),
                10, 5, 50, false, Set.of(), false, "ru", settings.commandMinRoles());

        SupportTicketService tickets = new SupportTicketService(new YamlFileStore(Path.of("/tmp/ordavk-tickets3.yml")));
        PendingReplyService pending = new PendingReplyService(new YamlFileStore(Path.of("/tmp/ordavk-pending3.yml")));
        VkMessageRouter router = new VkMessageRouter(
                withSupport,
                api,
                admins,
                tickets,
                pending,
                new CommandPolicyService(withSupport),
                new TestConsoleDispatchService(api),
                new GovernanceService(withSupport, api),
                new EventRelayService(withSupport, api),
                new LocalizationService("ru"),
                new PermissionMatrixService(withSupport.commandMinRoles())
        );

        admins.upsert(10L, "Boss", Role.CHIEF);

        int before = api.replies.size();
        router.onMessage(new VkIncomingMessage(2000000001L, 10L, "просто текст"));
        router.onMessage(new VkIncomingMessage(2000000003L, 10L, "и тут просто текст"));

        assertEquals(before, api.replies.size());
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

        router.onMessage(new VkIncomingMessage(2000000001L, 100L, "!admin set 200 Steve admin"));
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
    void adminSetOldFormatIsRejected() {
        VkMessageRouter router = newRouter();
        admins.upsert(100L, "Chief", Role.CHIEF);
        admins.upsert(200L, "Steve", Role.HELPER);

        router.onMessage(new VkIncomingMessage(2000000001L, 100L, "!admin set 200 admin"));
        assertTrue(api.lastReply().contains("!admin set <vk_id> <nick> <role>"));
        assertEquals(Role.HELPER, admins.find(200L).orElseThrow().role());
    }

    @Test
    void rnameRenamesAdminNick() {
        VkMessageRouter router = newRouter();
        admins.upsert(100L, "Chief", Role.CHIEF);
        admins.upsert(300L, "OldNick", Role.MODER);

        router.onMessage(new VkIncomingMessage(2000000001L, 100L, "!рнейм 300 NewNick"));
        assertEquals("NewNick", admins.find(300L).orElseThrow().mcNick());
        assertTrue(api.lastReply().contains("Ник обновл"));
    }

    @Test
    void adminSetSupportsMentionAndNickUpdate() {
        VkMessageRouter router = newRouter();
        admins.upsert(100L, "Chief", Role.CHIEF);
        admins.upsert(26255262L, "OldNick", Role.HELPER);

        router.onMessage(new VkIncomingMessage(2000000001L, 100L, "!admin set @id26255262 General_Kutuzov admin"));
        AdminRecord updated = admins.find(26255262L).orElseThrow();

        assertEquals(Role.ADMIN, updated.role());
        assertEquals("General_Kutuzov", updated.mcNick());
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


    @Test
    void kickMuteBanAndAdminRemoveAreLoggedToEvents() {
        VkMessageRouter router = newRouter();
        admins.upsert(500L, "Bolat", Role.CHIEF);
        admins.upsert(501L, "Steve", Role.HELPER);

        router.onMessage(new VkIncomingMessage(2000000001L, 500L, "!kick Steve flood"));
        router.onMessage(new VkIncomingMessage(2000000001L, 500L, "!mute Steve 30m мат"));
        router.onMessage(new VkIncomingMessage(2000000001L, 500L, "!ban Steve 7d читы"));
        router.onMessage(new VkIncomingMessage(2000000001L, 500L, "!ban Steve читы"));
        router.onMessage(new VkIncomingMessage(2000000001L, 500L, "!admin remove 501"));

        var events = api.events();
        assertTrue(events.stream().anyMatch(m -> m.contains("Kick: Steve") && m.contains("Инициатор: Bolat")));
        assertTrue(events.stream().anyMatch(m -> m.contains("TempMute: Steve") && m.contains("30m") && m.contains("Инициатор: Bolat")));
        assertTrue(events.stream().anyMatch(m -> m.contains("TempBan: Steve") && m.contains("7d") && m.contains("Инициатор: Bolat")));
        assertTrue(events.stream().anyMatch(m -> m.contains("Ban: Steve навсегда") && m.contains("Инициатор: Bolat")));
        assertTrue(events.stream().anyMatch(m -> m.contains("Администратор снят: VK 501") && m.contains("Инициатор: 500")));
    }

    @Test
    void rawDangerousAndLpGroupCommandsAreLoggedWithoutDuplicates() {
        VkMessageRouter router = newRouter();
        admins.upsert(600L, "Bolat", Role.CHIEF);

        router.onMessage(new VkIncomingMessage(2000000001L, 600L, "!cmd lp user Steve parent set хан"));
        router.onMessage(new VkIncomingMessage(2000000001L, 600L, "!cmd ban Steve grief"));
        router.onMessage(new VkIncomingMessage(2000000001L, 600L, "!cmd say hello"));

        var events = api.events();
        assertTrue(events.stream().anyMatch(m -> m.contains("Группа: Steve -> хан") && m.contains("Инициатор: Bolat")));
        assertTrue(events.stream().anyMatch(m -> m.contains("Raw command: ban Steve grief") && m.contains("Инициатор: Bolat")));
        assertFalse(events.stream().anyMatch(m -> m.contains("Raw command: say hello")));

        long rawLpCount = events.stream().filter(m -> m.contains("Raw command: lp user Steve parent set хан")).count();
        assertEquals(0, rawLpCount);
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
        private record Sent(long peerId, String message) {}
        private final List<Sent> sent = new ArrayList<>();
        private final List<String> replies = new ArrayList<>();
        private final List<String> consoleCommands = new ArrayList<>();

        private TestVkApi() {
            super("", "5.199");
        }

        @Override
        public void send(long peerId, String message) {
            sent.add(new Sent(peerId, message));
            replies.add(message);
        }

        @Override
        public GovernanceService.RemoveStatus removeChatUserDetailed(long chatId, long memberId) {
            if (chatId % 2 == 0) return GovernanceService.RemoveStatus.REMOVED;
            return GovernanceService.RemoveStatus.NOT_FOUND;
        }

        private List<String> events() {
            return sent.stream().filter(s -> s.peerId() == 2000000002L).map(s -> s.message()).toList();
        }

        private String lastReply() {
            return replies.isEmpty() ? "" : replies.get(replies.size() - 1);
        }
    }
}
