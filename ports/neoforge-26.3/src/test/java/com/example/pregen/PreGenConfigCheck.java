package com.example.pregen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Config file creation, legacy migration, missing-key appends and value validation. */
public final class PreGenConfigCheck {
    private PreGenConfigCheck() {}

    public static void run() throws IOException {
        Path outputRoot = Path.of("build", "config-check").toAbsolutePath().normalize();
        Files.createDirectories(outputRoot);
        Path scratch = Files.createTempDirectory(outputRoot, "config-");
        try {
            checkFreshFile(scratch.resolve("fresh"));
            checkLegacyMigration(scratch.resolve("legacy"));
            checkCustomAndInvalidValues(scratch.resolve("values"));
            checkBatchFitsHeap();
            checkSetCommand(scratch.resolve("set"));
            System.out.println("PREGEN_CONFIG_CHECK PASS defaults=batch1000,threads_max created=true legacy_migrated=true"
                    + " missing_keys_appended_once=true invalid_values_fall_back=true set_keeps_comments=true");
        } finally {
            delete(scratch, outputRoot);
        }
    }

    private static void checkFreshFile(Path directory) throws IOException {
        List<String> warnings = new ArrayList<>();
        PreGenConfig config = PreGenConfig.load(directory, 4, warnings::add);
        require(config.batchSizeBlocks == 1000, "Default batch must be 1000 blocks");
        require(config.threads == 4 && config.threadsFromCpuCount, "Default threads must be every visible core");
        require(config.terrainPipeline == PreGenConfig.TerrainPipeline.AUTO, "Default pipeline must be auto");
        require(warnings.isEmpty(), "A fresh file must not warn: " + warnings);
        Path file = directory.resolve(PreGenConfig.FILE_NAME);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        require(config.parallelSteps && config.releaseMemoryEachBatch, "Parallel steps and batch release default to on");
        require(config.skipExistingChunks, "Existing chunks are kept by default");
        require(config.lodExport, "Saved chunks go to LoD mods by default");
        require(config.resumeAfterRestart, "Runs cut short by a restart continue by default");
        for (String line : new String[]{"batch_size=1000", "threads=max", "resume_after_restart=true", "parallel_steps=true", "parallel_features=auto",
                "release_memory_each_batch=true", "skip_existing_chunks=true", "lod_export=true", "terrain_pipeline=auto"}) {
            require(text.contains(line), "New config must contain " + line);
        }
        long size = Files.size(file);
        PreGenConfig.load(directory, 4, warnings::add);
        require(Files.size(file) == size, "Reloading a complete file must not append anything");
        require(PreGenConfig.load(directory, 512, warnings::add).threads == PreGenConfig.MAX_THREADS,
                "max must be capped at " + PreGenConfig.MAX_THREADS);
    }

    private static void checkLegacyMigration(Path directory) throws IOException {
        Files.createDirectories(directory);
        for (String legacy : PreGenConfig.LEGACY_FILE_NAMES) {
            Path old = directory.resolve(legacy);
            Files.writeString(old, "terrain_pipeline=vanilla\n");
            PreGenConfig moved = PreGenConfig.load(directory, 4, message -> {});
            require(Files.notExists(old) && moved.terrainPipeline == PreGenConfig.TerrainPipeline.VANILLA,
                    "The settings in " + legacy + " must move to " + PreGenConfig.FILE_NAME);
            Files.delete(directory.resolve(PreGenConfig.FILE_NAME));
        }
        Files.writeString(directory.resolve(PreGenConfig.LEGACY_FILE_NAMES.get(1)), "terrain_pipeline=vanilla\n");
        PreGenConfig config = PreGenConfig.load(directory, 4, message -> {});
        require(Files.notExists(directory.resolve(PreGenConfig.LEGACY_FILE_NAMES.get(1))), "The legacy file must be moved");
        require(config.terrainPipeline == PreGenConfig.TerrainPipeline.VANILLA, "Legacy settings must be kept");
        String text = Files.readString(directory.resolve(PreGenConfig.FILE_NAME), StandardCharsets.UTF_8);
        require(text.contains("batch_size=1000") && text.contains("threads=max"), "New keys must be appended");
        require(text.indexOf("terrain_pipeline=") == text.lastIndexOf("terrain_pipeline="),
                "An existing key must not be appended again");
    }

    private static void checkCustomAndInvalidValues(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(PreGenConfig.FILE_NAME);
        Files.writeString(file, "batch_size=2000\nthreads=6\nparallel_steps=FALSE\nparallel_features=ALWAYS\n"
                + "release_memory_each_batch=false\nskip_existing_chunks=false\nlod_export=false\nterrain_pipeline=FUSED\nresume_after_restart=false\n");
        PreGenConfig custom = PreGenConfig.load(directory, 4, message -> {});
        require(!custom.resumeAfterRestart, "resume_after_restart=false must be honored");
        require(!custom.parallelSteps, "parallel_steps=false must be honored, in any case");
        require(custom.alwaysParallelFeatures, "parallel_features=always must be honored, in any case");
        require(!custom.releaseMemoryEachBatch, "release_memory_each_batch=false must be honored");
        require(!custom.skipExistingChunks, "skip_existing_chunks=false must be honored");
        require(!custom.lodExport, "lod_export=false must be honored");
        require(custom.batchSizeBlocks == 2000 && custom.threads == 6 && !custom.threadsFromCpuCount,
                "Custom batch and threads must be used");
        require(custom.terrainPipeline == PreGenConfig.TerrainPipeline.FUSED, "Pipeline values are case-insensitive");

        Files.writeString(file, "batch_size=100\nthreads=0\nparallel_steps=maybe\n"
                + "release_memory_each_batch=1\nterrain_pipeline=fast\n");
        List<String> warnings = new ArrayList<>();
        PreGenConfig invalid = PreGenConfig.load(directory, 4, warnings::add);
        require(invalid.batchSizeBlocks == 1000 && invalid.threads == 4 && invalid.threadsFromCpuCount
                && invalid.parallelSteps && invalid.releaseMemoryEachBatch
                && invalid.terrainPipeline == PreGenConfig.TerrainPipeline.AUTO, "Invalid values must fall back to defaults");
        require(warnings.size() == 5, "Each invalid value must warn once, got " + warnings);

        Files.writeString(file, "batch_size=x\nthreads=MAX\nparallel_steps=true\n"
                + "release_memory_each_batch=true\nterrain_pipeline=auto\n");
        warnings.clear();
        PreGenConfig mixed = PreGenConfig.load(directory, 8, warnings::add);
        require(mixed.batchSizeBlocks == 1000 && warnings.size() == 1, "A non-numeric batch must warn and fall back");
        require(mixed.threads == 8 && mixed.threadsFromCpuCount, "MAX is accepted in any case");
    }

    private static void delete(Path scratch, Path outputRoot) throws IOException {
        Path absolute = scratch.toAbsolutePath().normalize();
        require(absolute.startsWith(outputRoot) && !absolute.equals(outputRoot),
                "Temporary cleanup must remain inside its test output folder.");
        try (Stream<Path> paths = Files.walk(absolute)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    /** A heap too small for the configured batch gets a smaller one, never below the fitted minimum. */
    private static void checkBatchFitsHeap() {
        long gb = 1L << 30;
        require(PreGenConfig.fitBatchToHeap(1000, 8 * gb) == 1000, "A large heap keeps the configured batch");
        require(PreGenConfig.fitBatchToHeap(1000, PreGenConfig.heapForBatch(1000)) == 1000, "An exact fit keeps it");
        int small = PreGenConfig.fitBatchToHeap(1000, (long) (1.5 * gb));
        require(small < 1000 && PreGenConfig.heapForBatch(small) <= 1.5 * gb, "A 1.5 GB heap must get a batch that fits: " + small);
        require(PreGenConfig.fitBatchToHeap(1000, 0) == PreGenConfig.MIN_FITTED_BATCH_SIZE, "No heap still gets the minimum");
        require(PreGenConfig.heapForBatch(500) < PreGenConfig.heapForBatch(1000), "Smaller batches need less heap");
        require(PreGenConfig.overlapFits(1000, 8 * gb), "An 8 GB heap holds a batch beside the previous one's tail");
        require(!PreGenConfig.overlapFits(1000, PreGenConfig.heapForBatch(1000)), "Room for one batch runs batches one at a time");
        require(!PreGenConfig.overlapFits(1000, (long) (3.6 * gb)), "A 4 GB server (about 3.6 GB free) runs batches one at a time");
        System.out.println("PREGEN_BATCH_HEAP_CHECK PASS batch_for_1.5GB=" + small + " heap_for_1000="
                + PreGenConfig.heapForBatch(1000) / (1 << 20) + "MB");
    }

    /** /pregen set: one key's value changes in place, comments and other keys stay. */
    private static void checkSetCommand(Path directory) throws IOException {
        List<String> warnings = new ArrayList<>();
        PreGenConfig.set(directory, "batch_size", "1500");
        PreGenConfig.set(directory, "threads", "6");
        PreGenConfig.set(directory, "skip_existing_chunks", "false");
        PreGenConfig config = PreGenConfig.load(directory, 12, warnings::add);
        require(config.batchSizeBlocks == 1500 && config.threads == 6 && !config.threadsFromCpuCount
                && !config.skipExistingChunks, "Set values must be read back");
        require(warnings.isEmpty(), "Set values must be valid: " + warnings);
        String text = Files.readString(directory.resolve(PreGenConfig.FILE_NAME), StandardCharsets.UTF_8);
        require(text.contains("# batch_size:") && text.contains("terrain_pipeline=auto"), "Comments and other keys must stay");
        require(text.indexOf("batch_size=") == text.lastIndexOf("batch_size="), "A key must not be written twice");
        PreGenConfig.set(directory, "threads", "max");
        require(PreGenConfig.load(directory, 12, warnings::add).threadsFromCpuCount, "threads=max must restore every core");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
