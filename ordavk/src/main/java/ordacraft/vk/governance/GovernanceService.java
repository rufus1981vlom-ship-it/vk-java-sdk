package ordacraft.vk.governance;

import ordacraft.vk.config.PluginSettings;
import ordacraft.vk.vk.api.VkApiClient;

public class GovernanceService {
    public enum RemoveStatus { REMOVED, NOT_FOUND, NO_PERMISSIONS, API_ERROR }

    public record RemovalStats(int removed, int notFound, int noPermissions, int apiErrors) {
        public int failed() {
            return notFound + noPermissions + apiErrors;
        }
    }

    private final PluginSettings settings;
    private final VkApiClient api;

    public GovernanceService(PluginSettings settings, VkApiClient api) {
        this.settings = settings;
        this.api = api;
    }

    public RemovalStats removeUserFromAllChats(long targetVkId) {
        int removed = 0;
        int notFound = 0;
        int noPermissions = 0;
        int apiErrors = 0;

        for (var c : settings.chats()) {
            long chatId = c.id() - 2_000_000_000L;
            RemoveStatus status = api.removeChatUserDetailed(chatId, targetVkId);
            switch (status) {
                case REMOVED -> removed++;
                case NOT_FOUND -> notFound++;
                case NO_PERMISSIONS -> noPermissions++;
                case API_ERROR -> apiErrors++;
            }
        }

        return new RemovalStats(removed, notFound, noPermissions, apiErrors);
    }
}
