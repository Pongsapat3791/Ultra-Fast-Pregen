package com.example.pregen.forge;

import com.example.pregen.PreGenMod;
import java.util.List;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModInfo;

/** Forge entry point. Commands and everything else come from the shared code and its mixins. */
@Mod(PreGenMod.MOD_ID)
public final class ForgeEntrypoint {
    public ForgeEntrypoint() {
        String version = ModList.get().getModContainerById(PreGenMod.MOD_ID)
                .map(mod -> mod.getModInfo().getVersion().toString()).orElse("unknown");
        PreGenMod.init(FMLPaths.CONFIGDIR.get(), version);
        for (IModInfo mod : ModList.get().getMods()) {
            readCompatibility(mod);
        }
    }

    /**
     * Compatibility another mod declares in its mods.toml:
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
