package io.github.pongsapat3791.ultrafastpregen.api;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The running server's worlds, from {@link PregenApi#worlds()}, for mods that bring chunks into a world
 * from elsewhere, such as Ultra Fast Pregen Link. Everything that depends on Minecraft's version or the
 * loader is here, in plain Java types, so such a mod can be one jar for every version.
 *
 * <p>A chunk position is packed as in {@link PregenListener#onChunksSaved}. A chunk's {@code frame} is its
 * record in a region file: {@code [length][compression id][compressed NBT]}.
 *
 * <p>Any thread, unless a method says otherwise; methods that wait for the server or the disk block.
 * Methods that need a server throw {@link IllegalStateException} when none is running.
 */
public interface PregenWorlds {
    /** A dimension's two region stores. */
    enum Store { CHUNKS, ENTITIES }

    /** When a chunk may be written over what the store has. A chunk the server is saving always stays. */
    enum WriteRule {
        /** Only where the store has nothing. */
        ABSENT,
        /** Also over a part-generated chunk, when the new one is fully generated. */
        ABSENT_OR_PART_GENERATED,
        /** Also over a chunk that still has the hash of {@link ChunkWrite#baseHash()}. */
        ABSENT_OR_UNCHANGED
    }

    /**
     * A chunk to write.
     *
     * @param chunk          the packed position
     * @param frame          the chunk
     * @param fullyGenerated whether it is fully generated
     * @param baseHash       for {@link WriteRule#ABSENT_OR_UNCHANGED}: the {@link StoredChunk#hash()} it may replace; null for none
     */
    record ChunkWrite(long chunk, ByteBuffer frame, boolean fullyGenerated, byte[] baseHash) {}

    /**
     * What {@link #writeChunks} did.
     *
     * @param written  the chunks written, or with {@code dryRun} those it would write
     * @param replaced of these, the chunks that took the place of one the store had
     * @param kept     the chunks that stayed as they were
     */
    record WriteResult(long[] written, int replaced, int kept) {}

    /**
     * A chunk the store has, as {@link #readChunks} found it.
     *
     * @param chunk          the packed position
     * @param frame          the chunk
     * @param hash           what {@link WriteRule#ABSENT_OR_UNCHANGED} compares later
     * @param fullyGenerated whether it is fully generated
     */
    record StoredChunk(long chunk, ByteBuffer frame, byte[] hash, boolean fullyGenerated) {}

    /**
     * What generation depends on in one dimension.
     *
     * @param generator a hash of the generator's settings
     * @param probe     a hash of the height and biome the generator gives at 64 places, which differs when
     *                  generation differs although the settings read the same
     */
    record DimensionFacts(String generator, String probe) {}

    /**
     * What generation depends on in this server's world.
     *
     * @param minecraft   Minecraft's version
     * @param dataVersion Minecraft's chunk format
     * @param seed        the world's seed
     * @param loader      "fabric", "forge", "neoforge", "bukkit" or "sponge"
     * @param mods        every mod or plugin, id to version
     * @param datapacks   the datapacks in use
     * @param dimensions  dimension id to its facts
     */
    record ServerFacts(String minecraft, int dataVersion, long seed, String loader, Map<String, String> mods,
                       List<String> datapacks, Map<String, DimensionFacts> dimensions) {}

    /** Whoever ran a command added with {@link #addCommand}. Any thread. */
    interface CommandReply {
        /** A line for them; with {@code toOperators} also for the operators and the log. */
        void tell(String message, boolean toOperators);

        void fail(String message);

        /** Their permission level: 4 for the console and the owner of a singleplayer world. */
        boolean hasPermission(int level);

        String name();
    }

    interface CommandHandler {
        /**
         * On the server thread.
         *
         * @param arguments the text after the command's name; empty when there is none
         * @return whether it succeeded
         */
        boolean run(String arguments, CommandReply reply);
    }

    /** Whether a server is running. */
    boolean serverRunning();

    /** The world's folder, the one with level.dat. */
    Path worldFolder();

    /** The folder relative paths in commands start from. */
    Path serverFolder();

    /** The ids of the dimensions loaded now. */
    List<String> dimensions();

    /** The folder with a loaded dimension's region and entities folders. */
    Path dimensionFolder(String dimension);

    /** This server's facts. Can take a second or two, for the probes. */
    ServerFacts facts();

    /** Minecraft's chunk format, as in {@link ServerFacts#dataVersion()}, at once. */
    int dataVersion();

    /**
     * The chunks around an area that a run reads as neighbors: chunks outside the area to this distance
     * shape what is generated, so a world that must join them needs them too.
     */
    int neighborChunks();

    /**
     * Opens a separate world: a dimension of its own that generates like {@code like}, with the same
     * generator and seed. What its folder already holds stays, so region files written there first are
     * part of it. Runs ({@link PregenApi#start}) and these methods take its id like any dimension.
     *
     * @param like the dimension to copy
     * @param id   a new dimension id; its namespace should be the calling mod's
     * @return the id to use from here on: the same, unless the platform names worlds itself (Bukkit does)
     * @throws IOException when this server cannot open one (Folia cannot)
     */
    String openSeparateWorld(String like, String id) throws IOException;

    /** The folder {@link #openSeparateWorld} will use, to fill before it opens. Nothing is created. */
    Path separateWorldFolder(String like, String id);

    /** Saves and closes a separate world and takes it out of the server. Its folder stays. */
    void closeSeparateWorld(String id) throws IOException;

    /**
     * The chunks the dimension's store has of these, read through the thread that owns it, so a save
     * the server has queued counts.
     */
    List<StoredChunk> readChunks(String dimension, long[] chunks) throws IOException;

    /** Of these chunks, those the server holds in memory in any state, which must not be written. */
    long[] heldChunks(String dimension, long[] chunks);

    /**
     * Writes chunks through the thread that owns the store, in short tasks so the server's own loads and
     * saves run in between. The check and the write of a chunk are one step on that thread, so a save the
     * server makes in between is never overwritten; on Paper and Folia they are two steps, so leave out
     * {@link #heldChunks} first.
     *
     * @param dryRun only find out what would be written; nothing is created
     * @throws IOException when the store cannot be written, or another mod replaced it
     */
    WriteResult writeChunks(String dimension, Store store, List<ChunkWrite> chunks, WriteRule rule, boolean dryRun) throws IOException;

    /**
     * Adds a command with the server's next command setup, for operators (level 2) and the owner of a
     * singleplayer world. Call it while your mod initializes.
     */
    void addCommand(String name, CommandHandler handler);
}
