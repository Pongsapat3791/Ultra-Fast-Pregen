package com.example.pregen;

import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DebugLevelSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/** Mixin-target detection and terrain-path selection, against the live Mixin environment. */
public final class WorldgenCompatCheck {
    private WorldgenCompatCheck() {}

    public static void run(NoiseBasedChunkGenerator vanillaGenerator, HolderLookup.Provider lookup)
            throws ReflectiveOperationException {
        // Detection must see this mod's own mixins, or "no foreign mixins" would prove nothing.
        require(WorldgenCompat.allMixinConfigs(Aquifer.NoiseBasedAquifer.class).contains("pregen.mixins.json"),
                "The aquifer mixin of this mod must be detected.");
        require(WorldgenCompat.allMixinConfigs(NoiseBasedChunkGenerator.class).contains("pregen.mixins.json"),
                "The generator invoker of this mod must be detected.");
        List<String> generatorForeign = WorldgenCompat.foreignMixinConfigs(NoiseBasedChunkGenerator.class);
        List<String> statusForeign = WorldgenCompat.foreignMixinConfigs(ChunkStatusTasks.class);
        require(!generatorForeign.contains("pregen.mixins.json"), "This mod's config is never foreign.");

        // NoiseBasedChunkGenerator is final in 1.20.1; a non-noise generator stands in for custom ones.
        ChunkGenerator other = new DebugLevelSource(lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));
        boolean foreign = !generatorForeign.isEmpty() || !statusForeign.isEmpty();
        require(WorldgenCompat.chooseTerrainPath(vanillaGenerator, PreGenConfig.TerrainPipeline.AUTO).fused() == !foreign,
                "Auto must use the fused path exactly when no other mod targets the generator or ChunkStatus.");
        require(!WorldgenCompat.chooseTerrainPath(other, PreGenConfig.TerrainPipeline.AUTO).fused(),
                "Auto must use Minecraft's path for a non-noise generator.");
        require(!WorldgenCompat.chooseTerrainPath(other, PreGenConfig.TerrainPipeline.FUSED).fused(),
                "Even forced, the fused path needs a noise generator.");
        require(WorldgenCompat.chooseTerrainPath(vanillaGenerator, PreGenConfig.TerrainPipeline.FUSED).fused(),
                "fused in config forces the fast path for noise generators.");
        require(!WorldgenCompat.chooseTerrainPath(vanillaGenerator, PreGenConfig.TerrainPipeline.VANILLA).fused(),
                "vanilla in config always uses Minecraft's path.");
        System.out.println("WORLDGEN_COMPAT_CHECK PASS own_mixins_detected=true other_generator_fallback=true overrides=true"
                + " foreign_generator=" + generatorForeign + " foreign_chunk_status=" + statusForeign
                + " foreign_aquifer=" + WorldgenCompat.foreignMixinConfigs(Aquifer.NoiseBasedAquifer.class)
                + " feature_placement=" + WorldgenCompat.featurePlacementMixins());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
