package com.example.pregen.neoforge;

import com.example.pregen.PreGenCommand;
import com.example.pregen.PreGenMod;
import java.util.List;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforgespi.language.IModInfo;

/** NeoForge entry point: registers /pregen; everything else comes from the engine and its mixins. */
@Mod(PreGenMod.MOD_ID)
public final class NeoForgeEntrypoint {
    public NeoForgeEntrypoint() {
        String version = ModList.get().getModContainerById(PreGenMod.MOD_ID)
                .map(mod -> mod.getModInfo().getVersion().toString()).orElse("unknown");
        PreGenMod.init(FMLPaths.CONFIGDIR.get(), version);
        for (IModInfo mod : ModList.get().getMods()) {
            readCompatibility(mod);
        }
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event ->
                PreGenCommand.register(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection()));
    }

    /**
     * Compatibility another mod declares in its neoforge.mods.toml:
     * [modproperties.theirmod] ultrafastpregen_compatible_mixins = ["..."], ultrafastpregen_minecraft_terrain = "reason"
     */
    private static void readCompatibility(IModInfo mod) {
        Object mixins = mod.getModProperties().get("ultrafastpregen_compatible_mixins");
        if (mixins instanceof List<?> names) {
            for (Object name : names) {
                if (name instanceof String mixin) PreGenMod.declareCompatibleMixin(mixin, mod.getModId());
            }
        }
        if (mod.getModProperties().get("ultrafastpregen_minecraft_terrain") instanceof String reason) {
            PreGenMod.requireMinecraftTerrain(mod.getModId(), reason);
        }
    }
}
