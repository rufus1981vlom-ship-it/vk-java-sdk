package ordacraft.vk;

import ordacraft.vk.storage.YamlFileStore;
import ordacraft.vk.support.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TicketAndPendingTest {
    @Test void createCooldownMaxOpenAndPendingOnce() throws Exception {
        Path d= Files.createTempDirectory("ordavk-test");
        SupportTicketService t = new SupportTicketService(new YamlFileStore(d.resolve("tickets.yml")));
        t.load();
        UUID u = UUID.randomUUID();
        var a = t.create(TicketType.QUESTION, u, "Steve", "", "hello");
        assertEquals(1, a.id());
        t.touch(u);
        assertTrue(t.isCooldown(u, 60));
        t.create(TicketType.QUESTION, u, "Steve", "", "2");
        t.create(TicketType.QUESTION, u, "Steve", "", "3");
        assertEquals(3, t.openBy(u));

        PendingReplyService p = new PendingReplyService(new YamlFileStore(d.resolve("pending.yml")));
        p.load();
        p.put(new PendingReply(u, "Steve", a.id(), "answer", 1L, "mod", Instant.now().toEpochMilli()));
        assertTrue(p.take(u).isPresent());
        assertTrue(p.take(u).isEmpty());
    }
}
