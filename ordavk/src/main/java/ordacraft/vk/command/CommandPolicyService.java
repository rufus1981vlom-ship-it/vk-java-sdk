package ordacraft.vk.command;

import ordacraft.vk.admin.Role;
import ordacraft.vk.config.PluginSettings;

import java.util.Locale;

public class CommandPolicyService {
    private final PluginSettings settings;
    public CommandPolicyService(PluginSettings settings){ this.settings=settings; }

    public boolean canUseRaw(Role role){ return settings.cmdAllowedRoles().contains(role.name().toLowerCase(Locale.ROOT)); }

    public boolean allowsRaw(Role role, String command) {
        // Роли из cmdAllowedRoles имеют право на raw-команды без доп. policy-фильтра
        // (иначе события dangerous/raw и LP-структурные события могут не дойти до audit/event relay).
        if (canUseRaw(role)) return true;
        return allows(command);
    }

    public boolean allows(String command){
        String cmd = command.trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        String first = cmd.split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (settings.cmdBlocked().contains(first)) return false;
        String mode = settings.cmdPolicyMode().toLowerCase(Locale.ROOT);
        if ("whitelist".equals(mode)) return settings.cmdAllowed().contains(first);
        if ("blacklist".equals(mode)) return !settings.cmdBlocked().contains(first);
        return false;
    }
}
