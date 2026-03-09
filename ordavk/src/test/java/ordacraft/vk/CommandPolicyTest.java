package ordacraft.vk;

import ordacraft.vk.admin.Role;
import ordacraft.vk.command.CommandPolicyService;
import ordacraft.vk.config.PluginSettings;
import ordacraft.vk.config.VkChatConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CommandPolicyTest {
    @Test void whitelistAndBlacklistAndSlash(){
        PluginSettings s = new PluginSettings("",0,"",1, List.of(), "whitelist", Set.of("say"), Set.of("op"), Set.of("chief"), 1,1,false, Set.of(), false);
        CommandPolicyService c = new CommandPolicyService(s);
        assertTrue(c.allows("/say hi"));
        assertFalse(c.allows("/op Notch"));
        assertFalse(c.allows("/ban x"));
        assertTrue(c.canUseRaw(Role.CHIEF));
        assertFalse(c.canUseRaw(Role.ADMIN));
    }
}
