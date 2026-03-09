package ordacraft.vk;

import ordacraft.vk.admin.Role;
import ordacraft.vk.admin.RoleService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoleServiceTest {
    private final RoleService s = new RoleService();

    @Test void hierarchy(){
        assertFalse(s.canAssign(Role.HELPER, Role.HELPER));
        assertFalse(s.canAssign(Role.MODER, Role.HELPER));
        assertTrue(s.canAssign(Role.ADMIN, Role.HELPER));
        assertTrue(s.canAssign(Role.ADMIN, Role.MODER));
        assertFalse(s.canAssign(Role.ADMIN, Role.ADMIN));
        assertTrue(s.canAssign(Role.STAFF, Role.ADMIN));
        assertFalse(s.canAssign(Role.STAFF, Role.STAFF));
        assertTrue(s.canAssign(Role.CHIEF, Role.CHIEF));

        assertFalse(s.canManage(Role.ADMIN, Role.ADMIN));
        assertFalse(s.canManage(Role.ADMIN, Role.STAFF));
        assertTrue(s.canManage(Role.STAFF, Role.ADMIN));
    }
}
