package com.example.pregen.mixin;

import com.example.pregen.terrain.SurfaceRulePruning;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** The test of SurfaceRules.isBiome (its condition's compute), for SurfaceRulePruning. */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$BiomeConditionSource")
public abstract class BiomeConditionSourceMixin implements SurfaceRulePruning.BiomeTest {
    @Shadow @Final private Predicate<ResourceKey<Biome>> biomeNameTest;

    @Override
    public boolean pregen$matches(Holder<Biome> biome) {
        return biome.is(biomeNameTest);
    }
}
