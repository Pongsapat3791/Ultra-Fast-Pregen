package com.example.pregen.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$YClampedGradient")
public interface YClampedGradientAccessor {
    @Accessor("fromY")
    int pregen$getFromY();

    @Accessor("toY")
    int pregen$getToY();

    @Accessor("fromValue")
    double pregen$getFromValue();

    @Accessor("toValue")
    double pregen$getToValue();
}
