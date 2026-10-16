package com.example.pregen;

/**
 * Partitions the chunks intersecting the requested block square into disjoint tiles. The square
 * spans [center - size / 2, center - size / 2 + size) on each axis; the default center is 0, 0.
 * An off-grid center can make the two axes intersect different chunk counts.
 */
final class ChunkBatchPlan {
    private final int batchSizeBlocks;
    private final Axis x;
    private final Axis z;
    private final int tilesPerAxis;

    private record Axis(int minBlock, int minChunk, int maxChunkExclusive) {
        static Axis around(int centerBlock, int sizeBlocks) {
            int minBlock = centerBlock - sizeBlocks / 2;
            return new Axis(minBlock, Math.floorDiv(minBlock, 16), Math.floorDiv(minBlock + sizeBlocks - 1, 16) + 1);
        }

        int chunks() {
            return maxChunkExclusive - minChunk;
        }
    }

    ChunkBatchPlan(int worldSizeBlocks, int batchSizeBlocks) {
        this(worldSizeBlocks, batchSizeBlocks, 0, 0);
    }

    ChunkBatchPlan(int worldSizeBlocks, int batchSizeBlocks, int centerBlockX, int centerBlockZ) {
        if (worldSizeBlocks <= 0 || batchSizeBlocks <= 0) {
            throw new IllegalArgumentException("World and batch sizes must be positive");
        }
        this.batchSizeBlocks = batchSizeBlocks;
        this.x = Axis.around(centerBlockX, worldSizeBlocks);
        this.z = Axis.around(centerBlockZ, worldSizeBlocks);
        this.tilesPerAxis = Math.floorDiv(worldSizeBlocks - 1, batchSizeBlocks) + 1;
    }

    int chunksX() {
        return x.chunks();
    }

    /** Whether the chunk lies in the requested area. */
    boolean containsChunk(int chunkX, int chunkZ) {
        return chunkX >= x.minChunk() && chunkX < x.maxChunkExclusive()
                && chunkZ >= z.minChunk() && chunkZ < z.maxChunkExclusive();
    }

    int chunksZ() {
        return z.chunks();
    }

    int minBlockX() {
        return x.minBlock();
    }

    int minBlockZ() {
        return z.minBlock();
    }

    int totalChunks() {
        return Math.multiplyExact(chunksX(), chunksZ());
    }

    int tilesPerAxis() {
        return tilesPerAxis;
    }

    int totalBatches() {
        return Math.multiplyExact(tilesPerAxis, tilesPerAxis);
    }

    Tile tile(int tileX, int tileZ) {
        if (tileX < 0 || tileX >= tilesPerAxis || tileZ < 0 || tileZ >= tilesPerAxis) {
            throw new IndexOutOfBoundsException("Tile outside generation bounds");
        }
        int minX = boundaryChunk(x, tileX);
        int minZ = boundaryChunk(z, tileZ);
        return new Tile(minX, minZ, boundaryChunk(x, tileX + 1) - minX,
                boundaryChunk(z, tileZ + 1) - minZ);
    }

    private int boundaryChunk(Axis axis, int boundary) {
        // Round interior boundaries in one direction so neighbors share the same
        // boundary. Only the final outer edge rounds up to include its last block.
        if (boundary == tilesPerAxis) return axis.maxChunkExclusive();
        return Math.floorDiv(axis.minBlock() + boundary * batchSizeBlocks, 16);
    }

    record Tile(int minChunkX, int minChunkZ, int chunksX, int chunksZ) {
        int minBlockX() {
            return minChunkX * 16;
        }

        int minBlockZ() {
            return minChunkZ * 16;
        }

        int totalChunks() {
            return Math.multiplyExact(chunksX, chunksZ);
        }
    }
}
