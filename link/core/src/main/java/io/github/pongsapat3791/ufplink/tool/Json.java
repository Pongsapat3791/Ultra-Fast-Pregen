package io.github.pongsapat3791.ufplink.tool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads JSON without Gson (the merge tool runs without Minecraft's libraries): objects become maps,
 * arrays lists, numbers Long or Double, and true/false/null their Java values. For ufplink-fingerprint.json.
 */
public final class Json {
    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(String text) throws IOException {
        Json json = new Json(text);
        Object value = json.value(0);
        json.skipSpace();
        if (json.pos != text.length()) throw json.error("text after the value");
        return value;
    }

    private Object value(int depth) throws IOException {
        if (depth > 256) throw error("nested too deep");
        skipSpace();
        if (pos >= text.length()) throw error("unexpected end");
        char c = text.charAt(pos);
        if (c == '{') return object(depth);
        if (c == '[') return array(depth);
        if (c == '"') return string();
        if (text.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        if (text.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        return number();
    }

    private Map<String, Object> object(int depth) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipSpace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') throw error("expected a key");
            String key = string();
            skipSpace();
            if (peek() != ':') throw error("expected ':'");
            pos++;
            map.put(key, value(depth + 1));
            skipSpace();
            char c = peek();
            pos++;
            if (c == '}') return map;
            if (c != ',') throw error("expected ',' or '}'");
        }
    }

    private List<Object> array(int depth) throws IOException {
        List<Object> list = new ArrayList<>();
        pos++;
        skipSpace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(value(depth + 1));
            skipSpace();
            char c = peek();
            pos++;
            if (c == ']') return list;
            if (c != ',') throw error("expected ',' or ']'");
        }
    }

    private String string() throws IOException {
        StringBuilder out = new StringBuilder();
        pos++;
        while (true) {
            if (pos >= text.length()) throw error("unterminated string");
            char c = text.charAt(pos++);
            if (c == '"') return out.toString();
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (pos >= text.length()) throw error("unterminated string");
            char escape = text.charAt(pos++);
            switch (escape) {
                case '"', '\\', '/' -> out.append(escape);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) throw error("bad \\u escape");
                    try {
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException bad) {
                        throw error("bad \\u escape");
                    }
                    pos += 4;
                }
                default -> throw error("bad escape \\" + escape);
            }
        }
    }

    private Object number() throws IOException {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
        String token = text.substring(start, pos);
        if (token.isEmpty()) throw error("unexpected character");
        try {
            if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) return Long.parseLong(token);
            return Double.parseDouble(token);
        } catch (NumberFormatException bad) {
            throw error("bad number " + token);
        }
    }

    private char peek() throws IOException {
        if (pos >= text.length()) throw error("unexpected end");
        return text.charAt(pos);
    }

    private void skipSpace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private IOException error(String what) {
        return new IOException("JSON: " + what + " at character " + pos);
    }
}
