package ordacraft.vk.admin;

public class RoleService {
    public boolean canManage(Role actor, Role target) { return actor.higherThan(target); }
    public boolean canAssign(Role actor, Role assign) {
        return switch (actor) {
            case HELPER, MODER -> false;
            case ADMIN -> assign == Role.HELPER || assign == Role.MODER;
            case STAFF -> assign == Role.HELPER || assign == Role.MODER || assign == Role.ADMIN;
            case CHIEF -> true;
        };
    }
}
