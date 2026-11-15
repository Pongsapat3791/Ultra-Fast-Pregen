package io.github.pongsapat3791.ultrafastpregen.api;

import java.util.Optional;

/**
 * Ultra Fast Pregen's functions for other mods, from {@link UltraFastPregenApi#get()}.
 *
 * <p>Runs act on the running server (the dedicated server, or the singleplayer world's
 * integrated server). One run at a time: like {@code /pregen start}, starting while another run
 * is active is refused. Every method is thread-safe.
 */
public interface PregenApi {
    /** {@link UltraFastPregenApi#VERSION} of the installed Ultra Fast Pregen. */
    int apiVersion();

    /** The installed Ultra Fast Pregen's version, for example "1.21.1-15". */
    String modVersion();

    // ------------------------------------------------------------------ runs

    /**
     * Starts generating the requested area, with the settings of {@code config/ultrafastpregen.conf}
     * (batch size, threads, keeping existing chunks), as {@code /pregen start} does.
     * Returns at once; the run continues on Ultra Fast Pregen's own threads.
     */
    StartResult start(PregenRequest request);

    /** Continues the dimension's last run (its area and center) from Ultra Fast Pregen's save file, as {@code /pregen resume}. */
    StartResult resume(String dimension);

    /** Asks the running run to stop after its current batch, as {@code /pregen stop}. False when nothing runs. */
    boolean stop();

    /** Whether a run is active. */
    boolean isRunning();

    /** The active run, or else the last run since the server started; empty before the first run. */
    Optional<PregenStatus> status();

    // ------------------------------------------------------------------ events

    /** Calls the listener for every later run; adding the same listener twice has no effect. */
    void addListener(PregenListener listener);

    void removeListener(PregenListener listener);

    // ------------------------------------------------------------------ worlds

    /** The running server's worlds, for mods that bring chunks into a world from elsewhere. Since API version 2. */
    PregenWorlds worlds();

    // ------------------------------------------------------------------ compatibility

    /**
     * Declares that a mixin class does not change what Ultra Fast Pregen's shortcuts rely on,
     * although it targets it (for example a mixin that only reads values, or changes them in a
     * way the shortcut reproduces). From Minecraft 26.3 those are features on all threads, the
     * features' biome lookups and lighting on the workers. Declare it while your mod initializes,
     * before a run starts. Can also be declared without code, see the API documentation.
     *
     * @param mixinClassName the mixin's fully qualified class name, for example "com.example.mixin.NoiseMixin"
     */
    void declareCompatibleMixin(String mixinClassName);

    /**
     * Asks Ultra Fast Pregen to leave Minecraft's terrain steps (biomes, noise, surface and caves,
     * one step from Minecraft 26.3) on Minecraft's own threads while your mod is loaded.
     * Structures, features, light and saving still run on every core. The reason appears in the
     * server log.
     */
    void requireMinecraftTerrain(String modId, String reason);
}
