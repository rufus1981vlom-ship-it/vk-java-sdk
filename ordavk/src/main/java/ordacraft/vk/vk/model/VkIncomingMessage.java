package ordacraft.vk.vk.model;

public record VkIncomingMessage(long peerId, long fromId, String text) {}
