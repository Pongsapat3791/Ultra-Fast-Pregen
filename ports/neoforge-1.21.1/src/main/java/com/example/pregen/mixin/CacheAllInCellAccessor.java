package com.example.pregen.mixin;

import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$CacheAllInCell")
public interface CacheAllInCellAccessor {
    @Accessor("noiseFiller")
    DensityFunction pregen$getNoiseFiller();
}
