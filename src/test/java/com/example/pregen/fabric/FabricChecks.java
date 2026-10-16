package com.example.pregen.fabric;

import com.example.pregen.PartialExistingBatchCheck;
import com.example.pregen.SingleplayerCheck;
import com.example.pregen.TerrainBenchmark;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** Fabric entry for the development checks selected by system properties; the Forge build has its own. */
public final class FabricChecks implements ModInitializer {
    @Override
    public void onInitialize() {
        TerrainBenchmark.runIfRequested();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            PartialExistingBatchCheck.onServerStarted(server);
            SingleplayerCheck.onServerStarted(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(PartialExistingBatchCheck::onServerStopped);
    }
}
