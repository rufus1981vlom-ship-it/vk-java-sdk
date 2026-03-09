package ordacraft.vk.service;

import ordacraft.vk.admin.Role;

import java.util.Map;

public class PermissionMatrixService {
    private final Map<String, Role> minRoleByCommand;

    public PermissionMatrixService(Map<String, Role> minRoleByCommand) {
        this.minRoleByCommand = minRoleByCommand;
    }

    public boolean allowed(Role actor, String commandKey) {
        Role min = minRoleByCommand.getOrDefault(commandKey, Role.CHIEF);
        return actor.ordinal() >= min.ordinal();
    }
}
