package ordacraft.vk;

import ordacraft.vk.storage.YamlFileStore;
import ordacraft.vk.support.PendingActionService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PendingActionServiceTest {

    @Test
    void ordersByCreatedAtAndPersistsAttemptsAndErrors() throws Exception {
        Path file = Files.createTempFile("pending-actions", ".yml");
        PendingActionService service = new PendingActionService(new YamlFileStore(file));
        service.load();

        UUID u = UUID.randomUUID();
        var first = service.enqueue(u, "Steve", "mute", "tempmute Steve 30m flood", 1L, "moder");
        Thread.sleep(5L);
        var second = service.enqueue(u, "Steve", "ban", "tempban Steve 1d grief", 1L, "moder");
        service.save();

        var ordered = service.pendingFor(u);
        assertEquals(2, ordered.size());
        assertEquals(first.id(), ordered.get(0).id());
        assertEquals(second.id(), ordered.get(1).id());

        service.markFailed(first.id(), "dispatch_returned_false");
        service.save();

        PendingActionService reloaded = new PendingActionService(new YamlFileStore(file));
        reloaded.load();
        var afterReload = reloaded.pendingFor(u);
        assertEquals(2, afterReload.size());
        var failed = afterReload.stream().filter(a -> a.id() == first.id()).findFirst().orElseThrow();
        assertEquals(1, failed.attempts());
        assertEquals("dispatch_returned_false", failed.errorMessage());

        reloaded.markApplied(first.id());
        reloaded.save();

        PendingActionService afterApplyReload = new PendingActionService(new YamlFileStore(file));
        afterApplyReload.load();
        assertEquals(1, afterApplyReload.pendingFor(u).size());
    }
}
