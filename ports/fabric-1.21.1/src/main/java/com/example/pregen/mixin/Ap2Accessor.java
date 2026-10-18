package com.example.pregen.mixin;

import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$Ap2")
public interface Ap2Accessor {
    @Accessor("argument1")
    DensityFunction pregen$getArgument1();

    @Accessor("argument2")
    DensityFunction pregen$getArgument2();
}
