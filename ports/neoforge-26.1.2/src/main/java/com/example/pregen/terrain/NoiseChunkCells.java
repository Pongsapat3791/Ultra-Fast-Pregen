package com.example.pregen.terrain;

import net.minecraft.world.level.levelgen.DensityFunction;

/** A NoiseChunk as the cell-skipping fill drives it (see NoiseChunkMixin). */
public interface NoiseChunkCells {
    /**
     * NoiseChunk#selectCellYZ without filling the cell caches: for a cell whose blocks are decided
     * without the per-block density (see FastNoiseFill), but which still reads interpolated values.
     */
    void pregen$selectCorners(int cellY, int cellZ);

    /**
     * How many corners, from the bottom, each corner column is filled with from now on (per column:
     * x index * corners per side + z index), the rest being left unset; null for all (see SkyColumns).
     */
    void pregen$setExactLevels(int[] levels);

    /** Whether nothing of the chunk's own enters its density: no blending with older chunks, no structure pieces. */
    boolean pregen$plain();

    /** The chunk placed at a corner of the column being filled, as its slice-filling provider places it. */
    DensityFunction.FunctionContext pregen$atCornerLevel(int level);
}
