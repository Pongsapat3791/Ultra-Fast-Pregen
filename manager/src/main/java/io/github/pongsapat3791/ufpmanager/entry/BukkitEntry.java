package io.github.pongsapat3791.ufpmanager.entry;

import io.github.pongsapat3791.ufpmanager.Manager;
import org.bukkit.plugin.java.JavaPlugin;

/** CraftBukkit, Spigot, Paper and Folia. */
public final class BukkitEntry extends JavaPlugin {
    @Override
    public void onEnable() {
        Manager.start(Manager.Layout.plugin(getDataFolder().toPath(), getLogger()::info));
    }

    @Override
    public void onDisable() {
        Manager.stop();
    }
}
