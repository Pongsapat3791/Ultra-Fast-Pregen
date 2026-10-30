package com.example.pregen.terrain;

import com.example.pregen.mixin.FlatCacheAccessor;
import com.example.pregen.mixin.ShiftedNoiseAccessor;
import net.minecraft.core.Holder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Remembers a y-independent function at the chunk's sixteen quart columns. Biomes are sampled
 * at 4x96x4 quart positions per chunk, and the temperature and vegetation (humidity) noises
 * are 2D: vanilla's shifted_noise with y_scale 0 and a constant y shift reads y as exactly
 * +0.0 at every height, and its x and z shifts are flat-cached 2D values. So the value at each
 * column is computed once instead of 96 times, and is the same double vanilla computes.
 */
public final class ColumnCachedDensity implements DensityFunction {
    private static final Class<?> SHIFTED_NOISE = DensityFunctions.shiftedNoise2d(DensityFunctions.zero(),
            DensityFunctions.zero(), 1.0, Holder.direct(new NormalNoise.NoiseParameters(0, 1.0))).getClass();
    private static final Class<?> CONSTANT = DensityFunctions.constant(1.0).getClass();

    private final DensityFunction delegate;
    private final int minBlockX;
    private final int minBlockZ;
    private final double[] values = new double[16];
    private int filled;

    private ColumnCachedDensity(DensityFunction delegate, int minBlockX, int minBlockZ) {
        this.delegate = delegate;
        this.minBlockX = minBlockX;
        this.minBlockZ = minBlockZ;
    }

    /**
     * Wraps vanilla's shifted_noise when it cannot depend on y: y_scale 0, a constant y shift,
     * and x and z shifts flat-cached by this NoiseChunk (2D by construction). Else returns f.
     */
    public static DensityFunction wrapIfColumnConstant(DensityFunction f, int minBlockX, int minBlockZ) {
        if (f.getClass() != SHIFTED_NOISE) return f;
        ShiftedNoiseAccessor noise = (ShiftedNoiseAccessor) f;
        if (noise.pregen$getYScale() != 0.0 || noise.pregen$getShiftY().getClass() != CONSTANT
                || !isFlatCache(noise.pregen$getShiftX()) || !isFlatCache(noise.pregen$getShiftZ())) {
            return f;
        }
        return new ColumnCachedDensity(f, minBlockX, minBlockZ);
    }

    private static boolean isFlatCache(DensityFunction f) {
        return f instanceof FlatCacheAccessor;
    }

    @Override
    public double compute(FunctionContext context) {
        int dx = context.blockX() - minBlockX;
        int dz = context.blockZ() - minBlockZ;
        if ((dx & ~12) != 0 || (dz & ~12) != 0) return delegate.compute(context);
        int index = dx | dz >> 2;
        int bit = 1 << index;
        if ((filled & bit) != 0) return values[index];
        double value = delegate.compute(context);
        values[index] = value;
        filled |= bit;
        return value;
    }

    @Override
    public void fillArray(double[] values, ContextProvider provider) {
        provider.fillAllDirectly(values, this);
    }

    /** A leaf to visitors: before 26.2 each function applies the visitor to itself. */
    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(this);
    }

    /** A leaf to visitors from 26.2, whose mapAll walk applies the visitor to the result itself. */
    public DensityFunction mapChildren(Visitor visitor) {
        return this;
    }

    @Override
    public double minValue() {
        return delegate.minValue();
    }

    @Override
    public double maxValue() {
        return delegate.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return delegate.codec();
    }
}
