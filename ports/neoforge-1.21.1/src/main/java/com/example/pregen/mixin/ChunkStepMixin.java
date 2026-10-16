package com.example.pregen.mixin;

import com.example.pregen.RegeneratedChunk;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft 1.21 runs a step's task even when the chunk already has its status (1.20 skipped
 * it). A server-held proto chunk this mod replaced is marked FULL, but steps the server had
 * already queued for it would then rebuild terrain or place features a second time. Those
 * steps keep the finished chunk; light and the final FULL step still run, as in 1.20.
 */
@Mixin(ChunkStep.class)
public abstract class ChunkStepMixin {
    @Inject(method = "apply", at = @At("HEAD"), cancellable = true)
    private void pregen$keepRegeneratedChunk(WorldGenContext context, StaticCache2D<GenerationChunkHolder> cache,
                                             ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!(chunk instanceof RegeneratedChunk regenerated) || !regenerated.pregen$isRegenerated()) return;
        ChunkStatus target = ((ChunkStep) (Object) this).targetStatus();
        if (target != ChunkStatus.INITIALIZE_LIGHT && target != ChunkStatus.LIGHT && target != ChunkStatus.FULL) {
            cir.setReturnValue(CompletableFuture.completedFuture(chunk));
        }
    }
}
