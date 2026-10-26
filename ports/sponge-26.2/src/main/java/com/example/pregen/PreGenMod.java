package com.example.pregen;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Name, log and config folder shared by the loader entry points (package neoforge). */
public final class PreGenMod {

    public static final String MOD_ID = "ultrafastpregen";
    public static final String NAME = "Ultra Fast Pregen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    /** The loader's config folder, set by its entry point. */
    static volatile Path configDirectory = Path.of("config");

    private PreGenMod() {}

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
