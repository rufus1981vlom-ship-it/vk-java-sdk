package ordacraft.vk.config;

import ordacraft.vk.admin.Role;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record PluginSettings(String token, int groupId, String apiVersion, int pollInterval,
                             List<VkChatConfig> chats, String cmdPolicyMode,
                             Set<String> cmdAllowed, Set<String> cmdBlocked, Set<String> cmdAllowedRoles,
                             int supportCooldown, int supportMaxOpen,
                             boolean autoCloseOnReply, Set<Long> protectedUsers, boolean allowProtectedRemoval,
                             String language,
                             Map<String, Role> commandMinRoles) {}
