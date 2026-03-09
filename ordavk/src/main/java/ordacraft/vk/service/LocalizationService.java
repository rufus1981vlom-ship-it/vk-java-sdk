package ordacraft.vk.service;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class LocalizationService {
    private final Map<String, String> messages = new HashMap<>();

    @SuppressWarnings("unchecked")
    public LocalizationService(String langCode) {
        String normalized = langCode == null ? "ru" : langCode.toLowerCase(Locale.ROOT);
        if (!load("i18n/messages_" + normalized + ".yml")) {
            load("i18n/messages_ru.yml");
        }
    }

    @SuppressWarnings("unchecked")
    private boolean load(String resource) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) return false;
            Object obj = new Yaml().load(in);
            if (obj instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    messages.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String tr(String key) {
        return messages.getOrDefault(key, key);
    }

    public String tr(String key, Map<String, String> vars) {
        String base = tr(key);
        for (Map.Entry<String, String> e : vars.entrySet()) {
            base = base.replace("{" + e.getKey() + "}", e.getValue());
        }
        return base;
    }
}
