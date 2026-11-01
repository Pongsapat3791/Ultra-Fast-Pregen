package io.github.pongsapat3791.ufplink.entry;

import io.github.pongsapat3791.ufplink.UfpLink;
import org.bukkit.plugin.java.JavaPlugin;

/** CraftBukkit, Spigot, Paper and Folia. */
public final class BukkitEntry extends JavaPlugin {
    @Override
    public void onEnable() {
        UfpLink.start(getDataFolder().toPath());
    }

    @Override
    public void onDisable() {
        UfpLink.stop();
    }
}
