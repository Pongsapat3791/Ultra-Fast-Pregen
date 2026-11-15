package com.example.pregen.mixin;

import com.example.pregen.terrain.TerrainGenerationContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** On this mod's terrain workers a chunk's terrain stays on the worker instead of going to Minecraft's background threads. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @Redirect(
        method = "buildTerrain",
        at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),
        require = 0
    )
    private CompletableFuture<ChunkAccess> pregen$terrainOnWorker(Supplier<ChunkAccess> work, Executor executor) {
        if (TerrainGenerationContext.isActive()) return CompletableFuture.completedFuture(work.get());
        return CompletableFuture.supplyAsync(work, executor);
    }
}
