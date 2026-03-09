package ordacraft.vk.storage;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class YamlFileStore {
    private final Path file;
    private final Yaml yaml;

    public YamlFileStore(Path file) {
        this.file = file;
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        this.yaml = new Yaml(options);
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> load() {
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                save(new LinkedHashMap<>());
            }
            try (InputStream in = Files.newInputStream(file)) {
                Object o = yaml.load(in);
                return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
            }
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public synchronized void save(Map<String, Object> data) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                yaml.dump(data, writer);
            }
        } catch (IOException ignored) {}
    }
}
