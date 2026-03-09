package ordacraft.vk;

import ordacraft.vk.admin.Role;
import ordacraft.vk.service.PermissionMatrixService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PermissionMatrixServiceTest {
    @Test
    void allowsAnyRoleAtOrAboveMinimum() {
        PermissionMatrixService matrix = new PermissionMatrixService(Map.of(
                "manage.admin.add", Role.ADMIN,
                "manage.kick", Role.MODER
        ));

        assertTrue(matrix.allowed(Role.ADMIN, "manage.admin.add"));
        assertTrue(matrix.allowed(Role.STAFF, "manage.admin.add"));
        assertTrue(matrix.allowed(Role.CHIEF, "manage.admin.add"));
        assertFalse(matrix.allowed(Role.MODER, "manage.admin.add"));

        assertTrue(matrix.allowed(Role.MODER, "manage.kick"));
        assertTrue(matrix.allowed(Role.ADMIN, "manage.kick"));
    }

    @Test
    void handlesNullAndUnknownSafely() {
        PermissionMatrixService matrix = new PermissionMatrixService(Map.of("manage.help", Role.HELPER));
        assertFalse(matrix.allowed(null, "manage.help"));
        assertFalse(matrix.allowed(Role.CHIEF, null));
        assertFalse(matrix.allowed(Role.CHIEF, ""));
        assertFalse(matrix.allowed(Role.ADMIN, "unknown.command"));
    }
}
