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
 *
 * With the ring covered (see coverRing) the outermost tiles reach one chunk further out, so that
 * every chunk just outside the area lies in a tile too: a full run gives those chunks their
 * features, as Minecraft does for the neighbors of a chunk it finishes.
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
    /** Whether the tiles also cover the ring of chunks around the area (see inRing). */
    private volatile boolean ring;
    private volatile int ringBatches = -1;

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

    /**
     * Makes the tiles cover the ring around the area too: the first and last tile of each axis grow by
     * one chunk outward. The area, its chunk count and the tile indices stay as they are. Set before
     * the first tile is asked for.
     */
    void coverRing(boolean cover) {
        ring = cover;
        ringBatches = -1;
    }

    boolean coversRing() {
        return ring;
    }

    /** Not in the area, but beside a chunk of it (the eight around): the chunks whose features reach into the area. */
    boolean inRing(int chunkX, int chunkZ) {
        if (containsChunk(chunkX, chunkZ)) return false;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx != 0 || dz != 0) && containsChunk(chunkX + dx, chunkZ + dz)) return true;
            }
        }
        return false;
    }

    /** A chunk whose features this run places: one of the area, or of the ring when the tiles cover it. */
    boolean generates(int chunkX, int chunkZ) {
        return containsChunk(chunkX, chunkZ) || ring && inRing(chunkX, chunkZ);
    }

    /** The tile's chunks that lie in the ring (none unless the tiles cover it). */
    int ringChunks(Tile tile) {
        if (!ring) return 0;
        int count = 0;
        for (int cz = tile.minChunkZ(); cz < tile.minChunkZ() + tile.chunksZ(); cz++) {
            for (int cx = tile.minChunkX(); cx < tile.minChunkX() + tile.chunksX(); cx++) {
                if (inRing(cx, cz)) count++;
            }
        }
        return count;
    }

    /** Whether the tile is a batch of a run: it holds a chunk of the area, or of the ring when that is covered. */
    boolean isBatch(Tile tile) {
        return areaChunks(tile) > 0 || ringChunks(tile) > 0;
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

    /** Tiles with at least one chunk of the area (or of the ring, when covered): the batches of a run. */
    int totalBatches() {
        if (!ring) return totalBatches;
        int batches = ringBatches;
        if (batches < 0) {
            batches = 0;
            for (int tx = 0; tx < tilesPerAxis; tx++) {
                for (int tz = 0; tz < tilesPerAxis; tz++) {
                    if (isBatch(tile(tx, tz))) batches++;
                }
            }
            ringBatches = batches;
        }
        return batches;
    }

    /** The tile's chunks that lie in the area. */
    int areaChunks(Tile tile) {
        int minX = Math.max(tile.minChunkX(), x.minChunk());
        int maxX = Math.min(tile.minChunkX() + tile.chunksX(), x.maxChunkExclusive());
        int minZ = Math.max(tile.minChunkZ(), z.minChunk());
        int maxZ = Math.min(tile.minChunkZ() + tile.chunksZ(), z.maxChunkExclusive());
        if (minX >= maxX || minZ >= maxZ) return 0;
        if (shape == Shape.SQUARE) return (maxX - minX) * (maxZ - minZ);
        int count = 0;
        for (int cz = minZ; cz < maxZ; cz++) count += rowChunks(cz, minX, maxX);
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
        // With the ring covered the two outer edges lie one chunk further out.
        if (boundary == tilesPerAxis) return axis.maxChunkExclusive() + (ring ? 1 : 0);
        if (boundary == 0) return axis.minChunk() - (ring ? 1 : 0);
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
