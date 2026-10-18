package com.example.pregen.mixin;

import com.example.pregen.PregenRuns;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tracks the running server for the API (which starts runs by dimension id) and resume_after_restart (see PregenRuns). */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void pregen$created(CallbackInfo info) {
        PregenRuns.onServerCreated((MinecraftServer) (Object) this);
    }

    /** Once the server ticks: a run its last stop cut short may continue (resume_after_restart). */
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void pregen$ticking(CallbackInfo info) {
        PregenRuns.onServerTick((MinecraftServer) (Object) this);
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void pregen$stopping(CallbackInfo info) {
        PregenRuns.onServerStopping((MinecraftServer) (Object) this);
    }
}
