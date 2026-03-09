package ordacraft.vk.governance;

import ordacraft.vk.admin.AdminRegistry;
import ordacraft.vk.admin.Role;
import ordacraft.vk.config.PluginSettings;
import ordacraft.vk.vk.api.VkApiClient;

public class GovernanceService {
    private final PluginSettings settings; private final VkApiClient api; private final AdminRegistry registry;
    public GovernanceService(PluginSettings settings, VkApiClient api, AdminRegistry registry){ this.settings=settings; this.api=api; this.registry=registry; }

    public String kickEverywhere(Role actorRole, long targetVkId, String reason){
        var target = registry.find(targetVkId);
        if (target.isPresent() && !actorRole.higherThan(target.get().role())) return "❌ Target has equal or higher rank";
        if (!settings.allowProtectedRemoval() && settings.protectedUsers().contains(targetVkId)) return "❌ Target is protected";
        int ok=0, fail=0;
        for (var c: settings.chats()) {
            long chatId = c.id() - 2_000_000_000L;
            if (api.removeChatUser(chatId, targetVkId)) ok++; else fail++;
        }
        return "✅ VK kick summary: removed="+ok+", failed="+fail+" | reason="+reason;
    }
}
