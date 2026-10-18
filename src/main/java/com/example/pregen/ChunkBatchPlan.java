package com.example.pregen;

/**
 * Partitions the chunks intersecting the requested block square into disjoint tiles. The square
 * spans [center - size / 2, center - size / 2 + size) on each axis; the default center is 0, 0.
 * An off-grid center can make the two axes intersect different chunk counts.
 *
 * With Shape.CIRCLE the area is the circle inscribed in that square (radius size / 2): a chunk
 * belongs to it when its center is within the radius of the area's center. The tiles still cover
 * the square; the area's chunks of a tile are its core, the others are left like chunks outside
 * the area, and tiles without any chunk of the area are not batches at all.
 */
final class ChunkBatchPlan {
    enum Shape { SQUARE, CIRCLE }

    private final int batchSizeBlocks;
    private final Axis x;
    private final Axis z;
    private final int tilesPerAxis;
    private final Shape shape;
    private final int centerX;
    private final int centerZ;
    private final long radiusSquared;
    private final int totalChunks;
    private final int totalBatches;

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
        this(worldSizeBlocks, batchSizeBlocks, centerBlockX, centerBlockZ, Shape.SQUARE);
    }

    ChunkBatchPlan(int worldSizeBlocks, int batchSizeBlocks, int centerBlockX, int centerBlockZ, Shape shape) {
        if (worldSizeBlocks <= 0 || batchSizeBlocks <= 0) {
            throw new IllegalArgumentException("World and batch sizes must be positive");
        }
        this.batchSizeBlocks = batchSizeBlocks;
        this.x = Axis.around(centerBlockX, worldSizeBlocks);
        this.z = Axis.around(centerBlockZ, worldSizeBlocks);
        this.tilesPerAxis = Math.floorDiv(worldSizeBlocks - 1, batchSizeBlocks) + 1;
        this.shape = shape;
        this.centerX = centerBlockX;
        this.centerZ = centerBlockZ;
        long radius = worldSizeBlocks / 2;
        this.radiusSquared = radius * radius;
        if (shape == Shape.SQUARE) {
            this.totalChunks = Math.multiplyExact(x.chunks(), z.chunks());
            this.totalBatches = Math.multiplyExact(tilesPerAxis, tilesPerAxis);
        } else {
            long chunks = 0;
            for (int cz = z.minChunk(); cz < z.maxChunkExclusive(); cz++) {
                chunks += rowChunks(cz, x.minChunk(), x.maxChunkExclusive());
            }
            int batches = 0;
            for (int tx = 0; tx < tilesPerAxis; tx++) {
                for (int tz = 0; tz < tilesPerAxis; tz++) {
                    if (areaChunks(tile(tx, tz)) > 0) batches++;
                }
            }
            this.totalChunks = Math.toIntExact(chunks);
            this.totalBatches = batches;
        }
    }

    Shape shape() {
        return shape;
    }

    int chunksX() {
        return x.chunks();
    }

    /** Whether the chunk lies in the requested area. */
    boolean containsChunk(int chunkX, int chunkZ) {
        if (chunkX < x.minChunk() || chunkX >= x.maxChunkExclusive() || chunkZ < z.minChunk() || chunkZ >= z.maxChunkExclusive()) {
            return false;
        }
        return shape == Shape.SQUARE || inCircle(chunkX, chunkZ);
    }

    private boolean inCircle(int chunkX, int chunkZ) {
        long dx = 16L * chunkX + 8 - centerX;
        long dz = 16L * chunkZ + 8 - centerZ;
        return dx * dx + dz * dz <= radiusSquared;
    }

    /** How many chunks of row chunkZ, from minX (inclusive) to maxX (exclusive), lie in the circle. */
    private int rowChunks(int chunkZ, int minX, int maxX) {
        long dz = 16L * chunkZ + 8 - centerZ;
        long remaining = radiusSquared - dz * dz;
        if (remaining < 0) return 0;
        long half = (long) Math.sqrt((double) remaining);
        while (half * half > remaining) half--;
        while ((half + 1) * (half + 1) <= remaining) half++;
        // 16x + 8 - centerX within [-half, half].
        long first = -Math.floorDiv(-(centerX - half - 8), 16L);
        long last = Math.floorDiv(centerX + half - 8, 16L);
        long from = Math.max(first, minX);
        long to = Math.min(last, maxX - 1L);
        return to < from ? 0 : (int) (to - from + 1);
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

    /** Chunks of the area. */
    int totalChunks() {
        return totalChunks;
    }

    int tilesPerAxis() {
        return tilesPerAxis;
    }

    /** Tiles with at least one chunk of the area: the batches of a run. */
    int totalBatches() {
        return totalBatches;
    }

    /** The tile's chunks that lie in the area (all of them for a square). */
    int areaChunks(Tile tile) {
        if (shape == Shape.SQUARE) return tile.totalChunks();
        int count = 0;
        for (int cz = tile.minChunkZ(); cz < tile.minChunkZ() + tile.chunksZ(); cz++) {
            count += rowChunks(cz, tile.minChunkX(), tile.minChunkX() + tile.chunksX());
        }
        return count;
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
