package com.example.pregen.mixin;

import com.example.pregen.InMemoryChunkGrid;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Chunks this mod's workers and the light engine ask the server for, served from the grid where it holds them. */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
    @Shadow @Final ServerLevel level;

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"), cancellable = true)
    private void pregen$serveGridChunk(int chunkX, int chunkZ, ChunkStatus status, boolean load,
                                       CallbackInfoReturnable<ChunkAccess> cir) {
        ChunkAccess chunk = InMemoryChunkGrid.chunkForWorker(this.level, chunkX, chunkZ, status);
        if (chunk != null) cir.setReturnValue(chunk);
    }

    /** The light engine's block lookup; grid chunks while their grid lights (see InMemoryChunkGrid#chunkForLighting). */
    @Inject(method = "getChunkForLighting", at = @At("RETURN"), cancellable = true)
    private void pregen$lightGridChunk(int chunkX, int chunkZ, CallbackInfoReturnable<LightChunk> cir) {
        LightChunk chunk = InMemoryChunkGrid.chunkForLighting(this.level, chunkX, chunkZ, cir.getReturnValue());
        if (chunk != null) cir.setReturnValue(chunk);
    }
}
