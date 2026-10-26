package com.example.pregen;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * config/ultrafastpregen.conf (Java properties syntax), re-read on every /pregen start. Batch size
 * and worker threads are set only here, not by command. Keys missing from an existing file are
 * appended with their defaults so every setting stays visible and editable.
 */
final class PreGenConfig {
    enum TerrainPipeline { AUTO, FUSED, VANILLA }

    static final String FILE_NAME = "ultrafastpregen.conf";
    /** Earlier names of this file, newest first; the first one found is taken over. */
    static final List<String> LEGACY_FILE_NAMES = List.of("inmemory-pregen.conf", "inmemory-pregen.properties");
    static final int DEFAULT_BATCH_SIZE = 1000;
    static final int MIN_BATCH_SIZE = 512;
    /** Smallest batch chosen when the heap is too small for the configured one (see fitBatchToHeap). */
    static final int MIN_FITTED_BATCH_SIZE = 256;
    /**
     * Heap one grid chunk takes at most while a batch runs (measured about 160 KB per chunk of a
     * 1000-block tile with its padding on vanilla), with headroom for mods that add chunk data.
     */
    static final long BYTES_PER_GRID_CHUNK = 300L * 1024;

    /** Heap a batch of this size needs: its core chunks, halo and structure padding. */
    static long heapForBatch(int batchSizeBlocks) {
        long side = (batchSizeBlocks + 15) / 16 + 2L * InMemoryChunkGrid.PADDING;
        return side * side * BYTES_PER_GRID_CHUNK;
    }

    /**
     * Whether a batch can generate while the previous one is lit and saved (see
     * DynamicBatchPreGen.BatchTail): room for two batches, so the collector is not squeezed
     * (measured: a 4 GB heap holds both at batch 1000 but runs about 3% slower).
     */
    static boolean overlapFits(int batchSizeBlocks, long freeHeapBytes) {
        return 2 * heapForBatch(batchSizeBlocks) <= freeHeapBytes;
    }

    /**
     * The configured batch size, or a smaller one when the free heap could not hold it (a
     * server started with a small -Xmx, or singleplayer, where the client shares the heap):
     * shrunk in steps of a quarter, down to MIN_FITTED_BATCH_SIZE. Smaller batches are a little
     * slower but never run out of memory.
     */
    static int fitBatchToHeap(int configured, long freeHeapBytes) {
        int batch = configured;
        while (batch > MIN_FITTED_BATCH_SIZE && heapForBatch(batch) > freeHeapBytes) {
            batch = Math.max(MIN_FITTED_BATCH_SIZE, batch * 3 / 4);
        }
        return batch;
    }

    /** Heap free for a batch: the maximum minus what was still in use after the last collections. */
    static long freeHeapBytes() {
        long inUse = 0;
        boolean collected = false;
        for (java.lang.management.MemoryPoolMXBean pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != java.lang.management.MemoryType.HEAP) continue;
            java.lang.management.MemoryUsage afterGc = pool.getCollectionUsage();
            if (afterGc != null) {
                inUse += afterGc.getUsed();
                collected = true;
            }
        }
        Runtime runtime = Runtime.getRuntime();
        if (!collected) inUse = runtime.totalMemory() - runtime.freeMemory();
        return Math.max(0, runtime.maxMemory() - inUse);
    }
    static final int MAX_BATCH_SIZE = 10_000;
    static final int MAX_THREADS = 128;

    private static final String HEADER = """
            # Ultra Fast Pregen settings (key=value). Re-read on every /pregen start.
            """;
    // Insertion order is the order keys are written in a new file.
    private static final Map<String, String> SECTIONS = new LinkedHashMap<>();

    static {
        SECTIONS.put("batch_size", """

                # batch_size: edge of one tile in blocks; one tile at a time is generated in RAM.
                #   Memory grows with the square of this value. Measured peak of live heap:
                #   750 about 1.0 GB, 1000 about 1.4 GB, 1500 about 2.4 GB (the heap also needs room
                #   for garbage: give the server at least twice that). Larger is only slightly faster.
                #   Allowed: %d..%d. Default: %d.
                batch_size=%d
                """.formatted(MIN_BATCH_SIZE, MAX_BATCH_SIZE, DEFAULT_BATCH_SIZE, DEFAULT_BATCH_SIZE));
        SECTIONS.put("threads", """

                # threads: generation worker threads. "max" uses every CPU core the JVM can see
                #   (on hosting panels, the plan's CPU limit). Or a number 1..%d. Default: max.
                threads=max
                """.formatted(MAX_THREADS));
        SECTIONS.put("resume_after_restart", """

                # resume_after_restart: when a run was cut short by a server stop or crash (not by /pregen stop),
                #   continue it once the server has started again. After 3 restarts in a row without a
                #   saved batch it stops trying (/pregen resume still works). Default: true.
                resume_after_restart=true
                """);
        SECTIONS.put("parallel_steps", """

                # parallel_steps: run structure starts/references, caves (carvers) and features on all
                #   threads. Minecraft itself runs these steps one chunk at a time; set false if a
                #   world-generation mod crashes in Stage 1, 3 or 4 (slower but always safe). Default: true.
                parallel_steps=true
                """);
        SECTIONS.put("parallel_features", """

                # parallel_features: whether features (trees, ores, structure pieces) run on all threads.
                #   auto   - yes, except when another mod keeps state in feature placement (BCLib and
                #            the mods built on it): then one chunk at a time, so their features land
                #            where Minecraft puts them generating chunk by chunk (default)
                #   always - on all threads anyway: much faster in such packs; those mods' features
                #            land in other places, as random and valid as the usual ones
                parallel_features=auto
                """);
        SECTIONS.put("release_memory_each_batch", """

                # release_memory_each_batch: run a garbage collection after every batch so the finished
                #   tile's memory is returned. The server pauses briefly during it (longer with a bigger
                #   heap); set false if online players notice the stutter. Default: true.
                release_memory_each_batch=true
                """);
        SECTIONS.put("skip_existing_chunks", """

                # skip_existing_chunks: keep every chunk that already exists, on disk or loaded by the
                #   server (player builds, chunks from other generators, older runs), and generate only
                #   missing ones. A batch whose chunks all exist is skipped without generating it.
                #   false regenerates and overwrites every chunk in the area. Default: true.
                skip_existing_chunks=true
                """);
        SECTIONS.put("lod_export", """

                # lod_export: send every chunk a run saves to the LoD mods that are loaded (Voxy in
                #   singleplayer, Distant Horizons), so far terrain shows up without visiting it. They
                #   build their LoDs while the run goes on, which makes it slower; false only saves.
                #   Default: true.
                lod_export=true
                """);
        SECTIONS.put("terrain_pipeline", """

                # terrain_pipeline: how Stage 2 (biomes, noise, surface) runs.
                #   auto    - fast fused path for the vanilla noise generator; Minecraft's own
                #             per-status path when another mod changes the generator (default)
                #   fused   - always the fast path (only if you know your mods are compatible)
                #   vanilla - always Minecraft's own path (slower, most compatible)
                terrain_pipeline=auto
                """);
    }

    final int batchSizeBlocks;
    final int threads;
    final boolean threadsFromCpuCount;
    final boolean resumeAfterRestart;
    final boolean parallelSteps;
    final boolean alwaysParallelFeatures;
    final boolean releaseMemoryEachBatch;
    final boolean skipExistingChunks;
    final boolean lodExport;
    final TerrainPipeline terrainPipeline;

    private PreGenConfig(int batchSizeBlocks, int threads, boolean threadsFromCpuCount, boolean resumeAfterRestart,
                         boolean parallelSteps,
                         boolean alwaysParallelFeatures,
                         boolean releaseMemoryEachBatch, boolean skipExistingChunks, boolean lodExport,
                         TerrainPipeline terrainPipeline) {
        this.batchSizeBlocks = batchSizeBlocks;
        this.threads = threads;
        this.threadsFromCpuCount = threadsFromCpuCount;
        this.resumeAfterRestart = resumeAfterRestart;
        this.parallelSteps = parallelSteps;
        this.alwaysParallelFeatures = alwaysParallelFeatures;
        this.releaseMemoryEachBatch = releaseMemoryEachBatch;
        this.skipExistingChunks = skipExistingChunks;
        this.lodExport = lodExport;
        this.terrainPipeline = terrainPipeline;
    }

    static PreGenConfig load() {
        Path directory = PreGenMod.configDirectory;
        return load(directory, Runtime.getRuntime().availableProcessors(),
                message -> PreGenMod.LOGGER.warn("[PreGen] {}", message));
    }

    static PreGenConfig load(Path directory, int availableProcessors, Consumer<String> warn) {
        Path file = directory.resolve(FILE_NAME);
        Properties properties = new Properties();
        try {
            Files.createDirectories(directory);
            for (String name : LEGACY_FILE_NAMES) {
                Path legacy = directory.resolve(name);
                if (Files.notExists(file) && Files.exists(legacy)) Files.move(legacy, file);
            }
            if (Files.notExists(file)) Files.writeString(file, HEADER, StandardCharsets.UTF_8);
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            StringBuilder missing = new StringBuilder();
            for (Map.Entry<String, String> section : SECTIONS.entrySet()) {
                if (!properties.containsKey(section.getKey())) missing.append(section.getValue());
            }
            if (!missing.isEmpty()) {
                Files.writeString(file, missing, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }
        } catch (IOException failure) {
            warn.accept("Could not read or update " + file + "; using defaults (" + failure + ")");
        }

        int batch = DEFAULT_BATCH_SIZE;
        String batchValue = properties.getProperty("batch_size", Integer.toString(DEFAULT_BATCH_SIZE)).trim();
        try {
            batch = Integer.parseInt(batchValue);
            if (batch < MIN_BATCH_SIZE || batch > MAX_BATCH_SIZE) {
                warn.accept("batch_size=" + batch + " in " + file + " is outside " + MIN_BATCH_SIZE + ".."
                        + MAX_BATCH_SIZE + "; using " + DEFAULT_BATCH_SIZE);
                batch = DEFAULT_BATCH_SIZE;
            }
        } catch (NumberFormatException invalid) {
            warn.accept("batch_size='" + batchValue + "' in " + file + " is not a number; using " + DEFAULT_BATCH_SIZE);
        }

        int cores = Math.max(1, Math.min(MAX_THREADS, availableProcessors));
        int threads = cores;
        boolean fromCpuCount = true;
        String threadValue = properties.getProperty("threads", "max").trim();
        if (!threadValue.equalsIgnoreCase("max")) {
            try {
                int parsed = Integer.parseInt(threadValue);
                if (parsed >= 1 && parsed <= MAX_THREADS) {
                    threads = parsed;
                    fromCpuCount = false;
                } else {
                    warn.accept("threads=" + parsed + " in " + file + " is outside 1.." + MAX_THREADS + "; using max (" + cores + ")");
                }
            } catch (NumberFormatException invalid) {
                warn.accept("threads='" + threadValue + "' in " + file + " is not max or a number; using max (" + cores + ")");
            }
        }

        boolean resumeAfterRestart = readBoolean(properties, "resume_after_restart", true, file, warn);
        boolean parallelSteps = readBoolean(properties, "parallel_steps", true, file, warn);
        String featuresValue = properties.getProperty("parallel_features", "auto").trim().toLowerCase(Locale.ROOT);
        boolean alwaysParallelFeatures = featuresValue.equals("always");
        if (!alwaysParallelFeatures && !featuresValue.equals("auto")) {
            warn.accept("parallel_features='" + featuresValue + "' in " + file + " is not auto or always; using auto");
        }
        boolean releaseMemoryEachBatch = readBoolean(properties, "release_memory_each_batch", true, file, warn);
        boolean skipExistingChunks = readBoolean(properties, "skip_existing_chunks", true, file, warn);
        boolean lodExport = readBoolean(properties, "lod_export", true, file, warn);

        String pipelineValue = properties.getProperty("terrain_pipeline", "auto").trim().toUpperCase(Locale.ROOT);
        TerrainPipeline pipeline;
        try {
            pipeline = TerrainPipeline.valueOf(pipelineValue);
        } catch (IllegalArgumentException unknown) {
            warn.accept("Unknown terrain_pipeline '" + pipelineValue + "' in " + file + "; using auto");
            pipeline = TerrainPipeline.AUTO;
        }
        return new PreGenConfig(batch, threads, fromCpuCount, resumeAfterRestart, parallelSteps, alwaysParallelFeatures, releaseMemoryEachBatch,
                skipExistingChunks, lodExport, pipeline);
    }

    /**
     * Writes one setting into the file (from /pregen set), keeping its comments and every other
     * line: lines of the key get the new value, or it is appended. Callers validate the value.
     */
    static void set(Path directory, String key, String value) throws IOException {
        // Creates or migrates the file, with every key, first.
        load(directory, 1, message -> {});
        Path file = directory.resolve(FILE_NAME);
        List<String> lines = new java.util.ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            int separator = line.length();
            for (char mark : new char[]{'=', ':', ' ', '\t'}) {
                int at = line.indexOf(mark);
                if (at >= 0 && at < separator) separator = at;
            }
            if (line.substring(0, separator).equals(key)) {
                lines.set(i, key + "=" + value);
                found = true;
            }
        }
        if (!found) lines.add(key + "=" + value);
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static boolean readBoolean(Properties properties, String key, boolean fallback, Path file,
                                       Consumer<String> warn) {
        String value = properties.getProperty(key, Boolean.toString(fallback)).trim();
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        warn.accept(key + "='" + value + "' in " + file + " is not true or false; using " + fallback);
        return fallback;
    }
}
