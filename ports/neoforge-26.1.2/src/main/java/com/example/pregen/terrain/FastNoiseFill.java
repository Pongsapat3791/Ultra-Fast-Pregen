package com.example.pregen.terrain;

import com.example.pregen.MinecraftVersion;
import com.example.pregen.WorldgenCompat;
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
import net.minecraft.world.level.levelgen.OreVeinifier;
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
 * height range, or its vein values proving that no block of it is in a vein: every block is the
 * default block;</li>
 * <li>density above zero, inside an ore vein's reach: each block is what the ore veins give, or
 * the default block, without the density or the aquifer.</li>
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
    /** Benchmark switches, on unless -Dfastpregen.veinCells=false or -Dfastpregen.ridgeBounds=false. */
    private static final boolean VEIN_CELLS_ENABLED = Boolean.parseBoolean(System.getProperty("fastpregen.veinCells", "true"));
    private static final boolean RIDGE_BOUNDS_ENABLED = Boolean.parseBoolean(System.getProperty("fastpregen.ridgeBounds", "true"));
    /** Cells decided as air, decided as solid, and generated per block; for diagnostics. */
    public static final LongAdder AIR_CELLS = new LongAdder();
    public static final LongAdder SOLID_CELLS = new LongAdder();
    public static final LongAdder BLOCK_CELLS = new LongAdder();
    /**
     * Of the cells generated per block: those whose density is nowhere above zero (fluid, or air not proven),
     * those solid throughout but inside an ore vein's reach, and the last cell of each chunk.
     */
    public static final LongAdder NON_POSITIVE_BLOCK_CELLS = new LongAdder();
    public static final LongAdder SOLID_BLOCK_CELLS = new LongAdder();
    public static final LongAdder LAST_BLOCK_CELLS = new LongAdder();
    /** Solid cells in a vein's reach decided as vein-free by the ridge values, and those asked block by block. */
    public static final LongAdder RIDGE_FREE_CELLS = new LongAdder();
    public static final LongAdder VEIN_CELLS = new LongAdder();

    private FastNoiseFill() {}

    /** Air, solid and per-block cell counts so far, for a run's summary. */
    public static long[] cellCounts() {
        return new long[]{AIR_CELLS.sum(), SOLID_CELLS.sum(), BLOCK_CELLS.sum(), NON_POSITIVE_BLOCK_CELLS.sum(),
                SOLID_BLOCK_CELLS.sum(), LAST_BLOCK_CELLS.sum(), BlockBits.FILLED.sum(), BlockBits.REPLACED.sum(),
                BlockBits.LEFT.sum(), SharedSurfaceLevels.TAKEN.sum(), CellBeards.NONE_NEAR.sum(), CellBeards.SOME_NEAR.sum(),
                CellBeards.ALL_NEAR.sum(), PaletteGrowth.CLEARED.sum(), RIDGE_FREE_CELLS.sum(), VEIN_CELLS.sum(),
                OnceInputs.MARKED.sum(), OreHeights.ANSWERED.sum(), SkyColumns.CELLS.sum(), SkyColumns.CORNERS.sum(),
                SkyColumns.FAILED.sum()};
    }

    /** Returns null when this NoiseChunk is not the vanilla layout, so vanilla doFill must run. */
    public static ChunkAccess fill(NoiseChunk noiseChunk, RandomState randomState, BlockState defaultBlock,
                                   ChunkAccess chunkAccess, int i, int j) {
        NoiseChunkAccessor access = (NoiseChunkAccessor) noiseChunk;
        Plan plan = Plan.of(access, randomState, defaultBlock);
        if (plan == null) return null;

        Heightmap heightmap = chunkAccess.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap heightmap2 = chunkAccess.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        ChunkPos chunkPos = chunkAccess.getPos();
        int k = chunkPos.getMinBlockX();
        int l = chunkPos.getMinBlockZ();
        Aquifer aquifer = noiseChunk.aquifer();
        AquiferAirProof airProof = (Object) aquifer instanceof AquiferAirProof proof ? proof : null;
        // Minecraft's per-block rule, with the aquifer centers kept per grid cell (see AquiferCenters):
        // the aquifer over the cell's density, then the ore veins.
        AquiferAirProof centers = plan.aquiferRule && airProof != null && AquiferCenters.enabled
                && WorldgenCompat.aquiferOptimizationAllowed() ? airProof : null;
        DensityFunction fullDensity = plan.fullDensity;
        NoiseChunk.BlockStateFiller veinRule = plan.veinRule;
        // Solid cells in a vein's reach ask the ore veins alone (Minecraft's own pair of rules only).
        NoiseChunkCells veinCells = plan.veinOnly && VEIN_CELLS_ENABLED && defaultBlock != AIR
                && (Object) noiseChunk instanceof NoiseChunkCells cells ? cells : null;
        boolean voidTerrain = SharedConstants.debugVoidTerrain(chunkPos);
        int m = access.pregen$getCellWidth();
        int n = access.pregen$getCellHeight();
        int o = 16 / m;
        int p = 16 / m;
        long cellBlocks = (long) m * m * n;
        DensityBounds bounds = plan.bounds;
        // The open sky above the chunk, decided before its corners are computed (see SkyColumns).
        NoiseChunkCells cornerCells = (Object) noiseChunk instanceof NoiseChunkCells asCells ? asCells : null;
        SkyColumns sky = SkyColumns.enabled && airProof != null && cornerCells != null && !voidTerrain
                && o == access.pregen$getCellCountXZ() ? SkyColumns.of(access, cornerCells, bounds, airProof, k, l, i, j, o, m, n) : null;
        if (sky != null && !SkyColumns.VERIFY) cornerCells.pregen$setExactLevels(sky.exactLevels);
        noiseChunk.initializeForFirstCellX();
        BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();
        int airCells = 0;
        int solidCells = 0;
        int blockCells = 0;
        int nonPositiveCells = 0;
        int solidVeinCells = 0;
        int lastCells = 0;
        int ridgeFreeCells = 0;
        int veinCellCount = 0;
        int skyCells = 0;
        int skyFailed = 0;

        for (int q = 0; q < o; q++) {
            noiseChunk.advanceCellX(q);

            for (int r = 0; r < p; r++) {
                int s = chunkAccess.getSectionsCount() - 1;
                LevelChunkSection levelChunkSection = chunkAccess.getSection(s);

                for (int t = j - 1; t >= 0; t--) {
                    int cellMinX = k + q * m;
                    int cellMinY = (i + t) * n;
                    int cellMinZ = l + r * m;
                    // The last cell always runs block by block: the aquifer's fluid-update flag
                    // left by Minecraft's last block is what the carvers start from.
                    boolean last = q == o - 1 && r == p - 1 && t == 0;
                    boolean inSky = sky != null && !last && t >= sky.skyFrom[q * o + r];
                    if (inSky && !SkyColumns.VERIFY) {
                        // Air by the test below, decided before its corners were computed (which they were not).
                        access.pregen$setArrayInterpolationCounter(access.pregen$getArrayInterpolationCounter() + 2);
                        access.pregen$setInterpolationCounter(access.pregen$getInterpolationCounter() + cellBlocks);
                        airCells++;
                        skyCells++;
                        continue;
                    }
                    bounds.evaluate(t, r, cellMinX, cellMinY, cellMinZ, m, n);
                    boolean air = !last && airProof != null && bounds.allNonPositive()
                            && airProof.pregen$airAtNonPositiveDensity(cellMinX, cellMinY, cellMinZ, m, n);
                    if (inSky) {
                        skyCells++;
                        if (!air) skyFailed++;
                    }
                    boolean positive = !last && !air && bounds.allPositive();
                    boolean solid = positive && noVein(plan, t, r, cellMinX, cellMinY, cellMinZ, m, n);
                    if (positive && !solid && noRidge(plan, t, r, cellMinX, cellMinY, cellMinZ, m, n)) {
                        solid = true;
                        ridgeFreeCells++;
                    }
                    if (air || solid) {
                        // Keep NoiseChunk's cache counters strictly increasing, as if the
                        // cell's selectCellYZ and per-block updates had run.
                        access.pregen$setArrayInterpolationCounter(access.pregen$getArrayInterpolationCounter() + 2);
                        access.pregen$setInterpolationCounter(access.pregen$getInterpolationCounter() + cellBlocks);
                        if (air) airCells++;
                        else solidCells++;
                        if (air || defaultBlock == AIR || voidTerrain) continue;
                        // The cell lies in one section (cells are 8 high from a multiple of 8, as a rule).
                        if (plan.opaqueDefault && cellMinY >> 4 == cellMinY + n - 1 >> 4) {
                            int cellSection = chunkAccess.getSectionIndex(cellMinY);
                            LevelChunkSection sectionOfCell = chunkAccess.getSection(cellSection);
                            if (BlockBits.fillAir(sectionOfCell, cellMinX & 15, cellMinY & 15, cellMinZ & 15, m, n, defaultBlock)) {
                                s = cellSection;
                                levelChunkSection = sectionOfCell;
                                // Minecraft updates both heightmaps for each block from the top down; the default
                                // block counts for both, so only a column's top block can raise a height.
                                int top = cellMinY + n - 1;
                                for (int y = 0; y < m; y++) {
                                    int aa = (cellMinX + y) & 15;
                                    for (int ab = 0; ab < m; ab++) {
                                        int ad = (cellMinZ + ab) & 15;
                                        heightmap.update(aa, top, ad, defaultBlock);
                                        heightmap2.update(aa, top, ad, defaultBlock);
                                    }
                                }
                                continue;
                            }
                        }
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

                    if (positive && veinCells != null && !voidTerrain) {
                        // Density above zero at every block: the aquifer gives nothing and clears its
                        // fluid-update flag, so a block is what the ore veins give, or the default block.
                        veinCellCount++;
                        veinCells.pregen$selectCorners(t, r);
                        boolean filled = false;
                        if (plan.opaqueDefault && cellMinY >> 4 == cellMinY + n - 1 >> 4) {
                            int cellSection = chunkAccess.getSectionIndex(cellMinY);
                            LevelChunkSection sectionOfCell = chunkAccess.getSection(cellSection);
                            if (BlockBits.fillAir(sectionOfCell, cellMinX & 15, cellMinY & 15, cellMinZ & 15, m, n, defaultBlock)) {
                                s = cellSection;
                                levelChunkSection = sectionOfCell;
                                filled = true;
                                int top = cellMinY + n - 1;
                                for (int y = 0; y < m; y++) {
                                    int aa = (cellMinX + y) & 15;
                                    for (int ab = 0; ab < m; ab++) {
                                        int ad = (cellMinZ + ab) & 15;
                                        heightmap.update(aa, top, ad, defaultBlock);
                                        heightmap2.update(aa, top, ad, defaultBlock);
                                    }
                                }
                            }
                        }
                        for (int u = n - 1; u >= 0; u--) {
                            int v = (i + t) * n + u;
                            int w = v & 15;
                            int x = chunkAccess.getSectionIndex(v);
                            if (s != x) {
                                s = x;
                                levelChunkSection = chunkAccess.getSection(x);
                            }
                            noiseChunk.updateForY(v, (double) u / n);
                            for (int y = 0; y < m; y++) {
                                int z = cellMinX + y;
                                int aa = z & 15;
                                noiseChunk.updateForX(z, (double) y / m);
                                for (int ab = 0; ab < m; ab++) {
                                    int ac = cellMinZ + ab;
                                    int ad = ac & 15;
                                    noiseChunk.updateForZ(ac, (double) ab / m);
                                    BlockState blockState = veinRule.calculate(noiseChunk);
                                    if (blockState == null) {
                                        if (filled) continue;
                                        blockState = defaultBlock;
                                    }
                                    if (blockState != AIR) {
                                        levelChunkSection.setBlockState(aa, w, ad, blockState, false);
                                        heightmap.update(aa, v, ad, blockState);
                                        heightmap2.update(aa, v, ad, blockState);
                                    }
                                }
                            }
                        }
                        continue;
                    }

                    blockCells++;
                    if (last) lastCells++;
                    else if (bounds.allNonPositive()) nonPositiveCells++;
                    else if (bounds.allPositive()) solidVeinCells++;
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
                                BlockState blockState;
                                if (centers != null) {
                                    blockState = centers.pregen$computeSubstance(noiseChunk, fullDensity.compute(noiseChunk));
                                    if (blockState == null && veinRule != null) blockState = veinRule.calculate(noiseChunk);
                                } else {
                                    blockState = access.pregen$getInterpolatedState();
                                }
                                if (blockState == null) {
                                    blockState = defaultBlock;
                                }

                                if (blockState != AIR && !voidTerrain) {
                                    levelChunkSection.setBlockState(aa, w, ad, blockState, false);
                                    heightmap.update(aa, v, ad, blockState);
                                    heightmap2.update(aa, v, ad, blockState);
                                    if (aquifer.shouldScheduleFluidUpdate() && !blockState.getFluidState().isEmpty()) {
                                        mutableBlockPos.set(z, v, ac);
                                        MinecraftVersion.markPosForPostProcessing(chunkAccess, mutableBlockPos);
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
        if (cornerCells != null) cornerCells.pregen$setExactLevels(null);
        SkyColumns.CELLS.add(skyCells);
        if (skyFailed > 0) SkyColumns.FAILED.add(skyFailed);
        AIR_CELLS.add(airCells);
        SOLID_CELLS.add(solidCells);
        BLOCK_CELLS.add(blockCells);
        NON_POSITIVE_BLOCK_CELLS.add(nonPositiveCells);
        SOLID_BLOCK_CELLS.add(solidVeinCells);
        LAST_BLOCK_CELLS.add(lastCells);
        RIDGE_FREE_CELLS.add(ridgeFreeCells);
        VEIN_CELLS.add(veinCellCount);
        return chunkAccess;
    }

    private static boolean noVein(Plan plan, int t, int r, int minX, int minY, int minZ, int width, int height) {
        if (!plan.veins || minY > VEIN_MAX_Y || minY + height - 1 < VEIN_MIN_Y) return true;
        if (plan.veinToggle == null) return false;
        plan.veinToggle.evaluate(t, r, minX, minY, minZ, width, height);
        return plan.veinToggle.maxAbs() < VEIN_THRESHOLD;
    }

    /**
     * OreVeinifier gives nothing where the ridge value is zero or above, whatever its other values: a cell
     * whose ridge values stay above zero has no vein block.
     */
    private static boolean noRidge(Plan plan, int t, int r, int minX, int minY, int minZ, int width, int height) {
        if (plan.veinRidged == null) return false;
        plan.veinRidged.evaluate(t, r, minX, minY, minZ, width, height);
        return plan.veinRidged.allPositive();
    }

    /**
     * What the per-block rule of a vanilla NoiseChunk consists of, or null if not vanilla:
     * aquiferRule when its first rule is Minecraft's aquifer over fullDensity, veinRule the second,
     * veinOnly when those two are all its rules and the second is Minecraft's ore veins.
     */
    private record Plan(DensityBounds bounds, boolean veins, DensityBounds veinToggle, DensityBounds veinRidged,
                        boolean aquiferRule, DensityFunction fullDensity, NoiseChunk.BlockStateFiller veinRule,
                        boolean veinOnly, boolean opaqueDefault) {
        static Plan of(NoiseChunkAccessor access, RandomState randomState, BlockState defaultBlock) {
            // Whether the default block counts for both heightmaps the fill keeps (stone does).
            boolean opaqueDefault = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(defaultBlock)
                    && Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(defaultBlock);
            // Vanilla's rule: [aquifer over cacheAllInCell(finalDensity + beardifier)], plus the
            // ore veinifier when ore veins are enabled. The cell cache is the last one created.
            if (!(access.pregen$getBlockStateRule() instanceof MaterialRuleList rules)) return null;
            int fillers = rules.materialRuleList().length;
            if (fillers < 1 || fillers > 2) return null;
            List<?> caches = access.pregen$getCellCaches();
            if (caches.isEmpty() || !(caches.get(caches.size() - 1) instanceof CacheAllInCellAccessor cache)) return null;
            DensityFunction density = cache.pregen$getNoiseFiller();
            if (!DensityBounds.isSumWith(density, access.pregen$getBeardifier())) return null;
            DensityBounds bounds = DensityBounds.compile(density, access.pregen$getBlender());
            DensityBounds veinToggle = null;
            DensityBounds veinRidged = null;
            // NoiseChunk's constructor adds its own lambda first (the aquifer over fullNoiseDensity), then the ore veins'.
            boolean veinLambda = fillers == 2
                    && rules.materialRuleList()[1].getClass().getName().startsWith(OreVeinifier.class.getName() + "$$Lambda");
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
                if (veinLambda && RIDGE_BOUNDS_ENABLED) {
                    DensityFunction ridged = randomState.router().veinRidged().mapAll(access::pregen$wrap);
                    if (access.pregen$getInterpolators().size() == interpolators && access.pregen$getCellCaches().size() == cellCaches) {
                        veinRidged = DensityBounds.compile(ridged, access.pregen$getBlender());
                    }
                }
            }
            NoiseChunk.BlockStateFiller first = rules.materialRuleList()[0];
            boolean aquiferRule = first.getClass().getName().startsWith(NoiseChunk.class.getName() + "$$Lambda");
            // The density the aquifer rule reads is that cell cache itself (NoiseChunk made it for the rule; 26.1 also
            // keeps it in a field of its own, which Minecraft 1.21.11 does not have).
            return new Plan(bounds, fillers == 2, veinToggle, veinRidged, aquiferRule, (DensityFunction) (Object) cache,
                    fillers == 2 ? rules.materialRuleList()[1] : null, aquiferRule && veinLambda, opaqueDefault);
        }
    }
}
