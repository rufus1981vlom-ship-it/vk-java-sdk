package ordacraft.vk.support;

import java.util.UUID;

public record PendingAction(int id,
                            UUID playerUuid,
                            String playerNick,
                            String actionType,
                            String command,
                            long issuedByVkId,
                            String issuedByRole,
                            long createdAt,
                            String status,
                            int attempts,
                            Long appliedAt,
                            String errorMessage) {
}
