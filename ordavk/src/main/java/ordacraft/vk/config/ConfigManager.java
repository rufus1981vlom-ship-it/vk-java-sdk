package ordacraft.vk.config;

import ordacraft.vk.admin.Role;
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

        Map<String, Role> matrix = defaultMatrix();
        if (cfg.isConfigurationSection("permissions.command-min-role")) {
            for (String key : cfg.getConfigurationSection("permissions.command-min-role").getKeys(false)) {
                try {
                    matrix.put(key, Role.fromString(cfg.getString("permissions.command-min-role." + key, "CHIEF")));
                } catch (Exception ignored) {}
            }
        }

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
                clampJoinDelay(cfg.getInt("support.join-delivery-delay-ticks", 50)),
                cfg.getBoolean("support.auto-close-on-reply", false),
                protectedUsers,
                cfg.getBoolean("vk.allow-protected-removal", false),
                cfg.getString("general.language", "ru"),
                matrix
        );
    }

    private int clampJoinDelay(int ticks) {
        return Math.max(40, Math.min(60, ticks));
    }

    private Set<String> normalize(List<String> input){
        return input.stream().map(s->s.toLowerCase(Locale.ROOT).trim()).filter(s->!s.isEmpty()).collect(Collectors.toSet());
    }

    private Map<String, Role> defaultMatrix() {
        Map<String, Role> map = new HashMap<>();
        map.put("manage.help", Role.HELPER);
        map.put("manage.online", Role.HELPER);
        map.put("manage.status", Role.HELPER);
        map.put("manage.check", Role.HELPER);
        map.put("manage.kick", Role.MODER);
        map.put("manage.mute", Role.MODER);
        map.put("manage.ban", Role.MODER);
        map.put("manage.admins", Role.ADMIN);
        map.put("manage.admin.info", Role.ADMIN);
        map.put("manage.admin.add", Role.ADMIN);
        map.put("manage.admin.set", Role.ADMIN);
        map.put("manage.admin.remove", Role.ADMIN);
        map.put("manage.admin.rname", Role.ADMIN);
        map.put("manage.cmd", Role.CHIEF);

        map.put("support.list", Role.HELPER);
        map.put("support.info", Role.HELPER);
        map.put("support.close", Role.HELPER);
        map.put("support.reply", Role.HELPER);
        return map;
    }

    public PluginSettings settings(){ return settings; }
}
