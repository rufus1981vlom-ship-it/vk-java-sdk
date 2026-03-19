package org.bukkit.command;

public interface CommandSender {
    default void sendMessage(String message) {
    }

    default boolean hasPermission(String permission) {
        return true;
    }
}
