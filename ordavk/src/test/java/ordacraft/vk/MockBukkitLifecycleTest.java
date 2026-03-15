package ordacraft.vk;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class MockBukkitLifecycleTest {
    private ServerMock server;

    @AfterEach void tearDown(){ MockBukkit.unmock(); }

    @Test void pluginLoads(){
        server = MockBukkit.mock();
        var plugin = MockBukkit.load(OrdaVKPlugin.class);
        assertNotNull(plugin);
    }
}
