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

    private PreGenMod() {}

    public static void init(Path configFolder) {
        configDirectory = configFolder;
        LOGGER.info("{} loaded.", NAME);
    }
}
