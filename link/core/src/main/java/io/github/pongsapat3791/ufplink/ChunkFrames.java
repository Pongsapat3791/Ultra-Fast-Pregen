package io.github.pongsapat3791.ufplink;

import io.github.pongsapat3791.ufplink.tool.Nbt;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks region frames ([length][compression id][compressed NBT]) without Minecraft. */
final class ChunkFrames {
    private static final Set<String> CHUNK_TAGS = Set.of("DataVersion", "xPos", "zPos", "Status");
    private static final Set<String> ENTITY_TAGS = Set.of("DataVersion", "Position");

    private ChunkFrames() {}

    private static Map<String, Object> peek(ByteBuffer frame, Set<String> tags) throws IOException {
        Map<String, Object> tag = Nbt.peekFrame(frame, tags);
        if (tag == null) throw new IOException("a chunk compressed with LZ4");
        return tag;
    }

    /** Whether the chunk's generation status is full. */
    static boolean isFull(ByteBuffer frame) throws IOException {
        String status = Nbt.string(peek(frame, CHUNK_TAGS), "Status");
        return "minecraft:full".equals(status) || "full".equals(status);
    }

    /** Why a chunk cannot be brought in at pos, or null when it can. */
    static String checkChunk(ByteBuffer frame, ChunkPos pos, int dataVersion) throws IOException {
        Map<String, Object> tag = peek(frame, CHUNK_TAGS);
        long version = Nbt.number(tag, "DataVersion", -1);
        if (version != dataVersion) return "data version " + version + ", not " + dataVersion;
        long x = Nbt.number(tag, "xPos", Long.MIN_VALUE);
        long z = Nbt.number(tag, "zPos", Long.MIN_VALUE);
        if (x != pos.x || z != pos.z) return "it says it is chunk [" + x + ", " + z + "]";
        if (Nbt.string(tag, "Status") == null) return "no generation status";
        if (!tag.containsKey("sections")) return "no sections";
        return null;
    }

    /** Why entity data cannot be brought in at pos, or null when it can. */
    static String checkEntities(ByteBuffer frame, ChunkPos pos, int dataVersion) throws IOException {
        Map<String, Object> tag = peek(frame, ENTITY_TAGS);
        long version = Nbt.number(tag, "DataVersion", -1);
        if (version != dataVersion) return "data version " + version + ", not " + dataVersion;
        Object position = tag.get("Position");
        boolean here = position instanceof int[] xz && xz.length == 2 && xz[0] == pos.x && xz[1] == pos.z;
        if (!here) return "it says it belongs to another chunk";
        if (!tag.containsKey("Entities")) return "no entity list";
        return null;
    }
}
