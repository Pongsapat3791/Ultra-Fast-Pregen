package com.example.pregen.mixin;

import com.example.pregen.InMemoryChunkGrid;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Chunks this mod's workers ask the level for, served from their grid (see InMemoryChunkGrid#chunkForWorker). */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
    @Shadow @Final ServerLevel level;

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"), cancellable = true)
    private void pregen$serveGridChunk(int chunkX, int chunkZ, ChunkStatus status, boolean load,
                                       CallbackInfoReturnable<ChunkAccess> cir) {
        ChunkAccess chunk = InMemoryChunkGrid.chunkForWorker(this.level, chunkX, chunkZ, status);
        if (chunk != null) cir.setReturnValue(chunk);
    }
}
