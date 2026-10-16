package com.example.pregen.forge;

import com.example.pregen.PartialExistingBatchCheck;
import com.example.pregen.PreGenMod;
import com.example.pregen.TerrainBenchmark;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;

/** Forge entry for the development checks selected by system properties (see fabric.FabricChecks). */
public final class ForgeChecks {
    private ForgeChecks() {}

    @Mod.EventBusSubscriber(modid = PreGenMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Loading {
        @SubscribeEvent
        public static void onLoadComplete(FMLLoadCompleteEvent event) {
            TerrainBenchmark.runIfRequested();
        }
    }

    @Mod.EventBusSubscriber(modid = PreGenMod.MOD_ID)
    public static final class Server {
        @SubscribeEvent
        public static void onStarted(ServerStartedEvent event) {
            PartialExistingBatchCheck.onServerStarted(event.getServer());
        }

        @SubscribeEvent
        public static void onStopped(ServerStoppedEvent event) {
            PartialExistingBatchCheck.onServerStopped(event.getServer());
        }
    }
}
