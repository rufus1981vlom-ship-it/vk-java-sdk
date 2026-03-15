package ordacraft.vk.vk.parser;

public class SupportCommandParser {
    public record Parsed(String cmd, int id, String tail) {}

    public static Parsed parse(String text) {
        String[] p = text.trim().split("\\s+", 3);
        if (p.length == 1) return new Parsed(p[0].toLowerCase(), -1, "");
        if (p.length == 2) return new Parsed(p[0].toLowerCase(), Integer.parseInt(p[1]), "");
        return new Parsed(p[0].toLowerCase(), Integer.parseInt(p[1]), p[2]);
    }
}
