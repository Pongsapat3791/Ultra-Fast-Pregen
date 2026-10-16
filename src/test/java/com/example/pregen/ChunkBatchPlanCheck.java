package com.example.pregen;

/** Pure Java regression check for exact chunk coverage at negative, uneven and off-center bounds. */
public final class ChunkBatchPlanCheck {
    private static final int[][] CENTERS = {{0, 0}, {8, -1}, {-7, 15}, {12_345, -54_321}, {-29_900_000, 29_900_000}};

    private ChunkBatchPlanCheck() {}

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        ChunkBatchPlan oneK = new ChunkBatchPlan(1000, 1000);
        require(oneK.totalBatches() == 1, "A 1k world with 1k batches needs one tile");
        require(oneK.totalChunks() == 4096, "Blocks -500..499 intersect 4096 chunks");
        ChunkBatchPlan.Tile first = oneK.tile(0, 0);
        require(first.minChunkX() == -32 && first.minChunkZ() == -32,
                "Negative block bounds must round down to chunk -32");
        require(first.chunksX() == 64 && first.chunksZ() == 64,
                "Both partial outer chunks must be included");

        // Blocks -492..507 on X span chunks -31..31; blocks -500..499 on Z span -32..31.
        ChunkBatchPlan offCenter = new ChunkBatchPlan(1000, 1000, 8, 0);
        require(offCenter.chunksX() == 63 && offCenter.chunksZ() == 64, "Off-grid centers change per-axis widths");
        require(offCenter.tile(0, 0).minChunkX() == -31, "The X range must follow the center");

        long tiles = 0;
        int plans = 0;
        for (int[] center : CENTERS) {
            for (int worldK = 1; worldK <= 100; worldK++) {
                for (int batchK = 1; batchK <= 10; batchK++) {
                    tiles += checkPlan(worldK * 1000, batchK * 1000, center[0], center[1]);
                    plans++;
                }
            }
            // Also exercise odd sizes and non-aligned, partial final batches directly.
            for (int[] sizes : new int[][]{{1001, 1000}, {3000, 1000}, {11000, 10000},
                    {17, 16}, {33, 16}, {999, 128}}) {
                tiles += checkPlan(sizes[0], sizes[1], center[0], center[1]);
                plans++;
            }
        }
        System.out.println("CHUNK_BATCH_PLAN_CHECK plans=" + plans + " tiles=" + tiles + " centers=" + CENTERS.length
                + " requested_blocks_covered=true no_gaps=true no_overlaps=true one_k_chunks=4096");
    }

    private static long checkPlan(int worldBlocks, int batchBlocks, int centerX, int centerZ) {
        ChunkBatchPlan plan = new ChunkBatchPlan(worldBlocks, batchBlocks, centerX, centerZ);
        int expectedMinX = Math.floorDiv(centerX - worldBlocks / 2, 16);
        int expectedMaxX = Math.floorDiv(centerX - worldBlocks / 2 + worldBlocks - 1, 16) + 1;
        int expectedMinZ = Math.floorDiv(centerZ - worldBlocks / 2, 16);
        int expectedMaxZ = Math.floorDiv(centerZ - worldBlocks / 2 + worldBlocks - 1, 16) + 1;
        require(plan.chunksX() == expectedMaxX - expectedMinX, "Incorrect X chunk width");
        require(plan.chunksZ() == expectedMaxZ - expectedMinZ, "Incorrect Z chunk width");
        require(plan.totalChunks() == (expectedMaxX - expectedMinX) * (expectedMaxZ - expectedMinZ),
                "Incorrect total chunks");

        int nextX = expectedMinX;
        long coreTotal = 0;
        long tileTotal = 0;
        for (int x = 0; x < plan.tilesPerAxis(); x++) {
            ChunkBatchPlan.Tile xTile = plan.tile(x, 0);
            require(xTile.minChunkX() == nextX, "X tiles must be contiguous and disjoint");
            require(xTile.chunksX() > 0, "Tile width must be positive");
            nextX += xTile.chunksX();
            int nextZ = expectedMinZ;
            for (int z = 0; z < plan.tilesPerAxis(); z++) {
                ChunkBatchPlan.Tile tile = plan.tile(x, z);
                require(tile.minChunkZ() == nextZ, "Z tiles must be contiguous and disjoint");
                require(tile.chunksZ() > 0, "Tile depth must be positive");
                require(tile.minBlockX() % 16 == 0 && tile.minBlockZ() % 16 == 0,
                        "Grid inputs must be aligned to chunks");
                nextZ += tile.chunksZ();
                coreTotal += tile.totalChunks();
                tileTotal++;
            }
            require(nextZ == expectedMaxZ, "Z tiling must reach the final block's chunk");
        }
        require(nextX == expectedMaxX, "X tiling must reach the final block's chunk");
        require(coreTotal == plan.totalChunks(), "Every target chunk must occur exactly once");
        require(tileTotal == plan.totalBatches(), "Reported tile total must match the plan");
        return tileTotal;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
