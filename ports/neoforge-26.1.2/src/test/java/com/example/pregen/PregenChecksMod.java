package com.example.pregen;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Development-only entry for the checks selected by system properties. The test classes share
 * the mod (and its Java module) with the main classes, since they use package-private members.
 */
@EventBusSubscriber(modid = PreGenMod.MOD_ID)
public final class PregenChecksMod {
    private PregenChecksMod() {}

    @SubscribeEvent
    static void onCommonSetup(FMLCommonSetupEvent event) {
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, started -> {
            PartialExistingBatchCheck.onServerStarted(started.getServer());
            SingleplayerCheck.onServerStarted(started.getServer());
        });
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, stopped -> PartialExistingBatchCheck.onServerStopped(stopped.getServer()));
    }

    @SubscribeEvent
    static void onLoadComplete(FMLLoadCompleteEvent event) {
        TerrainBenchmark.runIfRequested();
    }
}
