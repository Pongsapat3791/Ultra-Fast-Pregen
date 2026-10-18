package com.example.pregen.mixin;

import com.example.pregen.InMemoryChunkGrid;
import com.example.pregen.RegionChunkCache;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Chunk lookups of a grid's region (every block read and write goes through getChunk(x, z)):
 * the chunk from a small per-region cache. Features in worldgen-mod-heavy worlds read millions of
 * blocks per batch, and Minecraft checks each lookup against the step's dependencies; in a grid's
 * region the answer for a chunk never changes while the step runs.
 *
 * Priority 1100: applied after mods that replace these methods outright (Lithium's and Canary's
 * chunk_region cache does, with its own array of the region's chunks); their method then has no
 * call to redirect and this mixin simply stays out, where an injection at the same priority
 * would stop the game from starting.
 */
@Mixin(value = WorldGenRegion.class, priority = 1100)
public abstract class WorldGenRegionChunkCacheMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private StaticCache2D<GenerationChunkHolder> cache;

    @Shadow public abstract ChunkPos getCenter();

    @Unique
    private RegionChunkCache pregen$chunks;

    @Redirect(
        method = "getChunk(II)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/WorldGenRegion;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;)Lnet/minecraft/world/level/chunk/ChunkAccess;"),
        require = 0
    )
    private ChunkAccess pregen$cachedChunk(WorldGenRegion region, int x, int z, ChunkStatus status) {
        RegionChunkCache chunks = this.pregen$chunkCache();
        int slot = chunks.slot(x, z);
        if (slot < 0) {
            return region.getChunk(x, z, status);
        }
        ChunkAccess chunk = chunks.get(slot);
        if (chunk == null) {
            // Always the same status here (getChunk(x, z) asks for EMPTY).
            chunk = region.getChunk(x, z, status);
            chunks.put(slot, chunk);
        }
        return chunk;
    }

    /** Block reads, the most frequent lookup, take the cache without the extra call. */
    @Redirect(
        method = "getBlockState",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/WorldGenRegion;getChunk(II)Lnet/minecraft/world/level/chunk/ChunkAccess;"),
        require = 0
    )
    private ChunkAccess pregen$cachedChunkForRead(WorldGenRegion region, int x, int z) {
        RegionChunkCache chunks = this.pregen$chunkCache();
        int slot = chunks.slot(x, z);
        if (slot < 0) {
            return region.getChunk(x, z);
        }
        ChunkAccess chunk = chunks.get(slot);
        return chunk != null ? chunk : region.getChunk(x, z);
    }

    /** The cache of a grid's own region (its center inside the grid), or NONE for any other region. */
    @Unique
    private RegionChunkCache pregen$chunkCache() {
        RegionChunkCache chunks = this.pregen$chunks;
        if (chunks == null) {
            ChunkPos center = this.getCenter();
            InMemoryChunkGrid grid = InMemoryChunkGrid.owning(this.level, this.cache, center);
            chunks = grid != null && grid.isInsideGrid(center.x, center.z)
                    ? RegionChunkCache.around(center.x, center.z) : RegionChunkCache.NONE;
            this.pregen$chunks = chunks;
        }
        return chunks;
    }
}
