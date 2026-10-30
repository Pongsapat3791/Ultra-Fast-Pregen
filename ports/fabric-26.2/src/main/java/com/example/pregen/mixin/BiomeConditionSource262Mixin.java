package com.example.pregen.mixin;

import com.example.pregen.terrain.SurfaceRulePruning;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * The test of SurfaceRules.isBiome (its condition's compute), for SurfaceRulePruning.
 * Minecraft 26.2, which tests a biome against a HolderSet; BiomeConditionSource261Mixin before (PregenMixinPlugin).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$BiomeConditionSource")
public abstract class BiomeConditionSource262Mixin implements SurfaceRulePruning.BiomeTest {
    @Shadow @Final private HolderSet<Biome> biomes;

    @Override
    public boolean pregen$matches(Holder<Biome> biome) {
        return biomes.contains(biome);
    }
}
