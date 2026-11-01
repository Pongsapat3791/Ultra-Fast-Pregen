package io.github.pongsapat3791.ufplink.tool;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads NBT without Minecraft: compounds become maps, lists lists, and numbers, strings and arrays
 * their Java types. For level.dat and for checking chunks in the merge tool.
 */
public final class Nbt {
    private Nbt() {}

    /** A top-level tag {@link #peekFrame} skipped: only its type is known. */
    record Skipped(int type) {}

    /** A gzip-compressed NBT file such as level.dat; its root compound. */
    static Map<String, Object> readGzipFile(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            return root(in, null);
        }
    }

    /**
     * The top-level tags of the NBT a region frame ([length][compression id][compressed NBT]) holds:
     * the ones named in {@code read} with their values, every other one as {@link Skipped} (passed over
     * without being built, which keeps checking a chunk cheap). Null for LZ4, which needs a library
     * the tool does not have.
     */
    public static Map<String, Object> peekFrame(ByteBuffer frame, Set<String> read) throws IOException {
        ByteBuffer data = frame.duplicate();
        data.getInt();
        int compression = Byte.toUnsignedInt(data.get()) & 127;
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        InputStream raw = new ByteArrayInputStream(bytes);
        InputStream stream = switch (compression) {
            case 1 -> new GZIPInputStream(raw);
            case 2 -> new InflaterInputStream(raw);
            case 3 -> raw;
            case 4 -> null;
            default -> throw new IOException("unknown chunk compression " + compression);
        };
        if (stream == null) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(stream, 32768))) {
            return root(in, read);
        }
    }

    /** The root compound; with {@code read}, only its named top-level tags are built. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> root(DataInputStream in, Set<String> read) throws IOException {
        int type = in.readByte();
        if (type != 10) throw new IOException("the root tag is not a compound");
        in.readUTF();
        if (read == null) return (Map<String, Object>) payload(in, type, 0);
        Map<String, Object> top = new LinkedHashMap<>();
        while (true) {
            int child = in.readByte();
            if (child == 0) return top;
            String name = in.readUTF();
            if (read.contains(name)) {
                top.put(name, payload(in, child, 1));
            } else {
                skip(in, child, 1);
                top.put(name, new Skipped(child));
            }
        }
    }

    private static Object payload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 512) throw new IOException("NBT nested too deep");
        switch (type) {
            case 1:
                return in.readByte();
            case 2:
                return in.readShort();
            case 3:
                return in.readInt();
            case 4:
                return in.readLong();
            case 5:
                return in.readFloat();
            case 6:
                return in.readDouble();
            case 7: {
                byte[] bytes = new byte[length(in)];
                in.readFully(bytes);
                return bytes;
            }
            case 8:
                return in.readUTF();
            case 9: {
                int elementType = in.readByte();
                int length = length(in);
                List<Object> list = new ArrayList<>(Math.min(length, 4096));
                for (int i = 0; i < length; i++) list.add(payload(in, elementType, depth + 1));
                return list;
            }
            case 10: {
                Map<String, Object> compound = new LinkedHashMap<>();
                while (true) {
                    int child = in.readByte();
                    if (child == 0) return compound;
                    String name = in.readUTF();
                    compound.put(name, payload(in, child, depth + 1));
                }
            }
            case 11: {
                int[] values = new int[length(in)];
                for (int i = 0; i < values.length; i++) values[i] = in.readInt();
                return values;
            }
            case 12: {
                long[] values = new long[length(in)];
                for (int i = 0; i < values.length; i++) values[i] = in.readLong();
                return values;
            }
            default:
                throw new IOException("unknown NBT tag type " + type);
        }
    }

    private static void skip(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 512) throw new IOException("NBT nested too deep");
        switch (type) {
            case 1 -> skipBytes(in, 1);
            case 2 -> skipBytes(in, 2);
            case 3, 5 -> skipBytes(in, 4);
            case 4, 6 -> skipBytes(in, 8);
            case 7 -> skipBytes(in, length(in));
            case 8 -> skipBytes(in, in.readUnsignedShort());
            case 9 -> {
                int elementType = in.readByte();
                int length = length(in);
                long size = switch (elementType) {
                    case 1 -> 1;
                    case 2 -> 2;
                    case 3, 5 -> 4;
                    case 4, 6 -> 8;
                    default -> -1;
                };
                if (size > 0) {
                    skipBytes(in, size * length);
                } else {
                    for (int i = 0; i < length; i++) skip(in, elementType, depth + 1);
                }
            }
            case 10 -> {
                while (true) {
                    int child = in.readByte();
                    if (child == 0) return;
                    skipBytes(in, in.readUnsignedShort());
                    skip(in, child, depth + 1);
                }
            }
            case 11 -> skipBytes(in, 4L * length(in));
            case 12 -> skipBytes(in, 8L * length(in));
            default -> throw new IOException("unknown NBT tag type " + type);
        }
    }

    private static void skipBytes(DataInputStream in, long count) throws IOException {
        while (count > 0) {
            long skipped = in.skip(count);
            if (skipped <= 0) {
                if (in.read() < 0) throw new IOException("NBT cut short");
                skipped = 1;
            }
            count -= skipped;
        }
    }

    private static int length(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 64 * 1024 * 1024) throw new IOException("bad NBT length " + length);
        return length;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> compound(Map<String, Object> parent, String key) {
        Object value = parent == null ? null : parent.get(key);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    public static long number(Map<String, Object> parent, String key, long fallback) {
        Object value = parent == null ? null : parent.get(key);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    public static String string(Map<String, Object> parent, String key) {
        Object value = parent == null ? null : parent.get(key);
        return value instanceof String text ? text : null;
    }

    /** Deep equality of NBT values (arrays by content). */
    static boolean same(Object a, Object b) {
        if (a instanceof byte[] x && b instanceof byte[] y) return java.util.Arrays.equals(x, y);
        if (a instanceof int[] x && b instanceof int[] y) return java.util.Arrays.equals(x, y);
        if (a instanceof long[] x && b instanceof long[] y) return java.util.Arrays.equals(x, y);
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (!x.keySet().equals(y.keySet())) return false;
            for (Object key : x.keySet()) if (!same(x.get(key), y.get(key))) return false;
            return true;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i))) return false;
            return true;
        }
        return java.util.Objects.equals(a, b);
    }
}
