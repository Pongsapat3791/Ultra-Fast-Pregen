package com.example.pregen.mixin;

import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The corner values of the current and next X slice of cells. */
@Mixin(NoiseChunk.NoiseInterpolator.class)
public interface NoiseInterpolatorAccessor {
    @Accessor("slice0")
    double[][] pregen$getSlice0();

    @Accessor("slice1")
    double[][] pregen$getSlice1();
}
