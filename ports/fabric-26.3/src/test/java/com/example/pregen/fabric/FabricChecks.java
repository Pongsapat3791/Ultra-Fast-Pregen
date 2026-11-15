package com.example.pregen.fabric;

import com.example.pregen.ServerChecks;
import net.fabricmc.api.ModInitializer;

/** Fabric entry for the development checks selected by system properties; the NeoForge build has its own. */
public final class FabricChecks implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerChecks.register();
    }
}
