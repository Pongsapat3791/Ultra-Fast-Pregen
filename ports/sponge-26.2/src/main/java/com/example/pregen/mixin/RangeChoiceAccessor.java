package com.example.pregen.mixin;

import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$RangeChoice")
public interface RangeChoiceAccessor {
    @Accessor("input")
    DensityFunction pregen$getInput();

    @Accessor("minInclusive")
    double pregen$getMinInclusive();

    @Accessor("maxExclusive")
    double pregen$getMaxExclusive();

    @Accessor("whenInRange")
    DensityFunction pregen$getWhenInRange();

    @Accessor("whenOutOfRange")
    DensityFunction pregen$getWhenOutOfRange();
}
