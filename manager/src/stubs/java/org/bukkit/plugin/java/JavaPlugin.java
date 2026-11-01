package org.bukkit.plugin.java;

import java.io.File;
import java.util.logging.Logger;

public abstract class JavaPlugin {
    public void onEnable() {
    }

    public void onDisable() {
    }

    public final File getDataFolder() {
        throw new UnsupportedOperationException();
    }

    public final Logger getLogger() {
        throw new UnsupportedOperationException();
    }
}
