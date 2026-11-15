package com.example.pregen.mixin;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The corner values of the current and next X slice of cells. */
@Mixin(NoiseChunk.NoiseInterpolator.class)
public interface NoiseInterpolatorAccessor {
    @Accessor("slice0")
    double[][] pregen$getSlice0();

    @Accessor("slice1")
    double[][] pregen$getSlice1();

    /** The function whose values at the cell corners are interpolated. */
    @Accessor("noiseFiller")
    DensityFunction pregen$getNoiseFiller();

    /** Takes the eight corners of a cell from the slices, as NoiseChunk#selectCellYZ does for each interpolator. */
    @Invoker("selectCellYZ")
    void pregen$selectCellYZ(int cellY, int cellZ);
}
