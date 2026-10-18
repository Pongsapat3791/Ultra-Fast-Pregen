package com.example.pregen.neoforge;

import com.example.pregen.PreGenCommand;
import com.example.pregen.PreGenMod;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** NeoForge entry point: registers /pregen; everything else comes from the engine and its mixins. */
@Mod(PreGenMod.MOD_ID)
public final class NeoForgeEntrypoint {
    public NeoForgeEntrypoint() {
        PreGenMod.init(FMLPaths.CONFIGDIR.get());
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event ->
                PreGenCommand.register(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection()));
    }
}
