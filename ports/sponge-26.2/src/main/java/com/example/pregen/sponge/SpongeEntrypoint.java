package com.example.pregen.sponge;

import com.example.pregen.PreGenMod;
import com.google.inject.Inject;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.lifecycle.ConstructPluginEvent;
import org.spongepowered.plugin.PluginContainer;
import org.spongepowered.plugin.builtin.jvm.Plugin;

/**
 * Sponge entry point. SpongeVanilla applies the mixins this plugin's manifest names (MixinConfigs),
 * so it works as the mod builds do: /pregen through CommandsMixin, the server's start, ticks and stop
 * through MinecraftServerMixin. Settings in config/ultrafastpregen.conf, as with the mods.
 */
@Plugin(PreGenMod.MOD_ID)
public final class SpongeEntrypoint {
    /** Minecraft 26.2 only, as in mixin.SpongeMixinPlugin, which applies no mixin without it. */
    private static final String RECURSIVE_VISITOR = "net.minecraft.world.level.levelgen.DensityFunction$1RecursiveVisitor";
    private final PluginContainer container;

    @Inject
    SpongeEntrypoint(PluginContainer container) {
        this.container = container;
    }

    @Listener
    public void onConstruct(ConstructPluginEvent event) {
        if (!minecraft262()) {
            PreGenMod.LOGGER.error("[PreGen] This jar is for Minecraft 26.2 and does nothing on this server.");
            return;
        }
        PreGenMod.init(Path.of("config"), container.metadata().version().toString());
        Map<String, String> plugins = new TreeMap<>();
        Sponge.pluginManager().plugins().forEach(plugin -> plugins.put(plugin.metadata().id(), plugin.metadata().version().toString()));
        PreGenMod.platform("sponge", plugins);
    }

    private static boolean minecraft262() {
        try {
            Class.forName(RECURSIVE_VISITOR, false, SpongeEntrypoint.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException otherVersion) {
            return false;
        }
    }
}
