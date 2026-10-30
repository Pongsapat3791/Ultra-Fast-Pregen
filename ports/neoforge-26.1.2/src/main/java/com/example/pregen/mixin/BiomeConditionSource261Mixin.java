package com.example.pregen.mixin;

import com.example.pregen.terrain.SurfaceRulePruning;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * The test of SurfaceRules.isBiome (its condition's compute), for SurfaceRulePruning.
 * Minecraft 26.1 to 26.1.2, which test a biome by a predicate on its key; BiomeConditionSource262Mixin on 26.2 (PregenMixinPlugin).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$BiomeConditionSource")
public abstract class BiomeConditionSource261Mixin implements SurfaceRulePruning.BiomeTest {
    @Shadow @Final private Predicate<ResourceKey<Biome>> biomeNameTest;

    @Override
    public boolean pregen$matches(Holder<Biome> biome) {
        return biome.is(biomeNameTest);
    }
}
