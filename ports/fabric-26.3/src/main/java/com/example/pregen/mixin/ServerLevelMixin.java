package com.example.pregen.mixin;

import com.example.pregen.InMemoryChunkGrid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A stopping server runs the tasks other threads hand it on those threads. The structure starts
 * this mod's workers generate meanwhile would then update the level's structure cache from many
 * threads at once, so they are left out of it; the cache reads them from saved chunks when needed.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "onStructureStartsAvailable", at = @At("HEAD"), cancellable = true)
    private void pregen$skipWhileStopping(ChunkAccess chunk, CallbackInfo info) {
        if (Thread.currentThread() instanceof InMemoryChunkGrid.WorkerThread
                && ((ServerLevel) (Object) this).getServer().isStopped()) {
            info.cancel();
        }
    }
}
