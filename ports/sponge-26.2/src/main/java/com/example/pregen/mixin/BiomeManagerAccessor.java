package com.example.pregen.mixin;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** BiomeManager internals for the surface step's biome lookups (see SurfaceBiomes). */
@Mixin(BiomeManager.class)
public interface BiomeManagerAccessor {
    @Accessor("noiseBiomeSource")
    BiomeManager.NoiseBiomeSource pregen$getNoiseBiomeSource();

    @Accessor("biomeZoomSeed")
    long pregen$getBiomeZoomSeed();

    @Invoker("getFiddledDistance")
    static double pregen$fiddledDistance(long seed, int x, int y, int z, double xFraction, double yFraction, double zFraction) {
        throw new AssertionError();
    }
}
