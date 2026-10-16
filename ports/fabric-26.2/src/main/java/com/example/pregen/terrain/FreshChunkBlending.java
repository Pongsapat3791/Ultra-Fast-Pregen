package com.example.pregen.terrain;

import java.util.List;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Ownership-checked shortcut for regions that can read only fresh in-memory chunks. */
public final class FreshChunkBlending {
    @FunctionalInterface
    public interface ChunkLookup {
        ChunkAccess get(int x, int z);
    }

    private FreshChunkBlending() {}

    public static boolean canSkip(List<ChunkAccess> cache, ChunkPos center, int radius, ChunkLookup ownedChunks) {
        if (radius < 0 || cache.isEmpty()) return false;
        if (ownedChunks.get(center.x(), center.z()) != cache.get(cache.size() / 2)) return false;
        for (ChunkAccess chunk : cache) {
            ChunkPos pos = chunk.getPos();
            if (ownedChunks.get(pos.x(), pos.z()) != chunk || chunk.getBlendingData() != null) return false;
        }
        for (int z = center.z() - radius; z <= center.z() + radius; z++) {
            for (int x = center.x() - radius; x <= center.x() + radius; x++) {
                ChunkAccess chunk = ownedChunks.get(x, z);
                if (chunk != null && chunk.getBlendingData() != null) return false;
            }
        }
        return true;
    }
}
