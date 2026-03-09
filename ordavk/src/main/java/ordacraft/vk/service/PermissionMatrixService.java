package ordacraft.vk.service;

import ordacraft.vk.admin.Role;

import java.util.Locale;
import java.util.Map;

public class PermissionMatrixService {
    private final Map<String, Role> minRoleByCommand;

    public PermissionMatrixService(Map<String, Role> minRoleByCommand) {
        this.minRoleByCommand = minRoleByCommand;
    }

    public boolean allowed(Role actor, String commandKey) {
        if (actor == null || commandKey == null || commandKey.isBlank()) {
            return false;
        }

        Role min = minRoleByCommand.get(commandKey);
        if (min == null) {
            min = minRoleByCommand.get(commandKey.toLowerCase(Locale.ROOT));
        }
        if (min == null) {
            min = Role.CHIEF;
        }

        // Требуется минимум указанной роли: ADMIN допускает ADMIN/STAFF/CHIEF и т.д.
        return actor.ordinal() >= min.ordinal();
    }
}
