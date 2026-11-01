package io.github.pongsapat3791.ufpmanager;

import java.util.LinkedHashMap;
import java.util.Map;

/** JSON text from maps, lists, numbers, strings and arrays of long; the mod has no library to rely on on every loader. */
final class Json {
    private Json() {}

    /** A map keeping its order, from name, value, name, value... */
    static Map<String, Object> object(Object... namesAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) map.put((String) namesAndValues[i], namesAndValues[i + 1]);
        return map;
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            quote(out, text);
        } else if (value instanceof Double number) {
            out.append(number.isNaN() || number.isInfinite() ? "null" : number.toString());
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(out, String.valueOf(entry.getKey()));
                out.append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> list) {
            out.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) out.append(',');
                first = false;
                append(out, item);
            }
            out.append(']');
        } else if (value instanceof long[] numbers) {
            out.append('[');
            for (int i = 0; i < numbers.length; i++) {
                if (i > 0) out.append(',');
                out.append(numbers[i]);
            }
            out.append(']');
        } else if (value instanceof int[] numbers) {
            out.append('[');
            for (int i = 0; i < numbers.length; i++) {
                if (i > 0) out.append(',');
                out.append(numbers[i]);
            }
            out.append(']');
        } else {
            quote(out, value.toString());
        }
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
