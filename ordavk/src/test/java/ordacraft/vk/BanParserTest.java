package ordacraft.vk;

import ordacraft.vk.vk.parser.BanCommandParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BanParserTest {
    @Test void banWithoutTime(){ assertEquals("ban Steve cheating", BanCommandParser.toConsole("Steve", null, "cheating")); }
    @Test void banWithTime(){ assertEquals("tempban Steve 7d cheating", BanCommandParser.toConsole("Steve", "7d", "cheating")); }
    @Test void invalidTokenAsReasonStart(){ assertEquals("ban Steve читы сегодня", BanCommandParser.toConsole("Steve", "читы", "сегодня")); }
}
