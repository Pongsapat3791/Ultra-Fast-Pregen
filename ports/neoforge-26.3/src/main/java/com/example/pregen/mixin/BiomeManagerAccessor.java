package com.example.pregen.mixin;

import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** BiomeManager internals for the features step's biome lookups (see FeatureBiomes). */
@Mixin(BiomeManager.class)
public interface BiomeManagerAccessor {
    @Accessor("noiseBiomeSource")
    BiomeResolver pregen$getNoiseBiomeSource();

    @Accessor("biomeZoomSeed")
    long pregen$getBiomeZoomSeed();

    @Invoker("getFiddledDistance")
    static double pregen$fiddledDistance(long seed, int x, int y, int z, double xFraction, double yFraction, double zFraction) {
        throw new AssertionError();
    }
}
