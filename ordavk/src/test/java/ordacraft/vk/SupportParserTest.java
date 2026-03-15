package ordacraft.vk;

import ordacraft.vk.vk.parser.SupportCommandParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SupportParserTest {
    @Test void parseReply(){ var p=SupportCommandParser.parse("!r 27 hello"); assertEquals("!r", p.cmd()); assertEquals(27, p.id()); assertEquals("hello", p.tail()); }
    @Test void parseInfo(){ var p=SupportCommandParser.parse("!info 27"); assertEquals("!info", p.cmd()); assertEquals(27, p.id()); }
    @Test void parseClose(){ var p=SupportCommandParser.parse("!close 27"); assertEquals("!close", p.cmd()); assertEquals(27, p.id()); }
}
