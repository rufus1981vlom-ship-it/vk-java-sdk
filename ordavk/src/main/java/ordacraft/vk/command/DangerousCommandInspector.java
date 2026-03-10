package ordacraft.vk.command;

import java.util.Locale;
import java.util.Set;

public class DangerousCommandInspector {
    public enum LpAction { SET, ADD, REMOVE }

    public record LpGroupChange(String user, String group, LpAction action) {}

    private static final Set<String> DANGEROUS_PREFIXES = Set.of(
            "kick", "mute", "tempmute", "ban", "tempban", "pardon", "unban",
            "lp", "luckperms", "op", "deop", "whitelist", "stop", "restart", "reload",
            "minecraft:stop", "minecraft:reload", "minecraft:kick", "minecraft:ban", "minecraft:pardon"
    );

    public String normalize(String raw) {
        if (raw == null) return "";
        String n = raw.trim();
        while (n.startsWith("/")) n = n.substring(1).trim();
        return n;
    }

    public String firstToken(String raw) {
        String n = normalize(raw);
        if (n.isBlank()) return "";
        int idx = n.indexOf(' ');
        String token = idx < 0 ? n : n.substring(0, idx);
        return token.toLowerCase(Locale.ROOT);
    }

    public boolean isDangerous(String raw) {
        return DANGEROUS_PREFIXES.contains(firstToken(raw));
    }

    public LpGroupChange parseLpGroupChange(String raw) {
        String[] p = normalize(raw).split("\\s+");
        if (p.length < 6) return null;

        String root = p[0].toLowerCase(Locale.ROOT);
        if (!("lp".equals(root) || "luckperms".equals(root))) return null;
        if (!"user".equalsIgnoreCase(p[1])) return null;
        if (!"parent".equalsIgnoreCase(p[3])) return null;

        String action = p[4].toLowerCase(Locale.ROOT);
        LpAction a = switch (action) {
            case "set" -> LpAction.SET;
            case "add" -> LpAction.ADD;
            case "remove" -> LpAction.REMOVE;
            default -> null;
        };
        if (a == null) return null;

        return new LpGroupChange(p[2], p[5], a);
    }
}
