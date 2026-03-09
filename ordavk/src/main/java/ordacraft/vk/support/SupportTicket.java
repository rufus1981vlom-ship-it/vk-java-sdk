package ordacraft.vk.support;

import java.util.UUID;

public record SupportTicket(int id, TicketType type, UUID playerUuid, String playerName, String targetPlayerName,
                            String text, long createdAt, TicketStatus status, Long responderVkId, boolean closed) {}
