package com.example.pregen.mixin;

import com.example.pregen.terrain.SurfaceBiomes;
import com.example.pregen.terrain.SurfaceRulePruning;
import com.example.pregen.terrain.TerrainGenerationContext;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * On this mod's terrain workers, the surface step's biome lookups from the chunk's biomes read
 * once (SurfaceBiomes), in place of the biome getter every way of evaluating the rules calls.
 * Minecraft 26.2 keeps the block's biome in a field, without the supplier per block of earlier
 * versions, so nothing else is needed here.
 * Minecraft 26.2; SurfaceRulesContext261Mixin before (PregenMixinPlugin).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$Context")
public abstract class SurfaceRulesContext262Mixin implements SurfaceRulePruning.ContextBiomes {
    @Shadow @Final @Mutable private Function<BlockPos, Holder<Biome>> biomeGetter;
    @Shadow @Final private ChunkAccess chunk;
    @Unique private SurfaceBiomes pregen$surfaceBiomes;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void pregen$readBiomes(CallbackInfo ci) {
        if (!TerrainGenerationContext.isActive()) return;
        SurfaceBiomes biomes = SurfaceBiomes.forContext(chunk);
        if (biomes != null) {
            // Minecraft's biomeManager::getBiome, from the chunk's biomes.
            pregen$surfaceBiomes = biomes;
            biomeGetter = biomes;
        }
    }

    @Override
    public SurfaceBiomes pregen$getSurfaceBiomes() {
        return pregen$surfaceBiomes;
    }
}
