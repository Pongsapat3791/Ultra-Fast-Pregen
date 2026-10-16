package com.example.pregen.forge;

import com.example.pregen.PreGenMod;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

/** Forge entry point. Commands and everything else come from the shared code and its mixins. */
@Mod(PreGenMod.MOD_ID)
public final class ForgeEntrypoint {
    public ForgeEntrypoint() {
        PreGenMod.init(FMLPaths.CONFIGDIR.get());
    }
}
