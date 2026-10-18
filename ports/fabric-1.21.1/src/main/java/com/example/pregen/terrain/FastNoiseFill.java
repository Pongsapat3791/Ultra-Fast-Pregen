package com.example.pregen.terrain;

import com.example.pregen.mixin.CacheAllInCellAccessor;
import com.example.pregen.mixin.NoiseChunkAccessor;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.material.MaterialRuleList;

/**
 * NoiseBasedChunkGenerator.doFill, block for block, except that interpolation cells whose
 * density bounds already decide every block skip the per-block density, aquifer and ore-vein
 * evaluation (see DensityBounds):
 * <ul>
 * <li>density at most zero and the aquifer proven to give air: the cell is all air, which
 * vanilla never writes, so nothing is written;</li>
 * <li>density above zero, so the aquifer gives no fluid, and the cell outside every ore vein's
 * height range: every block is the default block, written in vanilla's order.</li>
 * </ul>
 * All other cells run vanilla's per-block code unchanged.
 */
public final class FastNoiseFill {
    /** OreVeinifier's vein types span y -60..-8 (iron) and 0..50 (copper); outside, it yields nothing. */
    private static final int VEIN_MIN_Y = -60;
    private static final int VEIN_MAX_Y = 50;
    /**
     * OreVeinifier yields nothing where |veinToggle| + f < 0.4F, with f <= 0 from the distance to
     * the vein's height limits, so a cell whose toggle magnitude stays below this has no vein.
     */
    private static final double VEIN_THRESHOLD = 0.4F - DensityBounds.DECISION_MARGIN;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** Off only in the parity regression, to produce Minecraft's own fill for comparison. */
    public static volatile boolean enabled = true;
    /** Cells decided as air, decided as solid, and generated per block; for diagnostics. */
    public static final LongAdder AIR_CELLS = new LongAdder();
    public static final LongAdder SOLID_CELLS = new LongAdder();
    public static final LongAdder BLOCK_CELLS = new LongAdder();

    private FastNoiseFill() {}

    /** Air, solid and per-block cell counts so far, for a run's summary. */
    public static long[] cellCounts() {
        return new long[]{AIR_CELLS.sum(), SOLID_CELLS.sum(), BLOCK_CELLS.sum()};
    }

    /** Returns null when this NoiseChunk is not the vanilla layout, so vanilla doFill must run. */
    public static ChunkAccess fill(NoiseChunk noiseChunk, RandomState randomState, BlockState defaultBlock,
                                   ChunkAccess chunkAccess, int i, int j) {
        NoiseChunkAccessor access = (NoiseChunkAccessor) noiseChunk;
        Plan plan = Plan.of(access, randomState);
        if (plan == null) return null;

        Heightmap heightmap = chunkAccess.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap heightmap2 = chunkAccess.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        ChunkPos chunkPos = chunkAccess.getPos();
        int k = chunkPos.getMinBlockX();
        int l = chunkPos.getMinBlockZ();
        Aquifer aquifer = noiseChunk.aquifer();
        AquiferAirProof airProof = (Object) aquifer instanceof AquiferAirProof proof ? proof : null;
        boolean voidTerrain = SharedConstants.debugVoidTerrain(chunkPos);
        noiseChunk.initializeForFirstCellX();
        BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();
        int m = access.pregen$getCellWidth();
        int n = access.pregen$getCellHeight();
        int o = 16 / m;
        int p = 16 / m;
        long cellBlocks = (long) m * m * n;
        DensityBounds bounds = plan.bounds;
        int airCells = 0;
        int solidCells = 0;
        int blockCells = 0;

        for (int q = 0; q < o; q++) {
            noiseChunk.advanceCellX(q);

            for (int r = 0; r < p; r++) {
                int s = chunkAccess.getSectionsCount() - 1;
                LevelChunkSection levelChunkSection = chunkAccess.getSection(s);

                for (int t = j - 1; t >= 0; t--) {
                    int cellMinX = k + q * m;
                    int cellMinY = (i + t) * n;
                    int cellMinZ = l + r * m;
                    bounds.evaluate(t, r, cellMinX, cellMinY, cellMinZ, m, n);
                    // The last cell always runs block by block: the aquifer's fluid-update flag
                    // left by Minecraft's last block is what the carvers start from.
                    boolean last = q == o - 1 && r == p - 1 && t == 0;
                    boolean air = !last && airProof != null && bounds.allNonPositive()
                            && airProof.pregen$airAtNonPositiveDensity(cellMinX, cellMinY, cellMinZ, m, n);
                    boolean solid = !last && !air && bounds.allPositive() && noVein(plan, t, r, cellMinX, cellMinY, cellMinZ, m, n);
                    if (air || solid) {
                        // Keep NoiseChunk's cache counters strictly increasing, as if the
                        // cell's selectCellYZ and per-block updates had run.
                        access.pregen$setArrayInterpolationCounter(access.pregen$getArrayInterpolationCounter() + 2);
                        access.pregen$setInterpolationCounter(access.pregen$getInterpolationCounter() + cellBlocks);
                        if (air) airCells++;
                        else solidCells++;
                        if (air || defaultBlock == AIR || voidTerrain) continue;
                        for (int u = n - 1; u >= 0; u--) {
                            int v = (i + t) * n + u;
                            int w = v & 15;
                            int x = chunkAccess.getSectionIndex(v);
                            if (s != x) {
                                s = x;
                                levelChunkSection = chunkAccess.getSection(x);
                            }

                            for (int y = 0; y < m; y++) {
                                int aa = (cellMinX + y) & 15;
                                for (int ab = 0; ab < m; ab++) {
                                    int ad = (cellMinZ + ab) & 15;
                                    // A positive density makes the aquifer clear its fluid-update
                                    // flag, so no default-block fluid is scheduled here either.
                                    levelChunkSection.setBlockState(aa, w, ad, defaultBlock, false);
                                    heightmap.update(aa, v, ad, defaultBlock);
                                    heightmap2.update(aa, v, ad, defaultBlock);
                                }
                            }
                        }
                        continue;
                    }

                    blockCells++;
                    noiseChunk.selectCellYZ(t, r);

                    for (int u = n - 1; u >= 0; u--) {
                        int v = (i + t) * n + u;
                        int w = v & 15;
                        int x = chunkAccess.getSectionIndex(v);
                        if (s != x) {
                            s = x;
                            levelChunkSection = chunkAccess.getSection(x);
                        }

                        double d = (double) u / n;
                        noiseChunk.updateForY(v, d);

                        for (int y = 0; y < m; y++) {
                            int z = k + q * m + y;
                            int aa = z & 15;
                            double e = (double) y / m;
                            noiseChunk.updateForX(z, e);

                            for (int ab = 0; ab < m; ab++) {
                                int ac = l + r * m + ab;
                                int ad = ac & 15;
                                double f = (double) ab / m;
                                noiseChunk.updateForZ(ac, f);
                                BlockState blockState = access.pregen$getInterpolatedState();
                                if (blockState == null) {
                                    blockState = defaultBlock;
                                }

                                if (blockState != AIR && !voidTerrain) {
                                    levelChunkSection.setBlockState(aa, w, ad, blockState, false);
                                    heightmap.update(aa, v, ad, blockState);
                                    heightmap2.update(aa, v, ad, blockState);
                                    if (aquifer.shouldScheduleFluidUpdate() && !blockState.getFluidState().isEmpty()) {
                                        mutableBlockPos.set(z, v, ac);
                                        chunkAccess.markPosForPostprocessing(mutableBlockPos);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            noiseChunk.swapSlices();
        }

        noiseChunk.stopInterpolation();
        AIR_CELLS.add(airCells);
        SOLID_CELLS.add(solidCells);
        BLOCK_CELLS.add(blockCells);
        return chunkAccess;
    }

    private static boolean noVein(Plan plan, int t, int r, int minX, int minY, int minZ, int width, int height) {
        if (!plan.veins || minY > VEIN_MAX_Y || minY + height - 1 < VEIN_MIN_Y) return true;
        if (plan.veinToggle == null) return false;
        plan.veinToggle.evaluate(t, r, minX, minY, minZ, width, height);
        return plan.veinToggle.maxAbs() < VEIN_THRESHOLD;
    }

    /** What the per-block rule of a vanilla NoiseChunk consists of, or null if not vanilla. */
    private record Plan(DensityBounds bounds, boolean veins, DensityBounds veinToggle) {
        static Plan of(NoiseChunkAccessor access, RandomState randomState) {
            // Vanilla's rule: [aquifer over cacheAllInCell(finalDensity + beardifier)], plus the
            // ore veinifier when ore veins are enabled. The cell cache is the last one created.
            if (!(access.pregen$getBlockStateRule() instanceof MaterialRuleList rules)) return null;
            int fillers = rules.materialRuleList().size();
            if (fillers < 1 || fillers > 2) return null;
            List<?> caches = access.pregen$getCellCaches();
            if (caches.isEmpty() || !(caches.get(caches.size() - 1) instanceof CacheAllInCellAccessor cache)) return null;
            DensityFunction density = cache.pregen$getNoiseFiller();
            if (!DensityBounds.isSumWith(density, access.pregen$getBeardifier())) return null;
            DensityBounds bounds = DensityBounds.compile(density, access.pregen$getBlender());
            DensityBounds veinToggle = null;
            if (fillers == 2) {
                // The constructor wrapped the router through NoiseChunk.wrap, which memoizes by
                // value, so wrapping again returns the instances the veinifier already uses.
                // Should that ever create anything new, the vein bound is not used.
                int interpolators = access.pregen$getInterpolators().size();
                int cellCaches = caches.size();
                DensityFunction toggle = randomState.router().veinToggle().mapAll(access::pregen$wrap);
                if (access.pregen$getInterpolators().size() == interpolators && access.pregen$getCellCaches().size() == cellCaches) {
                    veinToggle = DensityBounds.compile(toggle, access.pregen$getBlender());
                }
            }
            return new Plan(bounds, fillers == 2, veinToggle);
        }
    }
}
