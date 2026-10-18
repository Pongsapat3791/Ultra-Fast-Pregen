package com.example.pregen.mixin;

import com.example.pregen.InMemoryChunkGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin {

    @Shadow @Final private ServerLevel level;
    @Shadow @Final private StaticCache2D<GenerationChunkHolder> cache;

    @Shadow public abstract boolean hasChunk(int x, int z);

    @Shadow public abstract ChunkPos getCenter();

    @Inject(method = "isOldChunkAround", at = @At("HEAD"), cancellable = true)
    private void pregen$skipDiskScanForFreshGrid(ChunkPos pos, int radius, CallbackInfoReturnable<Boolean> cir) {
        InMemoryChunkGrid grid = pos.equals(this.getCenter()) ? InMemoryChunkGrid.owning(this.level, this.cache, pos) : null;
        if (grid != null && grid.canSkipBlendingScan(this.cache, pos, radius)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * A grid region's cache holds only the chunks its step writes or reads closely; the structure
     * starts it may reference (up to 8 chunks away) come from the grid itself, as holders, so
     * Minecraft's own checks then run as with a full cache. This replaces only the cache lookup:
     * every block read goes through here, and a cancellable callback at the head of the method
     * cost a fifth of the features stage in worldgen-mod-heavy worlds.
     */
    @Redirect(
        method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/StaticCache2D;get(II)Ljava/lang/Object;")
    )
    private Object pregen$holderOrGrid(StaticCache2D<GenerationChunkHolder> cache, int x, int z) {
        if (cache.contains(x, z)) {
            return cache.get(x, z);
        }
        InMemoryChunkGrid grid = this.pregen$grid();
        return grid != null ? grid.holderOrFallback(x, z) : cache.get(x, z);
    }

    /** Further than any step reads, or not yet at the status asked for: the grid's chunk, as before. */
    @Inject(
        method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At(value = "NEW", target = "java/lang/IllegalStateException"),
        cancellable = true
    )
    private void onUnavailableChunk(int x, int z, ChunkStatus status, boolean required, CallbackInfoReturnable<ChunkAccess> cir) {
        if (this.cache.contains(x, z)) {
            return;
        }
        InMemoryChunkGrid grid = this.pregen$grid();
        if (grid != null) {
            cir.setReturnValue(grid.getChunkOrFallback(x, z));
        }
    }

    /** The grid whose own region this is, while its center lies inside it, or null. */
    @Unique
    private InMemoryChunkGrid pregen$grid() {
        ChunkPos center = this.getCenter();
        InMemoryChunkGrid grid = InMemoryChunkGrid.owning(this.level, this.cache, center);
        return grid != null && grid.isInsideGrid(center.x(), center.z()) ? grid : null;
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

        InMemoryChunkGrid grid = InMemoryChunkGrid.owning(this.level, this.cache, this.getCenter());
        if (grid != null) {
            ChunkPos center = this.getCenter();
            if (grid.isInsideGrid(center.x(), center.z())) {
                cir.setReturnValue(new DifficultyInstance(this.level.getDifficulty(), this.level.getOverworldClockTime(), 0L, this.level.getMoonBrightness(pos)));
            }
        }
    }
}
