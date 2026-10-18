package io.github.pongsapat3791.ultrafastpregen.api;

/**
 * Events of Ultra Fast Pregen's runs, added with {@link PregenApi#addListener}. Every method has
 * an empty default, so implement only what you need.
 *
 * <p>The methods are called on Ultra Fast Pregen's own threads, never the server thread: keep
 * them short, and hand work that touches the game to the server (for example
 * {@code server.execute(...)}). An exception thrown by a listener is logged and does not stop the run.
 */
public interface PregenListener {
    /** A run started (from a command, the API or a resume). */
    default void onStart(PregenStatus status) {}

    /** About every 3 seconds while a run is active. */
    default void onProgress(PregenStatus status) {}

    /**
     * A batch of chunks reached the region files, fully generated and lit: from here the server
     * loads them from disk like any saved chunk. Map and LoD mods can render them now.
     *
     * @param dimension the dimension id, for example "minecraft:overworld"
     * @param chunks    the saved chunks' positions packed as {@code (x & 0xFFFFFFFFL) | (z & 0xFFFFFFFFL) << 32},
     *                  the packing of Minecraft's {@code ChunkPos.asLong} ({@code ChunkPos.pack} in 26.x); chunks that existed and were kept are not included
     */
    default void onChunksSaved(String dimension, long[] chunks) {}

    /** A run ended: {@link PregenStatus#state()} says whether it completed, stopped or failed. */
    default void onFinish(PregenStatus status) {}
}
