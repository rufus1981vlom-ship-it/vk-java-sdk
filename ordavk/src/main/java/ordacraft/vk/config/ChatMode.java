package ordacraft.vk.config;

public enum ChatMode {
    MANAGE, // legacy alias, treated as MMANAGE
    AMANAGE,
    MMANAGE,
    EVENTS,
    SUPPORT,
    IGNORE;

    public static ChatMode from(String s) {
        String normalized = s.trim().toUpperCase();
        if ("MANAGE".equals(normalized)) {
            return MMANAGE;
        }
        return ChatMode.valueOf(normalized);
    }
}
