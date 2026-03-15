package ordacraft.vk.vk.parser;

import java.util.regex.Pattern;

public class BanCommandParser {
    private static final Pattern TIME = Pattern.compile("^\\d+[mhd]$");

    public static String toConsole(String player, String maybeTime, String reasonTail) {
        if (maybeTime != null && TIME.matcher(maybeTime).matches()) {
            return "tempban " + player + " " + maybeTime + " " + reasonTail;
        }
        String reason = (maybeTime == null ? "" : (maybeTime + " ")) + reasonTail;
        return "ban " + player + " " + reason.trim();
    }

    public static boolean isDuration(String token){ return token != null && TIME.matcher(token).matches(); }
}
