package ordacraft.vk.config;

public enum ChatMode { MANAGE, EVENTS, SUPPORT, IGNORE;
    public static ChatMode from(String s){ return ChatMode.valueOf(s.trim().toUpperCase());}
}
