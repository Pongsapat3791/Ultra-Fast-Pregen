package com.example.pregen;

import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * The chunks a grid's WorldGenRegion has handed out, by position, so block reads skip Minecraft's
 * per-call dependency checks (see WorldGenRegionMixin). Covers the 5 x 5 chunks around the
 * region's center; a chunk there never changes while its step runs.
 */
public final class RegionChunkCache {
    /** For regions that are not a grid's: every read goes to Minecraft as usual. */
    public static final RegionChunkCache NONE = new RegionChunkCache(0, 0, 0);
    private static final int RADIUS = 2;
    private static final int SIZE = 2 * RADIUS + 1;

    private final int minX;
    private final int minZ;
    private final ChunkAccess[] chunks;

    private RegionChunkCache(int centerX, int centerZ, int size) {
        this.minX = centerX - RADIUS;
        this.minZ = centerZ - RADIUS;
        this.chunks = new ChunkAccess[size * size];
    }

    public static RegionChunkCache around(int centerX, int centerZ) {
        return new RegionChunkCache(centerX, centerZ, SIZE);
    }

    /** The slot of chunk (x, z), or -1 when this cache does not cover it. */
    public int slot(int x, int z) {
        int dx = x - minX;
        int dz = z - minZ;
        return chunks.length != 0 && dx >= 0 && dx < SIZE && dz >= 0 && dz < SIZE ? dx * SIZE + dz : -1;
    }

    public ChunkAccess get(int slot) {
        return chunks[slot];
    }

    public void put(int slot, ChunkAccess chunk) {
        chunks[slot] = chunk;
    }
}
