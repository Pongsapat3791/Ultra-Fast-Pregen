package com.example.pregen;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Name, log and config folder shared by the plugin's classes (see UltraFastPregenPlugin). */
public final class PreGenMod {

    public static final String MOD_ID = "ultrafastpregen";
    public static final String NAME = "Ultra Fast Pregen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    /** The plugin's data folder (plugins/UltraFastPregen), set when it is enabled. */
    static volatile Path configDirectory = Path.of("plugins", "UltraFastPregen");

    private PreGenMod() {}

    /** Called once when the plugin is enabled; also makes the API (UltraFastPregenApi) available. */
    public static void init(Path configFolder, String version) {
        configDirectory = configFolder;
        PregenApiImpl.install(version);
        LOGGER.info("{} loaded.", NAME);
    }

    /** The config file as players and admins find it, for messages. */
    static String configFileText() {
        return configDirectory.resolve(PreGenConfig.FILE_NAME).toString().replace('\\', '/');
    }
}
