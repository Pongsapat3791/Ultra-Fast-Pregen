package com.example.pregen.sponge;

import com.example.pregen.PreGenMod;
import com.google.inject.Inject;
import java.nio.file.Path;
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
    private final PluginContainer container;

    @Inject
    SpongeEntrypoint(PluginContainer container) {
        this.container = container;
    }

    @Listener
    public void onConstruct(ConstructPluginEvent event) {
        PreGenMod.init(Path.of("config"), container.metadata().version().toString());
    }
}
