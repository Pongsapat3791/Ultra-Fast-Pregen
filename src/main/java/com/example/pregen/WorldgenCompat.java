package com.example.pregen;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.OreVeinifier;
import net.minecraft.world.level.levelgen.material.MaterialRuleList;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo;

/**
 * Decides when the mod's shortcuts are safe alongside other world-generation mods.
 *
 * The fused terrain path calls NoiseBasedChunkGenerator's private biome and noise steps
 * directly, bypassing createBiomes, fillFromNoise and the ChunkStatus tasks. A custom
 * generator subclass, or another mod's mixin into those classes, may depend on exactly
 * what is bypassed, so either case falls back to Minecraft's own per-status tasks.
 * Datapacks change only registry data, which both paths read, so they need no fallback.
 */
public final class WorldgenCompat {
    private static final String OWN_MIXIN_CONFIG = "pregen.mixins.json";
    private static volatile Boolean aquiferOptimizationAllowed;
    private static volatile Boolean fastNoiseFillAllowed;
    /** Mixin configs known to leave doFill, interpolation, aquifers and material rules untouched. */
    private static final Set<String> FILL_NEUTRAL_CONFIGS = Set.of("fabric-biome-api-v1.mixins.json");

    public record TerrainChoice(boolean fused, String reason) {}

    private WorldgenCompat() {}

    static TerrainChoice chooseTerrainPath(ChunkGenerator generator, PreGenConfig.TerrainPipeline setting) {
        if (setting == PreGenConfig.TerrainPipeline.VANILLA) {
            return new TerrainChoice(false, "terrain_pipeline=vanilla in config");
        }
        if (!(generator instanceof NoiseBasedChunkGenerator)) {
            return new TerrainChoice(false, "generator " + generator.getClass().getName() + " is not noise-based");
        }
        if (setting == PreGenConfig.TerrainPipeline.FUSED) {
            return new TerrainChoice(true, "terrain_pipeline=fused in config");
        }
        if (generator.getClass() != NoiseBasedChunkGenerator.class) {
            return new TerrainChoice(false, "custom generator " + generator.getClass().getName());
        }
        Set<String> foreign = new TreeSet<>(foreignMixinConfigs(NoiseBasedChunkGenerator.class));
        foreign.addAll(foreignMixinConfigs(ChunkStatus.class));
        if (!foreign.isEmpty()) {
            return new TerrainChoice(false, "other mods modify terrain generation " + foreign);
        }
        return new TerrainChoice(true, "vanilla noise generator without other generator mixins");
    }

    /**
     * The aquifer shortcut replaces computeSubstance wholesale, so another mod's mixin into the
     * same class would be silently skipped. Checked once, against the runtime (remapped) name.
     */
    public static boolean aquiferOptimizationAllowed() {
        Boolean allowed = aquiferOptimizationAllowed;
        if (allowed == null) {
            List<String> foreign = foreignMixinConfigs(Aquifer.NoiseBasedAquifer.class);
            allowed = foreign.isEmpty();
            if (!allowed) {
                PreGenMod.LOGGER.info("[PreGen] Aquifer shortcut disabled: other mods modify aquifers {}", foreign);
            }
            aquiferOptimizationAllowed = allowed;
        }
        return allowed;
    }

    /**
     * The cell-skipping fill replaces doFill and reads NoiseChunk, aquifer, beardifier and
     * material-rule internals, so another mod's mixin into any of them disables it.
     */
    public static boolean fastNoiseFillAllowed() {
        Boolean allowed = fastNoiseFillAllowed;
        if (allowed == null) {
            Set<String> foreign = new TreeSet<>();
            for (Class<?> target : List.of(NoiseBasedChunkGenerator.class, NoiseChunk.class,
                    NoiseChunk.NoiseInterpolator.class, Aquifer.NoiseBasedAquifer.class, Beardifier.class,
                    OreVeinifier.class, MaterialRuleList.class)) {
                foreign.addAll(foreignMixinConfigs(target));
            }
            // Fabric API's biome module hooks NoiseChunk's constructor and climate sampler only.
            foreign.removeAll(FILL_NEUTRAL_CONFIGS);
            allowed = foreign.isEmpty();
            if (!allowed) {
                PreGenMod.LOGGER.info("[PreGen] Cell-skipping terrain fill disabled: other mods modify terrain noise {}", foreign);
            }
            fastNoiseFillAllowed = allowed;
        }
        return allowed;
    }

    /**
     * Mixin configs of other mods that change feature placement itself: ChunkGenerator's
     * applyBiomeDecoration or WorldgenRandom's feature seed. Minecraft runs features one chunk at
     * a time per generator, and such mixins may rely on it; BCLib, for one, counts features in a
     * field of the shared generator to derive each feature's seed, which parallel chunks would
     * interleave (ores and other features then land in other places than in vanilla). Features
     * then run one chunk at a time. Empty when parallel features are safe.
     */
    public static List<String> featurePlacementMixins() {
        List<String> found = featurePlacementMixins;
        if (found == null) {
            Set<String> configs = new TreeSet<>();
            for (Class<?> target : List.of(ChunkGenerator.class, WorldgenRandom.class)) {
                try {
                    ClassInfo info = ClassInfo.forName(target.getName().replace('.', '/'));
                    for (Object registered : (Set<?>) registeredMixins().get(info)) {
                        IMixinInfo mixin = (IMixinInfo) registered;
                        String config = mixin.getConfig().getName();
                        if (!OWN_MIXIN_CONFIG.equals(config) && touchesFeaturePlacement(mixin)) configs.add(config);
                    }
                } catch (Throwable failure) {
                    configs.add("unknown (" + failure.getClass().getSimpleName() + ")");
                }
            }
            found = List.copyOf(configs);
            featurePlacementMixins = found;
        }
        return found;
    }

    private static volatile List<String> featurePlacementMixins;
    /** Named, Fabric intermediary and Forge SRG (1.20.1) names of the feature placement methods. */
    private static final Set<String> FEATURE_PLACEMENT_METHODS = Set.of(
            "applyBiomeDecoration", "method_12102", "m_213609_", "setFeatureSeed", "method_12664", "m_190064_");

    private static boolean touchesFeaturePlacement(IMixinInfo mixin) {
        ClassNode node = mixin.getClassNode(0);
        for (MethodNode method : node.methods) {
            for (List<AnnotationNode> annotations : java.util.Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
                if (annotations == null) continue;
                for (AnnotationNode annotation : annotations) {
                    if (mentionsFeaturePlacement(annotation.values)) return true;
                }
            }
        }
        return false;
    }

    /** Whether an annotation's values name a feature placement method (as method or target). */
    private static boolean mentionsFeaturePlacement(Object value) {
        if (value instanceof String text) {
            for (String name : FEATURE_PLACEMENT_METHODS) {
                if (text.equals(name) || text.startsWith(name + "(") || text.contains(";" + name + "(")) return true;
            }
            return false;
        }
        if (value instanceof AnnotationNode nested) return mentionsFeaturePlacement(nested.values);
        if (value instanceof List<?> list) {
            for (Object element : list) if (mentionsFeaturePlacement(element)) return true;
        }
        return false;
    }

    /**
     * Mixin configs other than this mod's that target the class; unknown counts as foreign.
     * Mixin's public lookups do not work after start-up here (getMixinsForClass records applied
     * mixins on the mixin's own class info, and getConfigs only lists configs not yet selected),
     * so this reads the registered mixins on the target's ClassInfo, filled when each mixin's
     * targets are read. The runtime class name keeps this valid with intermediary names.
     */
    static List<String> foreignMixinConfigs(Class<?> target) {
        List<String> configs = new ArrayList<>();
        try {
            for (String config : allMixinConfigs(target)) {
                if (!OWN_MIXIN_CONFIG.equals(config) && !configs.contains(config)) configs.add(config);
            }
        } catch (Throwable failure) {
            configs.add("unknown (" + failure.getClass().getSimpleName() + ")");
        }
        return configs;
    }

    /** Every config, including this mod's, with a mixin targeting the class. */
    static List<String> allMixinConfigs(Class<?> target) throws ReflectiveOperationException {
        ClassInfo info = ClassInfo.forName(target.getName().replace('.', '/'));
        List<String> configs = new ArrayList<>();
        for (Object mixin : (Set<?>) registeredMixins().get(info)) {
            String config = ((IMixinInfo) mixin).getConfig().getName();
            if (!configs.contains(config)) configs.add(config);
        }
        return configs;
    }

    private static Field registeredMixins() throws NoSuchFieldException {
        Field field = ClassInfo.class.getDeclaredField("mixins");
        field.setAccessible(true);
        return field;
    }
}
