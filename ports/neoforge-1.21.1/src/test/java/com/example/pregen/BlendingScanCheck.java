package com.example.pregen;

import com.example.pregen.terrain.FreshChunkBlending;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.levelgen.blending.BlendingData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Exercises vanilla's old-terrain metadata scan in isolated temporary region folders.
 * These files are never placed in a Minecraft save. Timings describe this machine's
 * scan overhead only; they are not an end-to-end terrain-generation benchmark.
 */
public final class BlendingScanCheck {
    private static final int BLENDING_RADIUS = 7;
    private static final int WARM_CALLS = 4_096;

    private BlendingScanCheck() {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME).listElements()
                .forEach(biome -> biomes.register(biome.key(), biome.value(), net.minecraft.core.RegistrationInfo.BUILT_IN));
        checkOwnership(biomes.freeze());
        run();
    }

    /** Cheap safety checks always run; opt in to disk measurements separately. */
    public static void run(Registry<Biome> biomeRegistry) throws IOException {
        checkOwnership(biomeRegistry);
        if (Boolean.getBoolean("pregen.benchmark.blendingScan")) run();
    }

    /** Optional cold/warm disk-scan measurements in disposable folders. */
    public static void run() throws IOException {
        Path outputRoot = Path.of("build", "blending-scan-check").toAbsolutePath().normalize();
        Files.createDirectories(outputRoot);
        Path scratch = Files.createTempDirectory(outputRoot, "regions-");
        try {
            checkEmptyRegion(scratch.resolve("empty-center"), new ChunkPos(16, 16), 1_024);
            checkEmptyRegion(scratch.resolve("empty-corner"), new ChunkPos(0, 0), 4_096);
            checkExistingData(scratch.resolve("existing-data"));
            System.out.println("BLENDING_SCAN_CHECK PASS: cold empty scans, warm cache and existing old-terrain detection verified.");
        } finally {
            deleteScratch(scratch, outputRoot);
        }
    }

    private static void checkOwnership(Registry<Biome> biomeRegistry) {
        ChunkPos center = new ChunkPos(-1, -1);
        Map<Long, ChunkAccess> owned = new HashMap<>();
        List<ChunkAccess> cache = new ArrayList<>();
        for (int z = center.z - 1; z <= center.z + 1; z++) {
            for (int x = center.x - 1; x <= center.x + 1; x++) {
                ProtoChunk chunk = newChunk(new ChunkPos(x, z), biomeRegistry);
                owned.put(chunk.getPos().toLong(), chunk);
                cache.add(chunk);
            }
        }
        FreshChunkBlending.ChunkLookup lookup = (x, z) -> owned.get(ChunkPos.asLong(x, z));
        require(FreshChunkBlending.canSkip(cache, center, BLENDING_RADIUS, lookup),
                "Fresh owned chunks and missing fresh fallback positions must allow the shortcut.");
        require(!FreshChunkBlending.canSkip(List.of(), center, BLENDING_RADIUS, lookup),
                "An empty region cache must not allow the shortcut.");
        require(!FreshChunkBlending.canSkip(cache, center, -1, lookup),
                "An invalid radius must not allow the shortcut.");

        List<ChunkAccess> foreignCenter = new ArrayList<>(cache);
        foreignCenter.set(cache.size() / 2, newChunk(center, biomeRegistry));
        require(!FreshChunkBlending.canSkip(foreignCenter, center, BLENDING_RADIUS, lookup),
                "A vanilla chunk with the same center coordinates is not an owned grid chunk.");
        List<ChunkAccess> foreignNeighbor = new ArrayList<>(cache);
        foreignNeighbor.set(0, newChunk(cache.get(0).getPos(), biomeRegistry));
        require(!FreshChunkBlending.canSkip(foreignNeighbor, center, BLENDING_RADIUS, lookup),
                "Every cached chunk must belong to the grid by object identity.");

        CompoundTag blendingTag = new CompoundTag();
        blendingTag.putInt("min_section", 0);
        blendingTag.putInt("max_section", 16);
        BlendingData oldData = BlendingData.CODEC.parse(NbtOps.INSTANCE, blendingTag).result().orElseThrow();
        ChunkAccess centerChunk = cache.get(cache.size() / 2);
        centerChunk.setBlendingData(oldData);
        require(!FreshChunkBlending.canSkip(cache, center, BLENDING_RADIUS, lookup),
                "Owned cached chunks carrying old-terrain data must retain vanilla blending.");
        centerChunk.setBlendingData(null);

        ProtoChunk distant = newChunk(new ChunkPos(center.x + BLENDING_RADIUS, center.z), biomeRegistry);
        distant.setBlendingData(oldData);
        owned.put(distant.getPos().toLong(), distant);
        require(!FreshChunkBlending.canSkip(cache, center, BLENDING_RADIUS, lookup),
                "Old-terrain data outside the local cache but inside the blending radius must be detected.");
        owned.remove(distant.getPos().toLong());
        require(FreshChunkBlending.canSkip(cache, center, BLENDING_RADIUS, lookup),
                "A missing fallback remains fresh after old-terrain data is removed.");
        System.out.println("FRESH_CHUNK_BLENDING_CHECK identity=true cached_old_data=true distant_old_data=true fresh_fallback=true");
    }

    private static ProtoChunk newChunk(ChunkPos position, Registry<Biome> biomeRegistry) {
        return new ProtoChunk(position, UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384), biomeRegistry, null);
    }

    private static void checkEmptyRegion(Path folder, ChunkPos center, int expectedScans) throws IOException {
        Files.createDirectories(folder);
        long filesBefore = countFiles(folder);
        try (CountingIOWorker worker = new CountingIOWorker(folder)) {
            long coldStart = System.nanoTime();
            boolean coldResult = worker.isOldChunkAround(center, BLENDING_RADIUS);
            long coldNanos = System.nanoTime() - coldStart;
            require(!coldResult, "A new empty region must contain no old terrain.");
            require(worker.scans.get() == expectedScans,
                    "Cold lookup should scan " + expectedScans + " chunks, got " + worker.scans.get());
            require(worker.failures.get() == 0, "Empty-region metadata scan failed.");

            int scansBeforeWarm = worker.scans.get();
            long warmStart = System.nanoTime();
            for (int index = 0; index < WARM_CALLS; index++) {
                require(!worker.isOldChunkAround(center, BLENDING_RADIUS), "Warm lookup changed its result.");
            }
            long warmNanos = System.nanoTime() - warmStart;
            require(worker.scans.get() == scansBeforeWarm, "Warm region cache must not rescan disk metadata.");
            System.out.printf(Locale.ROOT,
                    "BLENDING_SCAN_CHECK %s cold_ms=%.3f scans=%d warm_calls=%d warm_total_ms=%.3f warm_us_per_call=%.3f files_before=%d files_after=%d%n",
                    folder.getFileName(), coldNanos / 1_000_000.0, worker.scans.get(), WARM_CALLS,
                    warmNanos / 1_000_000.0, warmNanos / 1_000.0 / WARM_CALLS,
                    filesBefore, countFiles(folder));
        }
    }

    private static void checkExistingData(Path folder) throws IOException {
        Files.createDirectories(folder);
        ChunkPos taggedOld = new ChunkPos(16, 16);
        ChunkPos modern = new ChunkPos(17, 17);
        ChunkPos legacy = new ChunkPos(18, 18);
        try (CountingIOWorker writer = new CountingIOWorker(folder)) {
            CompoundTag blendingTag = new CompoundTag();
            blendingTag.putInt("DataVersion", 3_441);
            blendingTag.put("blending_data", new CompoundTag());
            writer.store(taggedOld, blendingTag).join();

            CompoundTag modernTag = new CompoundTag();
            modernTag.putInt("DataVersion", 3_441);
            writer.store(modern, modernTag).join();

            CompoundTag legacyTag = new CompoundTag();
            legacyTag.putInt("DataVersion", 3_000);
            writer.store(legacy, legacyTag).join();
            writer.synchronize(true).join();
        }

        // A fresh worker forces a disk read instead of reading the writer's pending map.
        try (CountingIOWorker reader = new CountingIOWorker(folder)) {
            long coldStart = System.nanoTime();
            require(reader.isOldChunkAround(taggedOld, 0), "A saved chunk with blending_data must be detected.");
            long coldNanos = System.nanoTime() - coldStart;
            require(!reader.isOldChunkAround(modern, 0), "Modern data without blending_data must not be old.");
            require(reader.isOldChunkAround(legacy, 0), "A saved legacy DataVersion must be detected.");
            require(reader.scans.get() == 1_024, "All three checks should share one cached region scan.");
            require(reader.failures.get() == 0, "Saved-region metadata scan failed.");
            System.out.printf(Locale.ROOT,
                    "BLENDING_SCAN_CHECK existing-data cold_ms=%.3f scans=%d tagged_old=true modern=false legacy=true%n",
                    coldNanos / 1_000_000.0, reader.scans.get());
        }
    }

    private static long countFiles(Path folder) throws IOException {
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private static void deleteScratch(Path scratch, Path outputRoot) throws IOException {
        Path absoluteScratch = scratch.toAbsolutePath().normalize();
        require(absoluteScratch.startsWith(outputRoot) && !absoluteScratch.equals(outputRoot),
                "Temporary cleanup must remain inside its test output folder.");
        try (Stream<Path> paths = Files.walk(absoluteScratch)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class CountingIOWorker extends IOWorker {
        private final AtomicInteger scans = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();

        private CountingIOWorker(Path folder) {
            super(ChunkSaveCheck.storageInfo("pregen-blending-scan-check"), folder, false);
        }

        @Override
        public CompletableFuture<Void> scanChunk(ChunkPos position, StreamTagVisitor visitor) {
            scans.incrementAndGet();
            return super.scanChunk(position, visitor).whenComplete((ignored, failure) -> {
                if (failure != null) failures.incrementAndGet();
            });
        }
    }
}
