package com.example.pregen.mixin;

import com.example.pregen.WorldgenCompat;
import com.example.pregen.terrain.SurfaceBiomes;
import com.example.pregen.terrain.TerrainGenerationContext;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The surface step's chunk and biome lookup on this mod's terrain workers, for its rules' context (see SurfaceBiomes).
 * Minecraft 26.1 to 26.1.2, whose buildSurface takes the biome registry; SurfaceSystem262Mixin on 26.2 (PregenMixinPlugin).
 */
@Mixin(SurfaceSystem.class)
public abstract class SurfaceSystem261Mixin {
    @Inject(method = "buildSurface", at = @At("HEAD"))
    private void pregen$beginSurface(RandomState randomState, BiomeManager biomeManager, Registry<Biome> biomes,
                                     boolean useLegacyRandomSource, WorldGenerationContext generationContext,
                                     ChunkAccess chunk, NoiseChunk noiseChunk, SurfaceRules.RuleSource ruleSource,
                                     CallbackInfo ci) {
        if (SurfaceBiomes.enabled && TerrainGenerationContext.isActive() && WorldgenCompat.surfaceShortcutsAllowed()) {
            SurfaceBiomes.beginSurface(biomeManager, chunk);
        }
    }

    @Inject(method = "buildSurface", at = @At("RETURN"))
    private void pregen$endSurface(RandomState randomState, BiomeManager biomeManager, Registry<Biome> biomes,
                                   boolean useLegacyRandomSource, WorldGenerationContext generationContext,
                                   ChunkAccess chunk, NoiseChunk noiseChunk, SurfaceRules.RuleSource ruleSource,
                                   CallbackInfo ci) {
        if (TerrainGenerationContext.isActive()) SurfaceBiomes.endSurface();
    }
}
