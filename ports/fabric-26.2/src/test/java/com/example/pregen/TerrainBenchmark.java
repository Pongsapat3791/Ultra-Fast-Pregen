package com.example.pregen;

import com.example.pregen.terrain.TerrainGenerationContext;
import com.mojang.serialization.Lifecycle;
import it.unimi.dsi.fastutil.shorts.ShortList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Repeatable, in-memory Stage 2 benchmark. It never opens or writes a Minecraft world.
 * Run through Fabric to measure mixins; a standalone main measures unmodified classes.
 * Chunk allocation, checksumming, registry bootstrap, warmup and JFR dumps are untimed.
 */
public final class TerrainBenchmark {
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final int[][] ORIGINS = {{0, 0}, {-257, -257}, {512, -513}, {-1024, 768}};
    private static final Method CREATE_BIOMES = privateMethod("doCreateBiomes", Blender.class,
            RandomState.class, StructureManager.class, ChunkAccess.class);
    private static final Method FILL = privateMethod("doFill", Blender.class,
            StructureManager.class, RandomState.class, ChunkAccess.class, int.class, int.class);

    /** Runs once mods have loaded when -Dpregen.benchmark=true, then exits (see PregenChecksMod). */
    public static void runIfRequested() {
        if (!Boolean.getBoolean("pregen.benchmark")) return;
        try {
            main(new String[0]);
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    /** The block and biome containers of chunks over this biome registry (as PalettedContainerFactory#create). */
    static net.minecraft.world.level.chunk.PalettedContainerFactory containerFactory(Registry<Biome> biomes) {
        var blocks = net.minecraft.world.level.chunk.Strategy.createForBlockStates(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY);
        var biomeStrategy = net.minecraft.world.level.chunk.Strategy.createForBiomes(biomes.asHolderIdMap());
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        var plains = biomes.getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
        return new net.minecraft.world.level.chunk.PalettedContainerFactory(blocks, air,
                net.minecraft.world.level.chunk.PalettedContainer.codecRW(net.minecraft.world.level.block.state.BlockState.CODEC, blocks, air),
                biomeStrategy, plains,
                net.minecraft.world.level.chunk.PalettedContainer.codecRO(biomes.holderByNameCodec(), biomeStrategy, plains));
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        AquiferCenterCacheCheck.run();
        ChunkBatchPlanCheck.run();
        DynamicBatchPreGenProgressCheck.run();
        PreGenConfigCheck.run();
        PreGenSaveFileCheck.run();
        PhaseSpacingCheck.run();
        ChunkSaveCheck.run();
        C2meStorageCheck.run();
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        MappedRegistry<Biome> mutableBiomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME).listElements()
                .sorted(Comparator.comparing(b -> b.key().identifier().toString()))
                .forEach(b -> mutableBiomes.register(b.key(), b.value(), net.minecraft.core.RegistrationInfo.BUILT_IN));
        Registry<Biome> biomeRegistry = mutableBiomes.freeze();
        BlockEntityPromotionCheck.run(biomeRegistry);
        Holder<NoiseGeneratorSettings> settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        MultiNoiseBiomeSource biomeSource = MultiNoiseBiomeSource.createFromPreset(
                lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                        .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(biomeSource, settings);
        FlatClimateIndexCheck.run(lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD).value().parameters());
        WorldgenCompatCheck.run(generator, lookup);
        List<SeedContext> contexts = new ArrayList<>();
        for (long seed : config.seeds) {
            contexts.add(new SeedContext(seed, RandomState.create(settings.value(),
                    lookup.lookupOrThrow(Registries.NOISE), seed)));
        }
        presetParity(lookup, biomeRegistry, config.threads);
        AtomicInteger workerIds = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(config.threads, task -> {
            Thread thread = new Thread(task, "TerrainBenchmark-" + workerIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        Recording recording = null;
        try {
            System.out.printf(Locale.ROOT,
                    "TERRAIN_BENCHMARK chunks_per_seed=%d seeds=%s threads=%d iterations=%d warmup_chunks=%d warmup_iterations=%d%n",
                    config.chunks, config.seeds, config.threads, config.iterations,
                    config.warmupChunks, config.warmupIterations);
            System.out.println("Timing includes NoiseChunk construction, biomes, density noise and surface; structures and region allocation are omitted.");
            List<Boolean> modes = config.compare ? List.of(false, true) : List.of(config.optimized);
            for (int iteration = 0; iteration < config.warmupIterations; iteration++) {
                for (boolean optimized : modes) {
                    for (SeedContext context : contexts) {
                        run(context, config.warmupChunks, generator, settings.value(), biomeRegistry, workers, false, optimized);
                    }
                }
            }
            if (config.jfr != null) {
                Path parent = config.jfr.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                recording = new Recording(Configuration.getConfiguration("profile"));
                recording.setName("Stage 2 terrain generation after warmup");
                recording.start();
            }
            Map<Long, String> expectedChecksums = new HashMap<>();
            List<String> csv = new ArrayList<>();
            csv.add("mode,iteration,seed,chunks,threads,wall_seconds,chunks_per_second,setup_worker_ms,biomes_worker_ms,noise_worker_ms,surface_worker_ms,checksum");
            Map<Boolean, List<Double>> iterationSeconds = new HashMap<>();
            for (boolean mode : modes) iterationSeconds.put(mode, new ArrayList<>());
            for (int iteration = 1; iteration <= config.iterations; iteration++) {
                Map<Boolean, Double> iterationWall = new HashMap<>();
                // Alternate the execution order to reduce warmup/order bias.
                List<Boolean> order = config.compare && iteration % 2 == 0 ? List.of(true, false) : modes;
                for (SeedContext context : contexts) {
                  for (boolean optimized : order) {
                    Result result = run(context, config.chunks, generator, settings.value(), biomeRegistry, workers, true, optimized);
                    String expected = expectedChecksums.putIfAbsent(context.seed, result.checksum);
                    if (expected != null && !expected.equals(result.checksum)) {
                        throw new AssertionError("Terrain parity failure for seed " + context.seed
                                + ": " + expected + " != " + result.checksum);
                    }
                    iterationWall.merge(optimized, result.seconds(), Double::sum);
                    String row = String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%.6f,%.3f,%.3f,%.3f,%.3f,%.3f,%s",
                            optimized ? "optimized" : "vanilla", iteration, context.seed, config.chunks, config.threads, result.seconds(),
                            config.chunks / result.seconds(), result.setupNs / 1e6, result.biomesNs / 1e6,
                            result.noiseNs / 1e6, result.surfaceNs / 1e6, result.checksum);
                    csv.add(row);
                    System.out.println("TERRAIN_RESULT " + row);
                  }
                }
                for (boolean mode : modes) {
                    double wall = iterationWall.get(mode);
                    iterationSeconds.get(mode).add(wall);
                    System.out.printf(Locale.ROOT, "TERRAIN_ITERATION mode=%s iteration=%d wall_seconds=%.6f chunks_per_second=%.3f%n",
                            mode ? "optimized" : "vanilla", iteration, wall, config.chunks * contexts.size() / wall);
                }
            }
            System.out.printf(Locale.ROOT, "TERRAIN_CELLS air=%d solid=%d per_block=%d%n",
                    com.example.pregen.terrain.FastNoiseFill.AIR_CELLS.sum(), com.example.pregen.terrain.FastNoiseFill.SOLID_CELLS.sum(),
                    com.example.pregen.terrain.FastNoiseFill.BLOCK_CELLS.sum());
            for (boolean mode : modes) {
                List<Double> times = iterationSeconds.get(mode);
                times.sort(Double::compare);
                int middle = times.size() / 2;
                double median = times.size() % 2 == 0 ? (times.get(middle - 1) + times.get(middle)) / 2 : times.get(middle);
                System.out.printf(Locale.ROOT, "TERRAIN_SUMMARY mode=%s median_seconds=%.6f chunks_per_second=%.3f deterministic=true%n",
                        mode ? "optimized" : "vanilla", median, config.chunks * contexts.size() / median);
            }
            if (config.output != null) {
                Path parent = config.output.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.write(config.output, csv);
                System.out.println("TERRAIN_CSV " + config.output.toAbsolutePath());
            }
        } finally {
            workers.shutdownNow();
            if (recording != null) {
                recording.stop();
                recording.dump(config.jfr);
                recording.close();
                System.out.println("TERRAIN_JFR " + config.jfr.toAbsolutePath());
            }
        }
    }

    /**
     * Every vanilla noise preset, not just the overworld: the optimized terrain must produce the
     * same blocks, biomes, heightmaps and fluid updates as Minecraft's for each.
     */
    private static void presetParity(HolderLookup.Provider lookup, Registry<Biome> biomeRegistry, int threads) throws Exception {
        var noise = lookup.lookupOrThrow(Registries.NOISE_SETTINGS);
        var parameters = lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
        net.minecraft.world.level.biome.BiomeSource overworld = MultiNoiseBiomeSource.createFromPreset(
                parameters.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        net.minecraft.world.level.biome.BiomeSource nether = MultiNoiseBiomeSource.createFromPreset(
                parameters.getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));
        net.minecraft.world.level.biome.BiomeSource end = net.minecraft.world.level.biome.TheEndBiomeSource.create(
                lookup.lookupOrThrow(Registries.BIOME));
        Map<net.minecraft.resources.ResourceKey<NoiseGeneratorSettings>, net.minecraft.world.level.biome.BiomeSource> presets =
                new java.util.LinkedHashMap<>();
        presets.put(NoiseGeneratorSettings.LARGE_BIOMES, overworld);
        presets.put(NoiseGeneratorSettings.AMPLIFIED, overworld);
        presets.put(NoiseGeneratorSettings.NETHER, nether);
        presets.put(NoiseGeneratorSettings.END, end);
        presets.put(NoiseGeneratorSettings.CAVES, overworld);
        presets.put(NoiseGeneratorSettings.FLOATING_ISLANDS, overworld);
        ExecutorService workers = Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(task, "TerrainPreset");
            thread.setDaemon(true);
            return thread;
        });
        List<String> checked = new ArrayList<>();
        try {
            for (var preset : presets.entrySet()) {
                Holder<NoiseGeneratorSettings> settings = noise.getOrThrow(preset.getKey());
                NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(preset.getValue(), settings);
                for (long seed : new long[]{42L, -7046029254386353131L}) {
                    SeedContext context = new SeedContext(seed, RandomState.create(settings.value(),
                            lookup.lookupOrThrow(Registries.NOISE), seed));
                    String vanilla = run(context, 64, generator, settings.value(), biomeRegistry, workers, true, false).checksum;
                    String optimized = run(context, 64, generator, settings.value(), biomeRegistry, workers, true, true).checksum;
                    if (!vanilla.equals(optimized)) {
                        throw new AssertionError("Terrain parity failure for " + preset.getKey().identifier()
                                + " seed " + seed + ": " + vanilla + " != " + optimized);
                    }
                }
                checked.add(preset.getKey().identifier().getPath());
            }
        } finally {
            workers.shutdownNow();
        }
        System.out.println("PRESET_PARITY_CHECK PASS presets=" + checked + " seeds=2 chunks=64");
    }

    private static Result run(SeedContext seed, int count, NoiseBasedChunkGenerator generator,
                              NoiseGeneratorSettings settings, Registry<Biome> biomeRegistry,
                              ExecutorService workers, boolean checksum, boolean optimized) throws Exception {
        NoiseSettings noiseSettings = settings.noiseSettings();
        LevelHeightAccessor height = LevelHeightAccessor.create(noiseSettings.minY(), noiseSettings.height());
        List<ProtoChunk> chunks = new ArrayList<>(count);
        Map<Long, ProtoChunk> byPosition = new HashMap<>();
        int width = Math.max(1, (int) Math.ceil(Math.sqrt(count / 4.0)));
        for (int index = 0; index < count; index++) {
            int[] origin = ORIGINS[index % ORIGINS.length];
            int local = index / ORIGINS.length;
            ChunkPos position = new ChunkPos(origin[0] + local % width, origin[1] + local / width);
            ProtoChunk chunk = new ProtoChunk(position, UpgradeData.EMPTY, height, containerFactory(biomeRegistry), null);
            chunk.setPersistedStatus(ChunkStatus.STRUCTURE_REFERENCES);
            chunks.add(chunk);
            byPosition.put(position.pack(), chunk);
        }
        BiomeManager biomes = new BiomeManager(new ChunkBiomes(byPosition, generator, seed.randomState),
                BiomeManager.obfuscateSeed(seed.seed));
        WorldGenerationContext generationContext = new WorldGenerationContext(generator, height);
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        Aquifer.FluidStatus water = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
        Aquifer.FluidPicker fluids = (x, y, z) -> y < Math.min(-54, settings.seaLevel()) ? lava : water;
        LongAdder setupTime = new LongAdder();
        LongAdder biomeTime = new LongAdder();
        LongAdder noiseTime = new LongAdder();
        LongAdder surfaceTime = new LongAdder();
        int minCellY = Mth.floorDiv(noiseSettings.minY(), noiseSettings.getCellHeight());
        int cellCountY = Mth.floorDiv(noiseSettings.height(), noiseSettings.getCellHeight());
        long start = System.nanoTime();
        dispatch(chunks, workers, chunk -> {
            // -Dpregen.benchmark.ab=surface: both modes optimized, the surface speedups only in the second;
            // =surfacerules: only the surface rule shortcuts (SurfaceBiomes) in the second.
            String ab = System.getProperty("pregen.benchmark.ab");
            boolean abSurface = "surface".equals(ab) || "surfacerules".equals(ab);
            if ("surface".equals(ab)) com.example.pregen.terrain.FastBlockWrites.enabled = optimized;
            if (abSurface) com.example.pregen.terrain.SurfaceBiomes.enabled = optimized;
            try (TerrainGenerationContext ignored = optimized || abSurface ? TerrainGenerationContext.enter() : null) {
            long componentStart = System.nanoTime();
            chunk.getOrCreateNoiseChunk(c -> NoiseChunk.forChunk(c, seed.randomState,
                    new Beardifier(List.of(), List.of(), null),
                    settings, fluids, Blender.empty()));
            setupTime.add(System.nanoTime() - componentStart);
            componentStart = System.nanoTime();
            invoke(CREATE_BIOMES, generator, Blender.empty(), seed.randomState, null, chunk);
            chunk.setPersistedStatus(ChunkStatus.BIOMES);
            biomeTime.add(System.nanoTime() - componentStart);
            componentStart = System.nanoTime();
            invoke(FILL, generator, Blender.empty(), null, seed.randomState, chunk, minCellY, cellCountY);
            chunk.setPersistedStatus(ChunkStatus.NOISE);
            noiseTime.add(System.nanoTime() - componentStart);
            }
        });
        // Surface biome lookup reads neighboring chunks. Publish all their biomes first.
        boolean surfaceInContext = optimized || "surface".equals(System.getProperty("pregen.benchmark.ab"))
                || "surfacerules".equals(System.getProperty("pregen.benchmark.ab"));
        dispatch(chunks, workers, chunk -> {
            // As on the pipeline's fused path, where surface runs inside the terrain context.
            try (TerrainGenerationContext ignored = surfaceInContext ? TerrainGenerationContext.enter() : null) {
                long componentStart = System.nanoTime();
                generator.buildSurface(chunk, generationContext, seed.randomState, null,
                        biomes, Blender.empty(), null);
                chunk.setPersistedStatus(ChunkStatus.SURFACE);
                surfaceTime.add(System.nanoTime() - componentStart);
            }
        });
        long elapsed = System.nanoTime() - start;
        return new Result(elapsed, setupTime.sum(), biomeTime.sum(), noiseTime.sum(), surfaceTime.sum(),
                checksum ? checksum(chunks, biomeRegistry) : "warmup");
    }

    /** The benchmark chunks' biomes, read as a world generation region reads them (see BiomeChunkSource). */
    private record ChunkBiomes(Map<Long, ProtoChunk> chunks, NoiseBasedChunkGenerator generator, RandomState randomState)
            implements BiomeManager.NoiseBiomeSource, com.example.pregen.terrain.BiomeChunkSource {
        @Override
        public Holder<Biome> getNoiseBiome(int x, int y, int z) {
            ProtoChunk neighbor = chunks.get(ChunkPos.pack(Math.floorDiv(x, 4), Math.floorDiv(z, 4)));
            return neighbor != null ? neighbor.getNoiseBiome(x, y, z)
                    : generator.getBiomeSource().getNoiseBiome(x, y, z, randomState.sampler());
        }

        @Override
        public ChunkAccess pregen$getBiomeChunk(int chunkX, int chunkZ) {
            return chunks.get(ChunkPos.pack(chunkX, chunkZ));
        }
    }

    private static void dispatch(List<ProtoChunk> chunks, ExecutorService workers, ChunkTask task) {
        List<CompletableFuture<Void>> pending = new ArrayList<>(chunks.size());
        for (ProtoChunk chunk : chunks) {
            pending.add(CompletableFuture.runAsync(() -> task.run(chunk), workers));
        }
        CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).join();
    }

    private static String checksum(List<ProtoChunk> chunks, Registry<Biome> biomeRegistry) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES * 5);
        for (ProtoChunk chunk : chunks) {
            long blocks = FNV_OFFSET;
            long biomes = FNV_OFFSET;
            for (LevelChunkSection section : chunk.getSections()) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            blocks = mix(blocks, Block.getId(section.getBlockState(x, y, z)));
                        }
                    }
                }
                for (int y = 0; y < 4; y++) {
                    for (int z = 0; z < 4; z++) {
                        for (int x = 0; x < 4; x++) {
                            biomes = mix(biomes, biomeRegistry.getId(section.getNoiseBiome(x, y, z).value()));
                        }
                    }
                }
            }
            long heightmaps = FNV_OFFSET;
            for (Heightmap.Types type : Heightmap.Types.values()) {
                if (chunk.hasPrimedHeightmap(type)) {
                    heightmaps = mix(heightmaps, type.ordinal());
                    for (long value : chunk.getOrCreateHeightmapUnprimed(type).getRawData()) {
                        heightmaps = mix(heightmaps, value);
                    }
                }
            }
            long postProcessing = FNV_OFFSET;
            for (ShortList section : chunk.getPostProcessing()) {
                postProcessing = mix(postProcessing, section == null ? -1 : section.size());
                if (section != null) {
                    for (int i = 0; i < section.size(); i++) {
                        postProcessing = mix(postProcessing, section.getShort(i));
                    }
                }
            }
            buffer.clear();
            buffer.putLong(chunk.getPos().pack()).putLong(blocks).putLong(biomes)
                    .putLong(heightmaps).putLong(postProcessing);
            digest.update(buffer.array());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static long mix(long hash, long value) {
        return (hash ^ value) * FNV_PRIME;
    }

    private static Method privateMethod(String name, Class<?>... arguments) {
        try {
            Method method = NoiseBasedChunkGenerator.class.getDeclaredMethod(name, arguments);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void invoke(Method method, Object instance, Object... arguments) {
        try {
            method.invoke(instance, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException failure) {
            throw new RuntimeException(failure);
        }
    }

    @FunctionalInterface
    private interface ChunkTask {
        void run(ProtoChunk chunk);
    }

    private record SeedContext(long seed, RandomState randomState) {}

    private record Result(long wallNs, long setupNs, long biomesNs, long noiseNs, long surfaceNs, String checksum) {
        double seconds() { return wallNs / 1e9; }
    }

    private record Config(int chunks, int threads, int iterations, int warmupChunks, int warmupIterations, boolean optimized, boolean compare,
                          List<Long> seeds, Path output, Path jfr) {
        static Config parse(String[] args) {
            Map<String, String> options = new HashMap<>();
            for (String argument : args) {
                if (!argument.startsWith("--") || !argument.contains("=")) {
                    throw new IllegalArgumentException("Expected --name=value, got " + argument);
                }
                int separator = argument.indexOf('=');
                options.put(argument.substring(2, separator), argument.substring(separator + 1));
            }
            int chunks = positive(options, "chunks", 64);
            int threads = positive(options, "threads", 1);
            int iterations = positive(options, "iterations", 3);
            int warmupChunks = positive(options, "warmupChunks", 16);
            int warmupIterations = positive(options, "warmupIterations", 2);
            List<Long> seeds = new ArrayList<>();
            for (String seed : option(options, "seeds", "0,42,-7046029254386353131").split(",")) {
                seeds.add(Long.parseLong(seed.trim()));
            }
            String output = option(options, "output", "");
            String jfr = option(options, "jfr", "");
            boolean optimized = Boolean.parseBoolean(option(options, "optimized", "true"));
            boolean compare = Boolean.parseBoolean(option(options, "compare", "false"));
            return new Config(chunks, threads, iterations, warmupChunks, warmupIterations, optimized, compare, seeds,
                    output.isBlank() ? null : Path.of(output), jfr.isBlank() ? null : Path.of(jfr));
        }

        private static int positive(Map<String, String> options, String name, int fallback) {
            int value = Integer.parseInt(option(options, name, Integer.toString(fallback)));
            if (value < 1) throw new IllegalArgumentException(name + " must be positive");
            return value;
        }

        private static String option(Map<String, String> options, String name, String fallback) {
            return options.getOrDefault(name, System.getProperty("pregen.benchmark." + name, fallback));
        }
    }
}
