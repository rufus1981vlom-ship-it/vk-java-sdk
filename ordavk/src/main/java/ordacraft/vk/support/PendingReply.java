package ordacraft.vk.support;

import java.util.UUID;

public record PendingReply(UUID playerUuid, String playerName, int ticketId, String replyText,
                           long responderVkId, String responderName, long createdAt) {}
