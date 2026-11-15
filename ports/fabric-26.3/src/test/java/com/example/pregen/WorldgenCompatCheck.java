package com.example.pregen;

import java.util.List;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DebugLevelSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * What decides the terrain path and which mixins count as changes. From 26.3 the fused path is
 * Minecraft's own steps on this mod's workers, whatever generator or other mod is there.
 */
public final class WorldgenCompatCheck {
    private WorldgenCompatCheck() {}

    public static void run(NoiseBasedChunkGenerator vanillaGenerator, HolderLookup.Provider lookup)
            throws ReflectiveOperationException {
        // Detection must see this mod's own mixins, or "no foreign mixins" would prove nothing.
        require(WorldgenCompat.allMixinConfigs(NoiseBasedChunkGenerator.class).contains("pregen.mixins.json"),
                "The noise generator mixin of this mod must be detected.");
        List<String> generatorForeign = WorldgenCompat.foreignMixinConfigs(NoiseBasedChunkGenerator.class);
        require(!generatorForeign.contains("pregen.mixins.json"), "This mod's config is never foreign.");
        Set<String> terrain = Set.of("buildTerrain");
        require(changes("com.example.pregen.mixin.NoiseBasedChunkGeneratorMixin", NoiseBasedChunkGenerator.class, null),
                "A mixin redirecting a call in the generator changes it.");
        require(changes("com.example.pregen.mixin.NoiseBasedChunkGeneratorMixin", NoiseBasedChunkGenerator.class, terrain),
                "The redirect names buildTerrain.");
        require(WorldgenCompat.lambdaOf("lambda$buildTerrain$4", terrain) && !WorldgenCompat.lambdaOf("lambda$doFill$1", terrain),
                "A lambda counts for the method it is compiled in.");
        require(WorldgenCompat.selectorNames("buildTerrain", terrain)
                        && WorldgenCompat.selectorNames("Lnet/minecraft/world/level/levelgen/NoiseBasedChunkGenerator;buildTerrain(I)V", terrain)
                        && WorldgenCompat.selectorNames("lambda$buildTerrain$4", terrain)
                        && WorldgenCompat.selectorNames("*", terrain)
                        && WorldgenCompat.selectorNames("buildTerrain+", terrain)
                        && WorldgenCompat.selectorNames("buildTerrain{1,2}", terrain)
                        && WorldgenCompat.selectorNames("name=/build.*/", terrain),
                "Selectors naming a method, a lambda inside it, or any method must count.");
        require(!WorldgenCompat.selectorNames("doFill", terrain) && !WorldgenCompat.selectorNames("doFill+", terrain),
                "Selectors naming other methods must not count.");

        ChunkGenerator other = new DebugLevelSource(lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));
        require(WorldgenCompat.chooseTerrainPath(vanillaGenerator, PreGenConfig.TerrainPipeline.AUTO).fused(),
                "Auto runs Minecraft's steps on the workers.");
        require(WorldgenCompat.chooseTerrainPath(other, PreGenConfig.TerrainPipeline.AUTO).fused(),
                "Any generator's own steps can run on the workers.");
        require(WorldgenCompat.chooseTerrainPath(vanillaGenerator, PreGenConfig.TerrainPipeline.FUSED).fused(),
                "fused in config is the same.");
        require(!WorldgenCompat.chooseTerrainPath(vanillaGenerator, PreGenConfig.TerrainPipeline.VANILLA).fused(),
                "vanilla in config leaves the steps on Minecraft's threads.");
        require(WorldgenCompat.biomeLookupsAllowed(),
                "With no other mods, the feature biome lookups must be on (this mod's own mixins never count).");
        System.out.println("WORLDGEN_COMPAT_CHECK PASS own_mixins_detected=true foreign_generator=" + generatorForeign
                + " biome_lookups=" + WorldgenCompat.biomeLookupsAllowed()
                + " feature_placement=" + WorldgenCompat.featurePlacementMixins());
    }

    private static boolean changes(String mixinClass, Class<?> target, Set<String> methods)
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
