package com.example.pregen.mixin;

import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$ShiftedNoise")
public interface ShiftedNoiseAccessor {
    @Accessor("shiftX")
    DensityFunction pregen$getShiftX();

    @Accessor("shiftY")
    DensityFunction pregen$getShiftY();

    @Accessor("shiftZ")
    DensityFunction pregen$getShiftZ();

    @Accessor("yScale")
    double pregen$getYScale();
}
