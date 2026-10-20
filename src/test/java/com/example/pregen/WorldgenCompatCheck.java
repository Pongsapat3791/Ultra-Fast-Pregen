package com.example.pregen;

import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DebugLevelSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

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
        List<String> statusForeign = WorldgenCompat.foreignMixinConfigs(ChunkStatus.class);
        require(!generatorForeign.contains("pregen.mixins.json"), "This mod's config is never foreign.");
        // Only mixins that change the target's code count: this mod's invoker only adds methods,
        // its generator mixin injects into doFill, which the fused path runs as well.
        java.util.Set<String> skipped = WorldgenCompat.FUSED_SKIPPED_GENERATOR_METHODS;
        require(!changes("com.example.pregen.mixin.NoiseBasedChunkGeneratorInvoker", NoiseBasedChunkGenerator.class, null),
                "An invoker mixin leaves the generator's code unchanged.");
        require(changes("com.example.pregen.mixin.NoiseBasedChunkGeneratorMixin", NoiseBasedChunkGenerator.class, null),
                "A mixin injecting into the generator changes it.");
        require(!changes("com.example.pregen.mixin.NoiseBasedChunkGeneratorMixin", NoiseBasedChunkGenerator.class, skipped),
                "A mixin into doFill leaves what the fused path skips unchanged.");
        require(changes("com.example.pregen.mixin.NoiseBasedAquiferMixin", Aquifer.NoiseBasedAquifer.class, null),
                "A mixin injecting into the aquifer changes it.");
        require(WorldgenCompat.lambdaOf("lambda$fillFromNoise$4", java.util.Set.of("fillFromNoise"))
                && !WorldgenCompat.lambdaOf("lambda$doFill$1", java.util.Set.of("fillFromNoise")),
                "A replaced lambda of a skipped method must count (Noisium replaces fillFromNoise's)");
        require(WorldgenCompat.selectorNames("fillFromNoise", skipped)
                        && WorldgenCompat.selectorNames("Lnet/minecraft/world/level/levelgen/NoiseBasedChunkGenerator;fillFromNoise(I)V", skipped)
                        && WorldgenCompat.selectorNames("lambda$createBiomes$4", skipped)
                        && WorldgenCompat.selectorNames("*", skipped)
                        && WorldgenCompat.selectorNames("fillFromNoise+", skipped)
                        && WorldgenCompat.selectorNames("fillFromNoise{1,2}", skipped)
                        && WorldgenCompat.selectorNames("name=/fill.*/", skipped),
                "Selectors naming a skipped method, a lambda inside one, or any method must count.");
        require(!WorldgenCompat.selectorNames("doFill", skipped)
                        && !WorldgenCompat.selectorNames("doCreateBiomes(I)V", skipped)
                        && !WorldgenCompat.selectorNames("buildSurface", skipped)
                        && !WorldgenCompat.selectorNames("doFill+", skipped),
                "Selectors naming methods the fused path runs must not count.");
        require(WorldgenCompat.selectorNames("method_38284", WorldgenCompat.FUSED_SKIPPED_STEPS)
                        && WorldgenCompat.selectorNames("Lnet/minecraft/class_2806;m_279981_(I)V", WorldgenCompat.FUSED_SKIPPED_STEPS)
                        && !WorldgenCompat.selectorNames("method_16569", WorldgenCompat.FUSED_SKIPPED_STEPS),
                "The biome and noise steps count, the surface step (run on the fused path) does not.");
        java.util.Set<String> terrainForeign = WorldgenCompat.terrainMixinConfigs();
        java.util.Set<String> strict = new java.util.HashSet<>(generatorForeign);
        strict.addAll(statusForeign);
        require(strict.containsAll(terrainForeign), "The terrain check never counts more than the strict one: "
                + terrainForeign + " vs " + strict);

        // NoiseBasedChunkGenerator is final in 1.20.1; a non-noise generator stands in for custom ones.
        ChunkGenerator other = new DebugLevelSource(lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));
        require(WorldgenCompat.chooseTerrainPath(vanillaGenerator, PreGenConfig.TerrainPipeline.AUTO).fused() == terrainForeign.isEmpty(),
                "Auto must use the fused path exactly when no other mod changes what it skips: " + terrainForeign);
        require(!WorldgenCompat.chooseTerrainPath(other, PreGenConfig.TerrainPipeline.AUTO).fused(),
                "Auto must use Minecraft's path for a non-noise generator.");
        require(!WorldgenCompat.chooseTerrainPath(other, PreGenConfig.TerrainPipeline.FUSED).fused(),
                "Even forced, the fused path needs a noise generator.");
        require(WorldgenCompat.chooseTerrainPath(vanillaGenerator, PreGenConfig.TerrainPipeline.FUSED).fused(),
                "fused in config forces the fast path for noise generators.");
        require(!WorldgenCompat.chooseTerrainPath(vanillaGenerator, PreGenConfig.TerrainPipeline.VANILLA).fused(),
                "vanilla in config always uses Minecraft's path.");
        require(WorldgenCompat.allMixinConfigs(net.minecraft.world.level.levelgen.SurfaceSystem.class).contains("pregen.mixins.json"),
                "The surface step mixin of this mod must be detected.");
        require(WorldgenCompat.surfaceShortcutsAllowed(),
                "With no other mods, the surface rule shortcuts must be on (this mod's own mixins never count).");
        System.out.println("WORLDGEN_COMPAT_CHECK PASS own_mixins_detected=true other_generator_fallback=true overrides=true"
                + " foreign_generator=" + generatorForeign + " foreign_chunk_status=" + statusForeign
                + " terrain_path_foreign=" + terrainForeign
                + " foreign_aquifer=" + WorldgenCompat.foreignMixinConfigs(Aquifer.NoiseBasedAquifer.class)
                + " surface_shortcuts=" + WorldgenCompat.surfaceShortcutsAllowed()
                + " feature_placement=" + WorldgenCompat.featurePlacementMixins());
    }

    private static boolean changes(String mixinClass, Class<?> target, java.util.Set<String> methods)
            throws ReflectiveOperationException {
        for (IMixinInfo mixin : WorldgenCompat.mixinsTargeting(target)) {
            if (mixin.getClassName().equals(mixinClass)) return WorldgenCompat.changes(mixin, target, methods);
        }
        throw new AssertionError(mixinClass + " must be registered on " + target.getName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
