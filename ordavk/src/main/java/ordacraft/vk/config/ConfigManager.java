package ordacraft.vk.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

public class ConfigManager {
    private final JavaPlugin plugin;
    private PluginSettings settings;

    public ConfigManager(JavaPlugin plugin) { this.plugin = plugin; }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        var cfg = plugin.getConfig();

        List<VkChatConfig> chats = new ArrayList<>();
        List<Map<?, ?>> rawChats = cfg.getMapList("vk.chats");
        for (Map<?, ?> c : rawChats) {
            try {
                chats.add(new VkChatConfig(Long.parseLong(c.get("id").toString()), ChatMode.from(c.get("mode").toString())));
            } catch (Exception ignored) {}
        }

        Set<String> allowed = normalize(cfg.getStringList("vk.cmd-policy.allowed"));
        Set<String> blocked = normalize(cfg.getStringList("vk.cmd-policy.blocked"));
        Set<String> allowedRoles = normalize(cfg.getStringList("vk.cmd-policy.allowed-roles"));
        Set<Long> protectedUsers = cfg.getLongList("vk.protected-users").stream().collect(Collectors.toSet());

        settings = new PluginSettings(
                cfg.getString("vk.token", ""),
                cfg.getInt("vk.group-id", 0),
                cfg.getString("vk.api-version", "5.199"),
                cfg.getInt("vk.poll-interval-seconds", 2),
                chats,
                cfg.getString("vk.cmd-policy.mode", "whitelist"),
                allowed, blocked, allowedRoles,
                cfg.getInt("support.cooldown-seconds", 60),
                cfg.getInt("support.max-open-tickets-per-player", 3),
                cfg.getBoolean("support.auto-close-on-reply", false),
                protectedUsers,
                cfg.getBoolean("vk.allow-protected-removal", false)
        );
    }

    private Set<String> normalize(List<String> input){
        return input.stream().map(s->s.toLowerCase(Locale.ROOT).trim()).filter(s->!s.isEmpty()).collect(Collectors.toSet());
    }

    public PluginSettings settings(){ return settings; }
}
