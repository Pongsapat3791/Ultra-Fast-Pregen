package com.example.pregen.terrain;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * NoiseChunk's slice-filling context provider for the lowest corners of a column only: the same
 * steps per corner (see NoiseChunkCells#pregen$atCornerLevel), over an array as long as the
 * corners that are computed (see SkyColumns).
 */
public final class CornerLevels implements DensityFunction.ContextProvider {
    private final NoiseChunkCells chunk;
    private int count;

    public CornerLevels(NoiseChunkCells chunk) {
        this.chunk = chunk;
    }

    /** For arrays of this many corners, from the lowest up. */
    public CornerLevels first(int count) {
        this.count = count;
        return this;
    }

    @Override
    public DensityFunction.FunctionContext forIndex(int level) {
        return chunk.pregen$atCornerLevel(level);
    }

    @Override
    public void fillAllDirectly(double[] output, DensityFunction function) {
        for (int level = 0; level < count; level++) {
            output[level] = function.compute(chunk.pregen$atCornerLevel(level));
        }
    }
}
