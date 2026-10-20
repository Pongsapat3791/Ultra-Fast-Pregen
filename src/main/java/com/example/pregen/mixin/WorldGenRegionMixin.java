package com.example.pregen.mixin;

import com.example.pregen.InMemoryChunkGrid;
import com.example.pregen.terrain.BiomeChunkSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import java.util.List;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin implements BiomeChunkSource {

    @Shadow @Final private ServerLevel level;
    @Shadow @Final private List<ChunkAccess> cache;

    @Shadow public abstract boolean hasChunk(int x, int z);

    @Shadow public abstract ChunkPos getCenter();

    @Inject(method = "isOldChunkAround", at = @At("HEAD"), cancellable = true)
    private void pregen$skipDiskScanForFreshGrid(ChunkPos pos, int radius, CallbackInfoReturnable<Boolean> cir) {
        InMemoryChunkGrid grid = pos.equals(this.getCenter()) ? InMemoryChunkGrid.owning(this.level, this.cache) : null;
        if (grid != null && grid.canSkipBlendingScan(this.cache, pos, radius)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(
        method = "getChunk(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onGetChunk(int x, int z, ChunkStatus status, boolean required, CallbackInfoReturnable<ChunkAccess> cir) {
        if (this.hasChunk(x, z)) {
            return;
        }

        InMemoryChunkGrid grid = InMemoryChunkGrid.owning(this.level, this.cache);
        if (grid != null) {
            ChunkPos center = this.getCenter();
            if (grid.isInsideGrid(center.x, center.z)) {
                cir.setReturnValue(grid.getChunkOrFallback(x, z));
            }
        }
    }

    /** The chunk whose biomes the region's getNoiseBiome reads for its cells, found as LevelReader's does. */
    @Override
    public ChunkAccess pregen$getBiomeChunk(int chunkX, int chunkZ) {
        return ((LevelReader) (Object) this).getChunk(chunkX, chunkZ, ChunkStatus.BIOMES, false);
    }

    @Inject(
        method = "getCurrentDifficultyAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/DifficultyInstance;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onGetCurrentDifficultyAt(BlockPos pos, CallbackInfoReturnable<DifficultyInstance> cir) {
        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
        if (this.hasChunk(chunkX, chunkZ)) {
            return;
        }

        InMemoryChunkGrid grid = InMemoryChunkGrid.owning(this.level, this.cache);
        if (grid != null) {
            ChunkPos center = this.getCenter();
            if (grid.isInsideGrid(center.x, center.z)) {
                cir.setReturnValue(new DifficultyInstance(this.level.getDifficulty(), this.level.getDayTime(), 0L, this.level.getMoonBrightness()));
            }
        }
    }
}
