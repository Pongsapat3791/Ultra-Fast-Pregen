package com.example.pregen.mixin;

import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The function under blend_density, which is that function itself where no older chunks are blended in.
 * Minecraft 26.1 to 26.1.2 only: from 26.2 blend_density is a marker the chunk drops (PregenMixinPlugin).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$BlendDensity")
public interface BlendDensityAccessor {
    @Accessor("input")
    DensityFunction pregen$getInput();
}
