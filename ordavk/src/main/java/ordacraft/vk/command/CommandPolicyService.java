package ordacraft.vk.command;

import ordacraft.vk.admin.Role;
import ordacraft.vk.config.PluginSettings;

import java.util.Locale;

public class CommandPolicyService {
    private final PluginSettings settings;
    public CommandPolicyService(PluginSettings settings){ this.settings=settings; }

    public boolean canUseRaw(Role role){ return settings.cmdAllowedRoles().contains(role.name().toLowerCase(Locale.ROOT)); }

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
