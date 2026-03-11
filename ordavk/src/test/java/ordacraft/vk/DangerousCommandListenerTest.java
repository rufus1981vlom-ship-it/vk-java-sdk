package ordacraft.vk;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import ordacraft.vk.admin.Role;
import ordacraft.vk.config.ChatMode;
import ordacraft.vk.config.PluginSettings;
import ordacraft.vk.config.VkChatConfig;
import ordacraft.vk.governance.GovernanceService;
import ordacraft.vk.listener.DangerousCommandListener;
import ordacraft.vk.service.EventRelayService;
import ordacraft.vk.vk.api.VkApiClient;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DangerousCommandListenerTest {
    private ServerMock server;
    private TestVkApi api;
    private DangerousCommandListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        api = new TestVkApi();
        PluginSettings settings = new PluginSettings("", 1, "5.199", 1,
                List.of(new VkChatConfig(2000000002L, ChatMode.EVENTS)),
                "whitelist", Set.of("say"), Set.of("op"), Set.of("chief"),
                10, 5, false, Set.of(), false, "ru", new HashMap<>());
        listener = new DangerousCommandListener(new EventRelayService(settings, api));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void logsDangerousPlayerCommandsAndLpStructured() {
        var player = server.addPlayer("Steve");
        listener.onPlayerCommand(new PlayerCommandPreprocessEvent(player, "/ban Alex grief"));
        listener.onPlayerCommand(new PlayerCommandPreprocessEvent(player, "/lp user Alex parent set admin"));
        listener.onPlayerCommand(new PlayerCommandPreprocessEvent(player, "/say hello"));

        assertTrue(api.events.stream().anyMatch(m -> m.contains("Raw command: ban Alex grief") && m.contains("Инициатор: Steve")));
        assertTrue(api.events.stream().anyMatch(m -> m.contains("Группа: Alex -> admin") && m.contains("Инициатор: Steve")));
        assertFalse(api.events.stream().anyMatch(m -> m.contains("Raw command: lp user Alex parent set admin")));
        assertFalse(api.events.stream().anyMatch(m -> m.contains("Raw command: say hello")));
    }

    @Test
    void logsDangerousConsoleCommand() {
        listener.onServerCommand(new ServerCommandEvent(server.getConsoleSender(), "op Steve"));
        assertTrue(api.events.stream().anyMatch(m -> m.contains("Raw command: op Steve") && m.contains("Инициатор: CONSOLE")));
    }

    private static final class TestVkApi extends VkApiClient {
        private final List<String> events = new ArrayList<>();

        private TestVkApi() {
            super("", "5.199");
        }

        @Override
        public void send(long peerId, String message) {
            events.add(message);
        }

        @Override
        public GovernanceService.RemoveStatus removeChatUserDetailed(long chatId, long memberId) {
            return GovernanceService.RemoveStatus.REMOVED;
        }
    }
}
