package com.example.pregen.terrain;

import com.example.pregen.mixin.NoiseChunkAccessor;
import com.example.pregen.mixin.NoiseInterpolatorAccessor;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.levelgen.NoiseChunk;

/**
 * The cell corners high above the ground, which the fill never reads, left uncomputed.
 * A chunk's terrain is interpolated between 5 x 5 columns of 49 corners, and every corner costs
 * the base 3D noise, cave entrance noises and four noodle cave noises, though about half of them
 * lie in the open sky, where every cell is skipped as air (see FastNoiseFill).
 *
 * Before any corner is computed, each corner column gets the height from which, up to the top, an
 * upper bound of the terrain function stays clearly below zero. The bound leaves the noises out
 * (the base 3D noise within its range, the 2D terrain shape exactly; see
 * DensityBounds#compileAtPoint), so a corner above that height is negative whatever the noises give.
 * A cell whose eight corners are such corners, and which the aquifer answers with air, is one the
 * fill skips as air by its own test, so it is skipped here without the test; and a corner that only
 * such cells touch is never read, so it is not computed (NoiseChunkMixin fills a column up to its
 * last needed corner). Every other corner is computed as before and every other cell goes the
 * usual way, with exact corners. Only for a chunk without structure pieces or blending.
 */
public final class SkyColumns {
    /** Benchmark switch, on unless -Dfastpregen.skyColumns=false. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("fastpregen.skyColumns", "true"));
    /**
     * Test switch (-Dfastpregen.skyColumns.verify=true): every corner is computed, and each cell that would
     * have been skipped as sky is put to the fill's own air test; FAILED counts the cells that fail it.
     */
    public static final boolean VERIFY = Boolean.getBoolean("fastpregen.skyColumns.verify");
    /** A corner counts as sky when the terrain function is proven below this. */
    static final double BELOW = -0.01;
    /** Cells skipped as sky, corners left uncomputed (per interpolated function), and failed checks; for diagnostics. */
    public static final LongAdder CELLS = new LongAdder();
    public static final LongAdder CORNERS = new LongAdder();
    public static final LongAdder FAILED = new LongAdder();
    /** Why chunks had no sky decided beforehand, each said once in the log; for diagnostics. */
    private static final Set<String> REASONS = ConcurrentHashMap.newKeySet();

    private static SkyColumns none(String reason) {
        if (REASONS.add(reason)) {
            com.example.pregen.PreGenMod.LOGGER.info("[PreGen] Sky corners: some chunks compute all of them ({})", reason);
        }
        return null;
    }

    /** Per corner column (x index * side + z index): how many corners, from the bottom, are computed. */
    final int[] exactLevels;
    /** Per cell column (x index * cells + z index): the first cell level, from the bottom, that is sky. */
    final int[] skyFrom;

    private SkyColumns(int[] exactLevels, int[] skyFrom) {
        this.exactLevels = exactLevels;
        this.skyFrom = skyFrom;
    }

    /**
     * The sky of a chunk about to be filled, or null when none of it can be decided beforehand.
     * minX and minZ are the chunk's first blocks, cellMinY its lowest cell's index, cells the cells per side.
     */
    static SkyColumns of(NoiseChunkAccessor access, NoiseChunkCells chunk, DensityBounds bounds, AquiferAirProof airProof,
                         int minX, int minZ, int cellMinY, int cellCountY, int cells, int width, int height) {
        if (!chunk.pregen$plain()) return none("structure pieces near the chunk, or older chunks blended in");
        // Per cell column: the cell levels, up to the top, that the aquifer answers with air at any non-positive
        // density (never the lowest level). Where its answer depends on y alone, one column stands for all.
        int[] airFrom = new int[cells * cells];
        boolean byY = airProof.pregen$fluidByYOnly();
        int lowestAir = cellCountY;
        for (int cx = 0; cx < cells; cx++) {
            for (int cz = 0; cz < cells; cz++) {
                int from = cellCountY;
                if (byY && (cx | cz) != 0) {
                    from = airFrom[0];
                } else {
                    for (int level = cellCountY - 1; level > 0; level--) {
                        if (!airProof.pregen$airAtNonPositiveDensity(minX + cx * width, (cellMinY + level) * height, minZ + cz * width,
                                width, height)) {
                            break;
                        }
                        from = level;
                    }
                }
                airFrom[cx * cells + cz] = from;
                lowestAir = Math.min(lowestAir, from);
            }
        }
        if (lowestAir >= cellCountY) return none("the aquifer answers no level with air");
        // The interpolated function that alone makes a cell non-positive when its corners are below zero.
        NoiseChunk.NoiseInterpolator terrain = null;
        int worldMinY = cellMinY * height;
        int worldMaxY = (cellMinY + cellCountY) * height - 1;
        for (NoiseChunk.NoiseInterpolator candidate : access.pregen$getInterpolators()) {
            if (bounds.nonPositiveWhenAtMost(candidate, BELOW, worldMinY, worldMaxY)) {
                terrain = candidate;
                break;
            }
        }
        if (terrain == null) return none("no interpolated function decides a cell alone");
        // The corner columns must be the ones this walk assumes: one corner more than the cells, up the whole height.
        double[][] slice = ((NoiseInterpolatorAccessor) (Object) terrain).pregen$getSlice0();
        if (slice.length != cells + 1 || slice[0].length != cellCountY + 1) return none("corner columns of another shape");
        DensityBounds upper = DensityBounds.compileAtPoint(((NoiseInterpolatorAccessor) (Object) terrain).pregen$getNoiseFiller());
        if (!upper.decidable()) return none("the terrain function has no bounds");

        int side = cells + 1;
        int levels = cellCountY + 1;
        // Per corner column: the lowest corner from which the function is proven negative all the way up to the
        // top corner. A bound over a taller stretch of the column holds for every shorter one inside it, so the
        // lowest such corner is found by halving.
        int topY = (cellMinY + levels - 1) * height;
        int[] negativeFrom = new int[side * side];
        for (int ix = 0; ix < side; ix++) {
            for (int iz = 0; iz < side; iz++) {
                int x = minX + ix * width;
                int z = minZ + iz * width;
                int low = lowestAir;
                int high = levels;
                while (low < high) {
                    int middle = (low + high) >>> 1;
                    if (upper.upperOver(x, z, (cellMinY + middle) * height, topY) < BELOW) {
                        high = middle;
                    } else {
                        low = middle + 1;
                    }
                }
                negativeFrom[ix * side + iz] = high;
            }
        }
        int[] skyFrom = new int[cells * cells];
        boolean any = false;
        for (int cx = 0; cx < cells; cx++) {
            for (int cz = 0; cz < cells; cz++) {
                int from = Math.max(Math.max(negativeFrom[cx * side + cz], negativeFrom[cx * side + cz + 1]),
                        Math.max(negativeFrom[(cx + 1) * side + cz], negativeFrom[(cx + 1) * side + cz + 1]));
                from = Math.max(from, airFrom[cx * cells + cz]);
                skyFrom[cx * cells + cz] = from;
                any |= from < cellCountY;
            }
        }
        if (!any) return none("no cell proven to be sky");
        // A corner is left out when every cell that touches it is sky: the cells of the (up to four) cell
        // columns around its column, at its own level and the one below.
        int[] exactLevels = new int[side * side];
        long skipped = 0;
        for (int ix = 0; ix < side; ix++) {
            for (int iz = 0; iz < side; iz++) {
                int highest = 0;
                for (int cx = Math.max(ix - 1, 0); cx <= Math.min(ix, cells - 1); cx++) {
                    for (int cz = Math.max(iz - 1, 0); cz <= Math.min(iz, cells - 1); cz++) {
                        highest = Math.max(highest, skyFrom[cx * cells + cz]);
                    }
                }
                int exact = Math.min(levels, highest + 1);
                exactLevels[ix * side + iz] = exact;
                skipped += levels - exact;
            }
        }
        CORNERS.add(skipped);
        return new SkyColumns(exactLevels, skyFrom);
    }
}
