package ordacraft.vk.admin;

public enum Role {
    HELPER, MODER, ADMIN, STAFF, CHIEF;

    public static Role fromString(String value) {
        return Role.valueOf(value.trim().toUpperCase());
    }

    public boolean higherThan(Role other) {
        return this.ordinal() > other.ordinal();
    }
}
