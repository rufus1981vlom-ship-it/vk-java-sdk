package org.bukkit.configuration;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public class ConfigurationSection {
    public Set<String> getKeys(boolean deep) {
        return Collections.emptySet();
    }

    public ConfigurationSection getConfigurationSection(String path) {
        return null;
    }

    public boolean getBoolean(String path, boolean def) {
        return def;
    }

    public int getInt(String path, int def) {
        return def;
    }

    public long getLong(String path, long def) {
        return def;
    }

    public String getString(String path, String def) {
        return def;
    }

    public String getString(String path) {
        return null;
    }

    public List<String> getStringList(String path) {
        return Collections.emptyList();
    }

    public boolean contains(String path) {
        return false;
    }

    public void set(String path, Object value) {
    }
}
