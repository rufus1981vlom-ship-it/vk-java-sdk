package ordacraft.vk;

import ordacraft.vk.command.DangerousCommandInspector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DangerousCommandInspectorTest {
    private final DangerousCommandInspector inspector = new DangerousCommandInspector();

    @Test
    void normalizesAndDetectsDangerous() {
        assertEquals("lp user Steve parent set хан", inspector.normalize("/lp user Steve parent set хан"));
        assertTrue(inspector.isDangerous("/ban Steve grief"));
        assertFalse(inspector.isDangerous("say hello"));
    }

    @Test
    void parsesLuckPermsParentActions() {
        var set = inspector.parseLpGroupChange("/lp user Steve parent set хан");
        assertNotNull(set);
        assertEquals("Steve", set.user());
        assertEquals("хан", set.group());

        var add = inspector.parseLpGroupChange("luckperms user Alex parent add moder");
        assertNotNull(add);
        assertEquals(DangerousCommandInspector.LpAction.ADD, add.action());

        var none = inspector.parseLpGroupChange("say hello");
        assertNull(none);
    }
}
