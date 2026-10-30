package com.example.pregen;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Name, log and config folder shared by the loader entry points (packages fabric and forge). */
public final class PreGenMod {

    public static final String MOD_ID = "ultrafastpregen";
    public static final String NAME = "Ultra Fast Pregen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    /** The loader's config folder, set by its entry point. */
    static volatile Path configDirectory = Path.of("config");
    /** Set by the Fabric entry point; null on Forge (see RuntimeNames). */
    static volatile RuntimeNames runtimeNames;

    private PreGenMod() {}

    /**
     * Runtime names of the 1.20.5 and 1.20.6 classes and methods that this 1.20.1 build reaches by
     * intermediary name (ChunkSteps, WorldChunkIO). Only the Fabric jar runs there; the Forge jar
     * stops at 1.20.4 and never asks.
     */
    public interface RuntimeNames {
        String className(String intermediary);

        String methodName(String owner, String name, String descriptor);
    }

    /** Called by the Fabric entry point before init. */
    public static void setRuntimeNames(RuntimeNames names) {
        runtimeNames = names;
    }

    public static void init(Path configFolder) {
        init(configFolder, "unknown");
    }

    /** Called once by the loader's entry point; also makes the API (UltraFastPregenApi) available. */
    public static void init(Path configFolder, String version) {
        configDirectory = configFolder;
        PregenApiImpl.install(version);
        LOGGER.info("{} loaded.", NAME);
    }

    /**
     * A mixin another mod declares compatible in its metadata (fabric.mod.json "custom" or
     * mods.toml "modproperties"; see the API documentation), read by the entry points.
     */
    public static void declareCompatibleMixin(String mixinClassName, String modId) {
        WorldgenCompat.declareCompatibleMixin(mixinClassName, modId);
    }

    /** A mod whose metadata asks for Minecraft's own terrain steps, read by the entry points. */
    public static void requireMinecraftTerrain(String modId, String reason) {
        WorldgenCompat.requireMinecraftTerrain(modId, reason);
    }
}
