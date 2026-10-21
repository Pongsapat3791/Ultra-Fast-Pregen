package com.example.pregen;

import com.example.pregen.terrain.SurfaceRulePruning;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.OreVeinifier;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.material.MaterialRuleList;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.refmap.IReferenceMapper;
import org.spongepowered.asm.mixin.transformer.ClassInfo;

/**
 * Decides when the mod's shortcuts are safe alongside other world-generation mods.
 *
 * The fused terrain path calls NoiseBasedChunkGenerator's private biome and noise steps
 * directly, bypassing createBiomes, fillFromNoise and the biome and noise ChunkStatus tasks
 * (the surface goes through Minecraft's own step). A custom generator subclass, or another
 * mod's mixin changing what is bypassed, falls back to Minecraft's own per-status tasks;
 * mixins into the rest of the generator apply on both paths.
 * Datapacks change only registry data, which both paths read, so they need no fallback.
 */
public final class WorldgenCompat {
    private static final String OWN_MIXIN_CONFIG = "pregen.mixins.json";
    private static volatile Boolean aquiferOptimizationAllowed;
    private static volatile Boolean fastNoiseFillAllowed;
    /** Mixin configs known to leave doFill, interpolation, aquifers and material rules untouched. */
    private static final Set<String> FILL_NEUTRAL_CONFIGS = Set.of("fabric-biome-api-v1.mixins.json");
    /**
     * Other mods' mixins, by class, with which the cell-skipping fill writes the same blocks
     * (compared block for block on 10 000 chunks with C2ME 0.4.0-alpha.0.123).
     */
    private static final Set<String> FILL_NEUTRAL_MIXINS = Set.of(
            // C2ME: accessors, a constructor-only mixin, Minecraft's random without atomics, its
            // aquifer and beardifier rewrites (same results), and the executor of the noise step.
            "com.ishland.c2me.base.mixin.access.IAquiferSamplerImpl",
            "com.ishland.c2me.base.mixin.access.IChunkNoiseSampler",
            "com.ishland.c2me.base.mixin.access.IChunkNoiseSamplerDensityInterpolator",
            "com.ishland.c2me.base.mixin.access.INoiseChunkGenerator",
            "com.ishland.c2me.base.mixin.access.IStructureWeightSampler",
            "com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading.MixinNoiseChunkGenerator",
            "com.ishland.c2me.opts.worldgen.general.mixin.random_instances.MixinRedirectAtomicSimpleRandom",
            "com.ishland.c2me.opts.worldgen.vanilla.mixin.aquifer.MixinAquiferSamplerImpl",
            "com.ishland.c2me.opts.worldgen.vanilla.mixin.structure_weight_sampler.MixinStructureWeightSampler",
            "com.ishland.c2me.rewrites.chunksystem.mixin.MixinNoiseChunkGenerator");
    /** C2ME's density function compiler: noise compiled to its own code, which the cell-skipping fill cannot read. */
    private static final String C2ME_DFC_CONFIG = "c2me-opts-dfc.mixins.json";
    /**
     * Other mods' mixins, by class, known to leave the terrain of the class they target unchanged;
     * they do not count as modifying it.
     */
    private static final Set<String> NEUTRAL_MIXINS = Set.of(
            // ModernFix: skips surface rules of biomes absent from the chunk; the surface comes out the same.
            "org.embeddedt.modernfix.common.mixin.perf.optimize_surface_rules.NoiseBasedChunkGeneratorMixin",
            // ModernFix worldgen_allocation: the same wrapping and material rules without lambdas or iterators.
            "org.embeddedt.modernfix.common.mixin.perf.worldgen_allocation.NoiseChunkMixin",
            "org.embeddedt.modernfix.common.mixin.perf.worldgen_allocation.MaterialRuleListMixin",
            // ModernFix chunk_deadlock: only the FULL step, which the fused path leaves to Minecraft.
            "org.embeddedt.modernfix.common.mixin.bugfix.chunk_deadlock.ChunkMapLoadMixin");
    /**
     * Mixins into feature placement known to work on separate chunks at the same time, with the
     * same result in any order (shared state thread-safe, randomness seeded per chunk, writes only
     * into the chunk being decorated), by mixin class. Features of such a pack still run in parallel.
     */
    private static final Set<String> PARALLEL_SAFE_FEATURE_MIXINS = Set.of(
            // GregTech CEu: ore veins from a Guava cache, placed in the chunk's own sections with a
            // random seeded from the world seed and chunk position.
            "com.gregtechceu.gtceu.core.mixins.ChunkGeneratorMixin");

    /**
     * Other mods' mixins, by class, whose changes to what the fused path skips concern only work
     * that path does not do; the rest of what they change applies on it as on Minecraft's path.
     */
    private static final Set<String> FUSED_COMPATIBLE_MIXINS = Set.of(
            // C2ME: Minecraft's random sources created without atomics (a redirect in every method,
            // same numbers): seen once MixinExtras' sugar wrappers are read (round 14).
            "com.ishland.c2me.opts.worldgen.general.mixin.random_instances.MixinRedirectAtomicSimpleRandom",
            // C2ME chunk system: runs createBiomes' and fillFromNoise's work on the calling thread
            // instead of Minecraft's worker pool; the fused path runs it on its own pool.
            "com.ishland.c2me.rewrites.chunksystem.mixin.MixinNoiseChunkGenerator");

    /**
     * Other mods' mixins, by class, into the surface rules and biome lookups that the surface
     * shortcuts stand in for, that keep their results: the shortcuts give the same blocks with them.
     */
    private static final Set<String> SURFACE_EQUIVALENT_MIXINS = Set.of(
            // ModernFix: the same lookups and biome tests decided for the chunk, sequences,
            // lazy conditions and the block's biome without iterators or lambdas.
            "org.embeddedt.modernfix.common.mixin.perf.optimize_surface_rules.SurfaceSystemMixin",
            "org.embeddedt.modernfix.common.mixin.perf.optimize_surface_rules.BiomeConditionSourceMixin",
            "org.embeddedt.modernfix.common.mixin.perf.worldgen_allocation.SequenceRuleMixin",
            "org.embeddedt.modernfix.common.mixin.perf.worldgen_allocation.SurfaceRulesMixin",
            "org.embeddedt.modernfix.common.mixin.perf.worldgen_allocation.SurfaceRulesContextMixin",
            // C2ME: sequences over arrays, the block's biome kept without allocations, and
            // Minecraft's biome lookup in native code.
            "com.ishland.c2me.opts.allocs.mixin.surfacebuilder.MixinMaterialRuleContext",
            "com.ishland.c2me.opts.allocs.mixin.surfacebuilder.MixinMaterialRulesSequenceBlockStateRule",
            "com.ishland.c2me.opts.allocs.mixin.surfacebuilder.MixinMaterialRulesSequenceMaterialRule",
            "com.ishland.c2me.opts.natives_math.mixin.MixinBiomeAccess");
    /**
     * The methods the surface shortcuts stand in for, of the classes surfaceShortcutsAllowed reads,
     * with their names in Fabric's intermediary mappings (the builds run on them).
     */
    private static final Set<String> SURFACE_CONTEXT_METHODS = Set.of("<init>", "updateY", "method_39073");
    private static final Set<String> SURFACE_STEP_METHODS = Set.of("buildSurface", "method_39106");
    private static final Set<String> BIOME_LOOKUP_METHODS = Set.of("getBiome", "method_22393");
    private static final Set<String> NOISE_BIOME_METHODS = Set.of("getNoiseBiome", "getBiomeManager", "method_16359", "method_22385");
    /** The condition SurfaceRules.isBiome builds, a local class. */
    private static final List<String> BIOME_CONDITION_CLASSES = List.of(
            "net.minecraft.world.level.levelgen.SurfaceRules$BiomeConditionSource$1BiomeCondition",
            "net.minecraft.class_6686$class_6689$class_6690");
    private static volatile Boolean surfaceShortcutsAllowed;
    /** DensityFunction#mapAll (and the spline coordinate's), with its intermediary names, which NoiseChunkWrap's memo stands in for on the classes it hooks. */
    private static final Set<String> ROUTER_MAPPING_METHODS = Set.of("mapAll", "method_40469", "method_41530");
    private static volatile Boolean noiseChunkWrapAllowed;
    /** Minecraft's light engine, which GridLight runs on its own and hands to the server's. */
    private static final List<Class<?>> LIGHT_ENGINE_CLASSES = List.of(
            net.minecraft.world.level.lighting.LevelLightEngine.class, net.minecraft.world.level.lighting.LightEngine.class,
            net.minecraft.world.level.lighting.BlockLightEngine.class, net.minecraft.world.level.lighting.SkyLightEngine.class,
            net.minecraft.world.level.lighting.LayerLightSectionStorage.class,
            net.minecraft.world.level.lighting.BlockLightSectionStorage.class,
            net.minecraft.world.level.lighting.SkyLightSectionStorage.class,
            net.minecraft.world.level.lighting.DataLayerStorageMap.class,
            net.minecraft.world.level.lighting.ChunkSkyLightSources.class, net.minecraft.world.level.chunk.DataLayer.class,
            net.minecraft.server.level.ThreadedLevelLightEngine.class);
    private static volatile Boolean parallelLightAllowed;

    /** Mixins their own mods declared compatible (API or mod metadata): mixin class -> where it was declared. */
    private static final Map<String, String> DECLARED_COMPATIBLE = new ConcurrentHashMap<>();
    /** Mods that asked for Minecraft's terrain steps: mod id -> their reason. */
    private static final Map<String, String> MINECRAFT_TERRAIN_REQUESTS = new ConcurrentHashMap<>();

    public record TerrainChoice(boolean fused, String reason) {}

    /**
     * A mixin that its mod declares compatible with every shortcut (see PregenApi): it no
     * longer turns the fast terrain path, the cell-skipping fill, the aquifer shortcut or parallel
     * features off. Declarations count for runs that start afterwards.
     */
    static void declareCompatibleMixin(String mixinClassName, String declaredBy) {
        if (mixinClassName != null && !mixinClassName.isBlank()) DECLARED_COMPATIBLE.put(mixinClassName.trim(), declaredBy);
    }

    /** A mod that wants Minecraft's own terrain steps while it is loaded (see PregenApi). */
    static void requireMinecraftTerrain(String modId, String reason) {
        if (modId != null && !modId.isBlank()) MINECRAFT_TERRAIN_REQUESTS.put(modId.trim(), reason == null ? "" : reason);
    }

    /** This mod's own exceptions and the mixins other mods declared compatible. */
    private static boolean neutral(String mixinClassName) {
        return NEUTRAL_MIXINS.contains(mixinClassName) || DECLARED_COMPATIBLE.containsKey(mixinClassName);
    }

    private WorldgenCompat() {}

    static TerrainChoice chooseTerrainPath(ChunkGenerator generator, PreGenConfig.TerrainPipeline setting) {
        if (setting == PreGenConfig.TerrainPipeline.VANILLA) {
            return new TerrainChoice(false, "terrain_pipeline=vanilla in config");
        }
        if (!(generator instanceof NoiseBasedChunkGenerator)) {
            return new TerrainChoice(false, "generator " + generator.getClass().getName() + " is not noise-based");
        }
        if (setting == PreGenConfig.TerrainPipeline.FUSED) {
            List<String> details = new ArrayList<>();
            Set<String> foreign = terrainMixinConfigs(details);
            if (foreign.isEmpty()) return new TerrainChoice(true, "terrain_pipeline=fused in config");
            PreGenMod.LOGGER.warn("[PreGen] terrain_pipeline=fused is forced although other mods modify terrain generation {}:"
                    + " their changes are skipped and the terrain can come out wrong. terrain_pipeline=auto avoids this."
                    + " Skipped: {}", foreign, details);
            return new TerrainChoice(true, "terrain_pipeline=fused in config, skipping other mods' terrain changes " + foreign);
        }
        if (generator.getClass() != NoiseBasedChunkGenerator.class) {
            return new TerrainChoice(false, "custom generator " + generator.getClass().getName());
        }
        if (!MINECRAFT_TERRAIN_REQUESTS.isEmpty()) {
            return new TerrainChoice(false, "requested by " + new java.util.TreeMap<>(MINECRAFT_TERRAIN_REQUESTS));
        }
        if (!DECLARED_COMPATIBLE.isEmpty()) {
            PreGenMod.LOGGER.info("[PreGen] Mixins their mods declare compatible: {}", new java.util.TreeMap<>(DECLARED_COMPATIBLE));
        }
        List<String> details = new ArrayList<>();
        Set<String> foreign = terrainMixinConfigs(details);
        if (!foreign.isEmpty()) {
            PreGenMod.LOGGER.info("[PreGen] Terrain changes of other mods that the fused path would skip: {}", details);
            return new TerrainChoice(false, "other mods modify terrain generation " + foreign);
        }
        return new TerrainChoice(true, "vanilla noise generator, no other mod changes the steps it replaces");
    }

    /**
     * The generator methods the fused path does not call, with their lambdas: it runs
     * doCreateBiomes, doFill and Minecraft's surface step itself, so other mods' changes to those
     * (and to the rest of the generator) apply on it as well.
     */
    static final Set<String> FUSED_SKIPPED_GENERATOR_METHODS = Set.of("createBiomes", "fillFromNoise",
            // Fabric's intermediary names (the Fabric build runs on them): the two methods and their lambdas.
            "method_38275", "method_38333", "method_12088", "method_38332");
    /** Minecraft's generation steps the fused path does not run: it does their work itself. */
    static final Set<String> FUSED_SKIPPED_STEPS = Set.of("generateBiomes", "generateNoise",
            // Fabric's intermediary names: the two steps and generateNoise's lambda.
            "method_57605", "method_57607", "method_57599");

    /**
     * Other mods' configs with mixins that change what the fused path skips: the generator's
     * createBiomes and fillFromNoise, and the biome and noise steps.
     */
    static Set<String> terrainMixinConfigs() {
        return terrainMixinConfigs(null);
    }

    /** As above; each counted mixin and why goes into details when given. */
    static Set<String> terrainMixinConfigs(List<String> details) {
        Set<String> foreign = new TreeSet<>(changingMixinConfigs(NoiseBasedChunkGenerator.class, FUSED_SKIPPED_GENERATOR_METHODS, details));
        foreign.addAll(changingMixinConfigs(ChunkStatusTasks.class, FUSED_SKIPPED_STEPS, details));
        return foreign;
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
                foreign.addAll(foreignMixinConfigs(target, FILL_NEUTRAL_MIXINS));
            }
            // Fabric API's biome module hooks NoiseChunk's constructor and climate sampler only.
            foreign.removeAll(FILL_NEUTRAL_CONFIGS);
            allowed = foreign.isEmpty();
            if (!allowed) {
                PreGenMod.LOGGER.info("[PreGen] Cell-skipping terrain fill disabled: other mods modify terrain noise {}", foreign);
                if (foreign.equals(Set.of(C2ME_DFC_CONFIG))) {
                    PreGenMod.LOGGER.info("[PreGen] With C2ME, set useDensityFunctionCompiler = false under"
                            + " [vanillaWorldGenOptimizations] in config/c2me.toml: pregeneration then uses the"
                            + " cell-skipping fill (faster than C2ME's compiled noise in this mod's tests).");
                }
            }
            fastNoiseFillAllowed = allowed;
        }
        return allowed;
    }

    /**
     * The router-building shortcuts (NoiseChunkWrap) change no result but rely on how Minecraft's
     * NoiseChunk wraps the router (see fastNoiseFillAllowed) and on how named functions, splines
     * and spline coordinates map: another mod's mixin into any of those turns them off.
     */
    public static boolean noiseChunkWrapAllowed() {
        Boolean allowed = noiseChunkWrapAllowed;
        if (allowed == null) {
            List<String> details = new ArrayList<>();
            Set<String> foreign = new TreeSet<>();
            for (Class<?> target : List.of(DensityFunctions.HolderHolder.class, DensityFunctions.Spline.class,
                    DensityFunctions.Spline.Coordinate.class)) {
                foreign.addAll(changingMixinConfigs(target, ROUTER_MAPPING_METHODS, details));
            }
            if (!foreign.isEmpty()) {
                PreGenMod.LOGGER.info("[PreGen] Faster noise router building disabled: other mods modify density functions {}: {}",
                        foreign, details);
            }
            allowed = foreign.isEmpty() && fastNoiseFillAllowed();
            noiseChunkWrapAllowed = allowed;
        }
        return allowed;
    }

    /**
     * Lighting a grid on the workers (GridLight) runs Minecraft's light engine on its own and gives
     * the result to the server's engine as a loaded chunk's light: another mod's change to the light
     * engine (the Starlight family replaces it) keeps the server's engine lighting the grid.
     */
    public static boolean parallelLightAllowed() {
        Boolean allowed = parallelLightAllowed;
        if (allowed == null) {
            List<String> details = new ArrayList<>();
            Set<String> foreign = new TreeSet<>();
            for (Class<?> target : LIGHT_ENGINE_CLASSES) foreign.addAll(changingMixinConfigs(target, null, details));
            allowed = foreign.isEmpty();
            if (!allowed) {
                PreGenMod.LOGGER.info("[PreGen] Lighting on the worker threads disabled: other mods modify the light engine {}: {}",
                        foreign, details);
            }
            parallelLightAllowed = allowed;
        }
        return allowed;
    }

    /**
     * The surface shortcuts (SurfaceBiomes, SurfaceRulePruning) stand in for Minecraft's biome
     * lookup in the surface step and for its rules' biome tests, tests and sequences, so another
     * mod's change to any of those, or to how the rules' context gets the biome of the block,
     * turns them off.
     */
    public static boolean surfaceShortcutsAllowed() {
        Boolean allowed = surfaceShortcutsAllowed;
        if (allowed == null) {
            List<String> details = new ArrayList<>();
            Set<String> foreign = new TreeSet<>();
            List<Class<?>> rules = new ArrayList<>();
            for (Class<?> member : SurfaceRules.class.getDeclaredClasses()) {
                if (member != SurfaceRulePruning.contextClass()) rules.add(member);
            }
            foreign.addAll(changingMixinConfigs(SurfaceRulePruning.contextClass(), SURFACE_CONTEXT_METHODS, details,
                    SURFACE_EQUIVALENT_MIXINS));
            for (String name : BIOME_CONDITION_CLASSES) {
                try {
                    rules.add(Class.forName(name, false, SurfaceRules.class.getClassLoader()));
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // Named so in other mappings only.
                }
            }
            for (Class<?> target : rules) {
                foreign.addAll(changingMixinConfigs(target, null, details, SURFACE_EQUIVALENT_MIXINS));
            }
            foreign.addAll(changingMixinConfigs(SurfaceSystem.class, SURFACE_STEP_METHODS, details, SURFACE_EQUIVALENT_MIXINS));
            foreign.addAll(changingMixinConfigs(BiomeManager.class, BIOME_LOOKUP_METHODS, details, SURFACE_EQUIVALENT_MIXINS));
            foreign.addAll(changingMixinConfigs(LevelReader.class, NOISE_BIOME_METHODS, details, SURFACE_EQUIVALENT_MIXINS));
            foreign.addAll(changingMixinConfigs(WorldGenRegion.class, NOISE_BIOME_METHODS, details, SURFACE_EQUIVALENT_MIXINS));
            allowed = foreign.isEmpty();
            if (!allowed) {
                PreGenMod.LOGGER.info("[PreGen] Surface rule shortcuts disabled: other mods modify surface rules or biome lookups {}: {}",
                        foreign, details);
            }
            surfaceShortcutsAllowed = allowed;
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
                        if (OWN_MIXIN_CONFIG.equals(config) || PARALLEL_SAFE_FEATURE_MIXINS.contains(mixin.getClassName())
                                || DECLARED_COMPATIBLE.containsKey(mixin.getClassName())) continue;
                        if (touchesFeaturePlacement(mixin)) configs.add(config);
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
        return foreignMixinConfigs(target, Set.of());
    }

    /** As above, also leaving out the given mixin classes. */
    static List<String> foreignMixinConfigs(Class<?> target, Set<String> alsoNeutral) {
        List<String> configs = new ArrayList<>();
        try {
            for (IMixinInfo mixin : mixinsTargeting(target)) {
                String config = mixin.getConfig().getName();
                if (OWN_MIXIN_CONFIG.equals(config) || neutral(mixin.getClassName())
                        || alsoNeutral.contains(mixin.getClassName())) continue;
                if (!configs.contains(config)) configs.add(config);
            }
        } catch (Throwable failure) {
            configs.add("unknown (" + failure.getClass().getSimpleName() + ")");
        }
        return configs;
    }

    /**
     * Configs of other mods with a mixin into the class that changes the named methods (all of
     * the class's code when null; FUSED_COMPATIBLE_MIXINS excepted); see changes.
     */
    static List<String> changingMixinConfigs(Class<?> target, Set<String> methods) {
        return changingMixinConfigs(target, methods, null);
    }

    /** As above; each counted mixin and why goes into details when given. */
    static List<String> changingMixinConfigs(Class<?> target, Set<String> methods, List<String> details) {
        return changingMixinConfigs(target, methods, details, FUSED_COMPATIBLE_MIXINS);
    }

    /** As above, with the given mixin classes known to keep what is asked about. */
    private static List<String> changingMixinConfigs(Class<?> target, Set<String> methods, List<String> details,
                                                     Set<String> compatible) {
        List<String> configs = new ArrayList<>();
        try {
            for (IMixinInfo mixin : mixinsTargeting(target)) {
                String config = mixin.getConfig().getName();
                if (OWN_MIXIN_CONFIG.equals(config) || neutral(mixin.getClassName())
                        || compatible.contains(mixin.getClassName())) continue;
                if (configs.contains(config) && details == null) continue;
                String reason = changeReason(mixin, target, methods);
                if (reason == null) continue;
                if (!configs.contains(config)) configs.add(config);
                if (details != null) details.add(mixin.getClassName() + " " + reason);
            }
        } catch (Throwable failure) {
            configs.add("unknown (" + failure.getClass().getSimpleName() + ")");
        }
        return configs;
    }

    /** Mixin annotations that add to the target or refer to what it has, without changing its code. */
    private static final Set<String> STRUCTURAL_ANNOTATIONS = Set.of(
            "Lorg/spongepowered/asm/mixin/Shadow;", "Lorg/spongepowered/asm/mixin/Unique;",
            "Lorg/spongepowered/asm/mixin/Final;", "Lorg/spongepowered/asm/mixin/Mutable;",
            "Lorg/spongepowered/asm/mixin/Dynamic;", "Lorg/spongepowered/asm/mixin/gen/Accessor;",
            "Lorg/spongepowered/asm/mixin/gen/Invoker;");
    /** Annotations in Mixin's injection package that go with an injector rather than being one. */
    private static final Set<String> INJECTOR_HELPERS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Group;", "Lorg/spongepowered/asm/mixin/injection/Surrogate;",
            "Lorg/spongepowered/asm/mixin/injection/Coerce;");

    /**
     * Whether the mixin changes the code of the named methods of its target (methods null: any of
     * its code). Mixins that only add to the class (accessors and invokers, getters, extra
     * interfaces, shadowed fields) change none; BCLib, WorldsTogether, BetterEnd, Regions
     * Unexplored and Porting Lib have only such mixins into the noise generator and ChunkStatus.
     * Injectors and other transformations count by the methods their selectors name, resolved
     * through the mixin's refmap as Mixin does; overwrites and methods replacing one of the
     * target's by their own name. A selector this cannot read, and anything unreadable, counts.
     */
    static boolean changes(IMixinInfo mixin, Class<?> target, Set<String> methods) {
        return changeReason(mixin, target, methods) != null;
    }

    /**
     * What the mixin changes (see changes), or null when nothing. One at a time: the checks run on
     * whichever worker first needs them, and two reading the same mixin at once made one of them
     * fail (Fabric API's WorldViewMixin read as unreadable, which turned the surface shortcuts off).
     */
    static synchronized String changeReason(IMixinInfo mixin, Class<?> target, Set<String> methods) {
        try {
            ClassNode node = mixin.getClassNode(0);
            // Where the skipped work sits in the target's fields (1.20.1's ChunkStatus keeps each
            // step's task in one), anything setting those fields changes it too.
            boolean fieldsMatter = methods == null || target.getName().endsWith(".ChunkStatus");
            Set<String> shadowFields = new HashSet<>();
            for (FieldNode field : node.fields) {
                if (hasAnnotation(field.visibleAnnotations, field.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;")) {
                    shadowFields.add(field.name);
                }
            }
            Set<String> targetMethods = null;
            for (MethodNode method : node.methods) {
                if (method.name.equals("<init>") || method.name.equals("<clinit>")) {
                    // Merged into the target's own initializers: state every method of it reads.
                    if (!fieldsMatter) continue;
                    for (AbstractInsnNode insn : method.instructions) {
                        if (insn instanceof FieldInsnNode field && field.owner.equals(node.name)
                                && (field.getOpcode() == Opcodes.PUTFIELD || field.getOpcode() == Opcodes.PUTSTATIC)
                                && shadowFields.contains(field.name)) {
                            return "sets " + field.name + " in " + method.name;
                        }
                    }
                    continue;
                }
                boolean mixinAnnotated = false;
                for (List<AnnotationNode> annotations : java.util.Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
                    if (annotations == null) continue;
                    for (AnnotationNode wrapper : annotations) {
                        // MixinExtras rewrites handlers that use sugar (@Local and the like) under
                        // @SugarWrapper and keeps the injector they had in "original": that one
                        // says what the handler changes (Noisium's injection into fillFromNoise's
                        // completion lambda on 1.20.1 is one).
                        AnnotationNode annotation = annotationValue(wrapper, "original") instanceof AnnotationNode original
                                ? original : wrapper;
                        if (STRUCTURAL_ANNOTATIONS.contains(annotation.desc)) {
                            // A setter accessor changes the target's state from outside.
                            if (fieldsMatter && annotation.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")
                                    && Type.getArgumentTypes(method.desc).length > 0) {
                                return "sets a field through accessor " + method.name;
                            }
                            mixinAnnotated = true;
                        } else if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/Overwrite;")
                                || annotation.desc.equals("Lorg/spongepowered/asm/mixin/Intrinsic;")) {
                            if (methods == null || methods.contains(method.name) || lambdaOf(method.name, methods)) {
                                return "overwrites " + method.name;
                            }
                            mixinAnnotated = true;
                        } else if ((annotation.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")
                                        && !INJECTOR_HELPERS.contains(annotation.desc))
                                || annotation.desc.startsWith("Lcom/llamalad7/mixinextras/injector/")) {
                            // An injector: it transforms the methods its selectors name. Other
                            // annotations (Mixin's meta markers) only accompany one.
                            if (methods == null || selectsAny(mixin, annotation, methods)) {
                                return "@" + annotation.desc.substring(annotation.desc.lastIndexOf('/') + 1, annotation.desc.length() - 1)
                                        + " " + annotationValue(annotation, "method");
                            }
                            mixinAnnotated = true;
                        } else if (annotationValue(annotation, "method") != null || annotationValue(annotation, "target") != null) {
                            // An injector this check does not know (a relocated MixinExtras, say):
                            // counted by its selectors all the same.
                            if (methods == null || selectsAny(mixin, annotation, methods)) {
                                return "@" + annotation.desc.substring(annotation.desc.lastIndexOf('/') + 1, annotation.desc.length() - 1)
                                        + " " + annotationValue(annotation, "method");
                            }
                            mixinAnnotated = true;
                        }
                    }
                }
                if (mixinAnnotated || (method.access & Opcodes.ACC_ABSTRACT) != 0) continue;
                if (methods != null) {
                    if (methods.contains(method.name) || lambdaOf(method.name, methods)) return "replaces " + method.name;
                    continue;
                }
                if (targetMethods == null) targetMethods = originalMethods(target);
                if (targetMethods.contains(method.name + method.desc)) return "replaces " + method.name;
            }
            return null;
        } catch (Throwable failure) {
            return "unreadable (" + failure + ")";
        }
    }

    private static Object annotationValue(AnnotationNode annotation, String name) {
        if (annotation.values == null) return null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    /** Whether the annotation's method selectors (after the refmap) name one of the methods. */
    private static boolean selectsAny(IMixinInfo mixin, AnnotationNode annotation, Set<String> methods)
            throws ReflectiveOperationException {
        List<?> selectors = annotationValue(annotation, "method") instanceof List<?> list ? list : null;
        if (selectors == null || selectors.isEmpty()) return true;  // e.g. a @Desc target: not read here
        // The config's refmap (MixinConfig is not public API): the names the selectors resolve to.
        java.lang.reflect.Method getter = mixin.getConfig().getClass().getMethod("getReferenceMapper");
        getter.setAccessible(true);
        IReferenceMapper refMapper = (IReferenceMapper) getter.invoke(mixin.getConfig());
        String mixinName = mixin.getClassName().replace('.', '/');
        for (Object selector : selectors) {
            if (!(selector instanceof String text)) return true;
            String mapped = refMapper == null ? null : refMapper.remap(mixinName, text);
            if (selectorNames(text, methods) || (mapped != null && selectorNames(mapped, methods))) return true;
        }
        return false;
    }

    /**
     * Whether a target selector ("name", "name(desc)", "Lowner;name(desc)", "owner.name", with
     * a quantifier such as name+ or name{1,2}) names one of the methods or a lambda inside one.
     * Wildcards, regular expressions and matcher selectors (name=/.../) count.
     */
    static boolean selectorNames(String selector, Set<String> methods) {
        if (selector.contains("*") || selector.startsWith("/") || selector.contains("=/")) return true;
        String name = selector;
        int paren = name.indexOf('(');
        if (paren >= 0) name = name.substring(0, paren);
        int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(0, colon);
        name = name.substring(Math.max(name.lastIndexOf(';'), name.lastIndexOf('.')) + 1);
        // Left over from a pattern this does not read: counts.
        if (name.contains("/")) return true;
        // Quantifiers: name+ and name{n,m} select methods of that name.
        int brace = name.indexOf('{');
        if (brace >= 0) name = name.substring(0, brace);
        if (name.endsWith("+")) name = name.substring(0, name.length() - 1);
        return methods.contains(name) || lambdaOf(name, methods);
    }

    /** A lambda compiled inside one of the methods (javac names it lambda$method$n). */
    static boolean lambdaOf(String name, Set<String> methods) {
        for (String method : methods) {
            if (name.startsWith("lambda$" + method + "$")) return true;
        }
        return false;
    }

    /**
     * The target's methods as compiled, before mixins added any, and those it inherits, as
     * name + descriptor in runtime names. A mixin method with one of these replaces or overrides it.
     */
    private static Set<String> originalMethods(Class<?> target) {
        Set<String> methods = new HashSet<>();
        boolean own = true;
        for (ClassInfo info = ClassInfo.forName(target.getName().replace('.', '/')); info != null; info = info.getSuperClass()) {
            for (ClassInfo.Method method : info.getMethods()) {
                if (own && method.isInjected()) continue;
                methods.add(method.getName() + method.getDesc());
            }
            own = false;
        }
        return methods;
    }

    private static boolean hasAnnotation(List<AnnotationNode> visible, List<AnnotationNode> invisible, String desc) {
        for (List<AnnotationNode> annotations : java.util.Arrays.asList(visible, invisible)) {
            if (annotations == null) continue;
            for (AnnotationNode annotation : annotations) if (annotation.desc.equals(desc)) return true;
        }
        return false;
    }

    /** Every config, including this mod's, with a mixin targeting the class. */
    static List<String> allMixinConfigs(Class<?> target) throws ReflectiveOperationException {
        List<String> configs = new ArrayList<>();
        for (IMixinInfo mixin : mixinsTargeting(target)) {
            String config = mixin.getConfig().getName();
            if (!configs.contains(config)) configs.add(config);
        }
        return configs;
    }

    private static volatile String chunkSystemReplacement;

    /**
     * A mod that replaces Minecraft's chunk system (chunk holders, chunk storage and the light
     * engine), which this mod generates and saves through, or null. Moonrise (Paper's chunk
     * system) does: none of the batch's chunks would reach the disk or be lit the way they are
     * here, so a run does not start (see PreGenCommand).
     */
    public static String chunkSystemReplacement() {
        String known = chunkSystemReplacement;
        if (known == null) {
            known = "";
            try {
                for (IMixinInfo mixin : mixinsTargeting(net.minecraft.server.level.ChunkMap.class)) {
                    if (mixin.getConfig().getName().startsWith("moonrise")) {
                        known = "Moonrise";
                        break;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                PreGenMod.LOGGER.debug("[PreGen] Could not read the chunk system's mixins", failure);
            }
            chunkSystemReplacement = known;
        }
        return known.isEmpty() ? null : known;
    }

    static List<IMixinInfo> mixinsTargeting(Class<?> target) throws ReflectiveOperationException {
        ClassInfo info = ClassInfo.forName(target.getName().replace('.', '/'));
        List<IMixinInfo> mixins = new ArrayList<>();
        for (Object mixin : (Set<?>) registeredMixins().get(info)) mixins.add((IMixinInfo) mixin);
        return mixins;
    }

    private static Field registeredMixins() throws NoSuchFieldException {
        Field field = ClassInfo.class.getDeclaredField("mixins");
        field.setAccessible(true);
        return field;
    }
}
