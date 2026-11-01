package io.github.pongsapat3791.ufplink.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the parts of a YAML file the merge tool checks (spigot.yml, bukkit.yml, Paper's world
 * settings): nested maps of plain values, kept as strings. Lists and multi-line values are skipped.
 */
final class Yaml {
    private Yaml() {}

    private record Level(int indent, Map<String, Object> map) {}

    /** The file's maps; an empty map when the file does not exist. */
    static Map<String, Object> read(Path file) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return root;
        Deque<Level> stack = new ArrayDeque<>();
        stack.push(new Level(-1, root));
        int skipDeeperThan = Integer.MAX_VALUE;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = stripComment(raw.replace('\t', ' '));
            String content = line.strip();
            if (content.isEmpty() || content.equals("---") || content.equals("...")) continue;
            int indent = line.length() - line.stripLeading().length();
            if (indent > skipDeeperThan) continue;
            skipDeeperThan = Integer.MAX_VALUE;
            if (content.startsWith("-")) {
                // A list item, and whatever belongs to it.
                skipDeeperThan = indent;
                continue;
            }
            String key;
            String value;
            if (content.startsWith("\"") || content.startsWith("'")) {
                int end = content.indexOf(content.charAt(0), 1);
                if (end < 0 || end + 1 >= content.length() || content.charAt(end + 1) != ':') continue;
                key = content.substring(1, end);
                value = content.substring(end + 2).strip();
            } else {
                int colon = content.indexOf(": ");
                if (colon < 0 && content.endsWith(":")) colon = content.length() - 1;
                if (colon < 0) continue;
                key = content.substring(0, colon).strip();
                value = content.substring(colon + 1).strip();
            }
            while (stack.peek().indent() >= indent) stack.pop();
            Map<String, Object> parent = stack.peek().map();
            if (value.isEmpty()) {
                Map<String, Object> child = new LinkedHashMap<>();
                parent.put(key, child);
                stack.push(new Level(indent, child));
            } else if (value.startsWith("|") || value.startsWith(">")) {
                parent.put(key, "");
                skipDeeperThan = indent;
            } else {
                parent.put(key, unquote(value));
            }
        }
        return root;
    }

    /** The value at the path of keys, or null. */
    @SuppressWarnings("unchecked")
    static Object get(Map<String, Object> root, String... keys) {
        Object value = root;
        for (String key : keys) {
            if (!(value instanceof Map)) return null;
            value = ((Map<String, Object>) value).get(key);
        }
        return value;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** The line without a comment (a # at its start or after a space, outside quotes). */
    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if ((c == '"' || c == '\'') && (i == 0 || line.charAt(i - 1) == ' ' || line.charAt(i - 1) == ':')) {
                quote = c;
            } else if (c == '#' && (i == 0 || line.charAt(i - 1) == ' ')) {
                return line.substring(0, i);
            }
        }
        return line;
    }
}
