package io.github.pongsapat3791.ultrafastpregen.api;

/**
 * Entry point of Ultra Fast Pregen's API for other mods.
 *
 * <pre>{@code
 * if (FabricLoader.getInstance().isModLoaded("ultrafastpregen")) {   // or ModList.get().isLoaded(...)
 *     PregenApi pregen = UltraFastPregenApi.get();
 *     pregen.addListener(new PregenListener() {
 *         public void onChunksSaved(String dimension, long[] chunks) { ... }
 *     });
 * }
 * }</pre>
 *
 * The API uses plain Java types only (dimension ids, block coordinates, packed chunk positions),
 * so the same calls work on every Minecraft version and loader Ultra Fast Pregen is built for.
 * Check that the mod is loaded before the first call: without it these classes are missing.
 */
public final class UltraFastPregenApi {
    /** The API version: methods are only added within a version, never changed or removed. */
    public static final int VERSION = 1;

    private static volatile PregenApi instance;

    private UltraFastPregenApi() {}

    /**
     * The API. Available from the moment Ultra Fast Pregen initializes, which loaders do before
     * any server starts.
     *
     * @throws IllegalStateException when called before Ultra Fast Pregen initialized
     */
    public static PregenApi get() {
        PregenApi api = instance;
        if (api == null) {
            throw new IllegalStateException("Ultra Fast Pregen has not initialized yet");
        }
        return api;
    }

    /** Set once by Ultra Fast Pregen itself while it initializes; not for other mods. */
    public static synchronized void provide(PregenApi api) {
        if (instance != null) {
            throw new IllegalStateException("Ultra Fast Pregen's API is already provided");
        }
        instance = api;
    }
}
