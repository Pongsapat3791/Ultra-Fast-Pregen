package io.github.pongsapat3791.ufpmanager;

import io.github.pongsapat3791.ufpmanager.WebServer.BadRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Ultra Fast Pregen's settings file, which it reads at the start of every run: the same change as /pregen set. A known
 * key's line is replaced and the file's comments stay.
 */
final class UfpSettings {
    private static final Predicate<String> BOOLEAN = value -> value.equals("true") || value.equals("false");
    private static final Map<String, Predicate<String>> KEYS = new LinkedHashMap<>();
    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        add("batch_size", "1000", value -> whole(value, 512, 10_000));
        add("threads", "max", value -> value.equals("max") || whole(value, 1, 128));
        add("parallel_features", "auto", value -> value.equals("auto") || value.equals("always"));
        add("skip_existing_chunks", "true", BOOLEAN);
        add("resume_after_restart", "true", BOOLEAN);
        add("release_memory_each_batch", "true", BOOLEAN);
        add("terrain_pipeline", "auto", value -> value.equals("auto") || value.equals("fused") || value.equals("vanilla"));
    }

    private static void add(String key, String fallback, Predicate<String> valid) {
        KEYS.put(key, valid);
        DEFAULTS.put(key, fallback);
    }

    private static boolean whole(String value, int min, int max) {
        try {
            int number = Integer.parseInt(value);
            return number >= min && number <= max;
        } catch (NumberFormatException wrong) {
            return false;
        }
    }

    private final Path file;

    UfpSettings(Path file) {
        this.file = file;
    }

    /** Each key's value, the defaults for what the file lacks. */
    synchronized Map<String, String> values() throws IOException {
        Map<String, String> values = new LinkedHashMap<>(DEFAULTS);
        if (!Files.isRegularFile(file)) return values;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int equals = line.indexOf('=');
            if (line.startsWith("#") || equals < 0) continue;
            String key = line.substring(0, equals).trim();
            if (values.containsKey(key)) values.put(key, line.substring(equals + 1).trim());
        }
        return values;
    }

    synchronized void save(Map<String, List<String>> fields) throws IOException {
        Map<String, String> changes = new LinkedHashMap<>();
        for (Map.Entry<String, Predicate<String>> key : KEYS.entrySet()) {
            List<String> value = fields.get(key.getKey());
            if (value == null || value.isEmpty()) continue;
            String text = value.get(0).trim();
            if (!key.getValue().test(text)) throw new BadRequest(key.getKey() + " cannot be " + text);
            changes.put(key.getKey(), text);
        }
        List<String> lines = new ArrayList<>(Files.isRegularFile(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int equals = line.indexOf('=');
            if (line.startsWith("#") || equals < 0) continue;
            String value = changes.remove(line.substring(0, equals).trim());
            if (value != null) lines.set(i, line.substring(0, equals + 1) + value);
        }
        changes.forEach((key, value) -> lines.add(key + "=" + value));
        Files.createDirectories(file.getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
