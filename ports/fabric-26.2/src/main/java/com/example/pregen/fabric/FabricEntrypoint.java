package com.example.pregen.fabric;

import com.example.pregen.PreGenMod;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** Fabric entry point; the Forge build leaves this package out. */
public class FabricEntrypoint implements ModInitializer {
    @Override
    public void onInitialize() {
        PreGenMod.init(FabricLoader.getInstance().getConfigDir());
    }
}
