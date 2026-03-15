package ordacraft.vk.command;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DangerousCommandInspector {
    public enum LpAction { SET, ADD, REMOVE }

    public record LpGroupChange(String user, String group, LpAction action) {}

    private static final Pattern LP_GROUP_CHANGE_PATTERN = Pattern.compile(
            "^(?:lp|luckperms)\\s+user\\s+(\\S+)\\s+parent\\s+(set|add|remove)\\s+(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

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
        String normalized = normalize(raw);
        Matcher matcher = LP_GROUP_CHANGE_PATTERN.matcher(normalized);
        if (!matcher.matches()) return null;

        String action = matcher.group(2).toLowerCase(Locale.ROOT);
        LpAction a = switch (action) {
            case "set" -> LpAction.SET;
            case "add" -> LpAction.ADD;
            case "remove" -> LpAction.REMOVE;
            default -> null;
        };
        if (a == null) return null;

        String user = matcher.group(1);
        String group = matcher.group(3).trim();
        if (group.isEmpty()) return null;
        return new LpGroupChange(user, group, a);
    }
}
