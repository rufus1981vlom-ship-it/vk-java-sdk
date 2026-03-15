package ordacraft.vk;

import ordacraft.vk.admin.AdminRegistry;
import ordacraft.vk.admin.Role;
import ordacraft.vk.storage.YamlFileStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceTest {
    @Test void adminLoadSaveMalformedSafe() throws Exception {
        Path d = Files.createTempDirectory("ordavk-admin");
        AdminRegistry r = new AdminRegistry(new YamlFileStore(d.resolve("admins.yml")));
        r.load();
        r.upsert(1L, "nick", Role.CHIEF);
        r.save();

        AdminRegistry re = new AdminRegistry(new YamlFileStore(d.resolve("admins.yml")));
        re.load();
        assertTrue(re.find(1L).isPresent());

        Files.writeString(d.resolve("admins.yml"), "broken: [:::]");
        re.load();
        assertNotNull(re.all());
    }
}
