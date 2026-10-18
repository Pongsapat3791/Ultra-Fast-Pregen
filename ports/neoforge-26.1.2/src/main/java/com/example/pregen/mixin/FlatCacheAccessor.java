package com.example.pregen.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Marks and reads NoiseChunk's per-chunk 2D cache (values at quart columns, computed at y 0). */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$FlatCache")
public interface FlatCacheAccessor {
    @Accessor("values")
    double[] pregen$getValues();
}
