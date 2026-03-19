package org.bukkit.entity;

public interface Player {
    default String getName() {
        return "";
    }

    default double getHealth() {
        return 20.0;
    }
}
