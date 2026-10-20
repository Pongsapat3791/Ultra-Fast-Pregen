package com.example.pregen;

import com.example.pregen.mixin.ChunkStorageAccessor;
import io.github.pongsapat3791.ultrafastpregen.api.PregenApi;
import io.github.pongsapat3791.ultrafastpregen.api.PregenListener;
import io.github.pongsapat3791.ultrafastpregen.api.PregenRequest;
import io.github.pongsapat3791.ultrafastpregen.api.PregenStatus;
import io.github.pongsapat3791.ultrafastpregen.api.StartResult;
import io.github.pongsapat3791.ultrafastpregen.api.UltraFastPregenApi;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in regression against a real server in a disposable, isolated test world. The loader's
 * check entry (fabric.FabricChecks, or ForgeChecks in the Forge build) calls the two hooks.
 */
public final class PartialExistingBatchCheck {
    private static final AtomicInteger RESULT = new AtomicInteger(1);
    private static final String MARKER = "fastpregen-preserve-existing";

    private static boolean requested() {
        return Boolean.getBoolean("pregen.partialBatchCheck");
    }

    /** The server has started (on its thread). */
    public static void onServerStarted(MinecraftServer server) {
        if (!requested()) return;
        // A JVM that ends without the exit below (the server thread died, e.g. out of memory)
        // must not report success.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (RESULT.get() != 0) Runtime.getRuntime().halt(1);
        }, "PartialBatchCheck-Result"));
        Thread coordinator = new Thread(() -> runWithTimeout(server), "PartialBatchCheck-Coordinator");
        coordinator.setDaemon(true);
        coordinator.start();
    }

    /** The server has stopped (on its thread). */
    public static void onServerStopped(MinecraftServer server) {
        if (!requested()) return;
        // JVM shutdown hooks join the server thread. Exiting inside this callback
        // would make the server wait for a hook that is waiting for the server.
        Thread serverThread = Thread.currentThread();
        Thread exit = new Thread(() -> {
            try {
                serverThread.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                RESULT.set(1);
            }
            System.exit(RESULT.get());
        }, "PartialBatchCheck-Exit");
        exit.start();
    }

    private static void runWithTimeout(MinecraftServer server) {
        List<DynamicBatchPreGen> tasks = new ArrayList<>();
        ExecutorService check = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "PartialBatchCheck-Worker");
            thread.setDaemon(true);
            return thread;
        });
        Future<?> completion = check.submit(() -> {
            try {
                run(server, tasks);
            } catch (Exception failure) {
                throw new IllegalStateException("Partial-existing batch regression failed", failure);
            }
        });
        try {
            // Benchmarks generate whole areas; the regression itself must finish within twenty minutes.
            boolean benchmark = Boolean.getBoolean("pregen.pipelineBenchmark") || System.getProperty("pregen.batchBenchmark") != null;
            completion.get(benchmark ? 3600 : 1200, TimeUnit.SECONDS);
            RESULT.set(0);
            System.out.println("PARTIAL_EXISTING_BATCH_CHECK PASS: fast_fill_parity=true feature_order_parity=true vanilla_pipeline_parity=true stored_biomes_match=true skip_existing=true batch_seams=true resume_edges=true existing_neighbors=true other_dimensions=true overwritten=4 loaded_terrain_replaced=true later_server_save_keeps_new_terrain=true no_prescan=true repeat_overwritten=4");
        } catch (Throwable failure) {
            failure.printStackTrace();
            completion.cancel(true);
        } finally {
            check.shutdownNow();
            synchronized (tasks) {
                for (DynamicBatchPreGen task : tasks) {
                    task.cancel();
                    try {
                        ((ExecutorService) field(task, "workerPool")).shutdownNow();
                    } catch (ReflectiveOperationException failure) {
                        failure.printStackTrace();
                        RESULT.set(1);
                    }
                }
            }
            server.halt(false);
        }
    }

    private static void run(MinecraftServer server, List<DynamicBatchPreGen> tasks) throws Exception {
        Path directory = Path.of("").toAbsolutePath().normalize();
        require(directory.endsWith(Path.of("build", "partial-batch-check")),
                "Run only from the isolated build/partial-batch-check directory: " + directory);
        ServerLevel level = server.overworld();
        if (Boolean.getBoolean("pregen.pipelineBenchmark")) {
            pipelineBenchmark(level, tasks);
            return;
        }
        if (System.getProperty("pregen.batchBenchmark") != null) {
            batchBenchmark(level, tasks);
            return;
        }
        IOWorker io = ((ChunkStorageAccessor) level.getChunkSource().chunkMap).pregen$getWorker();
        // -PonlyChecks=resume,existing: only those checks (faster iteration on one of them).
        String only = System.getProperty("pregen.onlyChecks");
        if (only != null) {
            if (only.contains("resume")) checkResumeEdges(level, io, tasks);
            if (only.contains("existing")) checkExistingNeighbors(server, level, io, tasks);
            if (only.contains("dimensions")) checkOtherDimensions(server, tasks);
            if (only.contains("anyGenerator")) checkAnyGenerator(level, io, tasks);
            if (only.contains("light")) checkLightParity(level, io, tasks);
            if (only.contains("spawn")) checkSpawnEdge(server, level, tasks);
            if (only.contains("held")) checkHeldDuringBatch(server, level, io, tasks);
            if (only.contains("safe")) checkSafeModeRetry(level, io, tasks);
            if (only.contains("api")) checkApi(level, tasks);
            if (only.contains("circle")) checkCircle(level, io, tasks);
            if (only.contains("restart")) checkAutoResume(server, level, tasks);
            if (only.contains("parity")) checkFastFillParity(server, level);
            return;
        }
        ChunkBatchPlan.Tile tile = new ChunkBatchPlan.Tile(10_000, 10_000, 2, 2);
        ChunkPos existing = new ChunkPos(tile.minChunkX(), tile.minChunkZ());
        // Far from spawn; no player or server chunk holder loads this marker-only entry.
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                require(io.loadAsync(new ChunkPos(existing.x + x, existing.z + z)).get(10, TimeUnit.SECONDS).isEmpty(),
                        "Test coordinates must be absent in a fresh disposable world");
            }
        }
        CompoundTag marker = new CompoundTag();
        marker.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        marker.putInt("xPos", existing.x);
        marker.putInt("zPos", existing.z);
        marker.putString("Status", "minecraft:full");
        marker.putString("PartialBatchCheckMarker", MARKER);
        io.store(existing, marker).get(10, TimeUnit.SECONDS);
        io.synchronize(true).get(10, TimeUnit.SECONDS);

        BlockPos oldBuild = new BlockPos((existing.x + 1) * 16 + 8, 310, (existing.z + 1) * 16 + 8);
        server.submit(() -> {
            level.getChunk(existing.x + 1, existing.z + 1);
            level.setBlock(oldBuild, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            level.setBlock(oldBuild.below(), Blocks.CHEST.defaultBlockState(), 3);
            return null;
        }).get(30, TimeUnit.SECONDS);

        ChunkPos loadedPos = new ChunkPos(existing.x + 1, existing.z + 1);
        int fullNeighborsBefore = server.submit(() -> fullNeighbors(level, loadedPos)).get(10, TimeUnit.SECONDS);

        DynamicBatchPreGen first = newTask(level, tile, tasks);
        invoke(first, "processBatch", new Class<?>[]{int.class, int.class, ChunkBatchPlan.Tile.class}, 1, 1, tile);
        invoke(first, "awaitPendingWrites", new Class<?>[0]);
        // Replacing a loaded chunk must never load or generate a neighbor on the server thread
        // (post-processing that did so crashed a live server through the 60 s watchdog).
        require(server.submit(() -> fullNeighbors(level, loadedPos)).get(10, TimeUnit.SECONDS) == fullNeighborsBefore,
                "Replacing a loaded chunk must not bring any neighbor to full status");
        require((int) field(first, "completedChunksCount") == 4, "Partial tile must complete all four target chunks");
        require(((AtomicInteger) field(first, "keptExistingChunks")).get() == 0, "Regeneration must not preserve old chunks");
        require(((AtomicInteger) field(first, "writtenChunks")).get() == 4, "All four target chunks must be overwritten");
        require(InMemoryChunkGrid.getCurrentGrid() == null, "Generation grid must be disposed after saving");
        require(!io.loadAsync(existing).get(10, TimeUnit.SECONDS).orElseThrow().contains("PartialBatchCheckMarker"),
                "The old stored chunk marker must be replaced");
        CompoundTag liveSave = server.submit(() -> {
            var live = level.getChunk(existing.x + 1, existing.z + 1);
            require(live.getBlockState(oldBuild).isAir(), "Loaded player block must be replaced with regenerated terrain");
            require(live.getBlockEntity(oldBuild.below()) == null, "Old loaded chest must be removed");
            return ChunkSerializer.write(level, live);
        }).get(10, TimeUnit.SECONDS);
        ChunkPos livePos = new ChunkPos(existing.x + 1, existing.z + 1);
        io.store(livePos, liveSave).get(10, TimeUnit.SECONDS);
        io.synchronize(true).get(10, TimeUnit.SECONDS);
        // The server may save the loaded chunk again meanwhile (when it unloads); only its
        // game-time fields differ then.
        require(withoutTimes(io.loadAsync(livePos).get(10, TimeUnit.SECONDS).orElseThrow()).equals(withoutTimes(liveSave)),
                "Later server saves must retain regenerated loaded terrain");
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                ChunkPos pos = new ChunkPos(existing.x + x, existing.z + z);
                CompoundTag generated = io.loadAsync(pos).get(10, TimeUnit.SECONDS).orElseThrow();
                require("minecraft:full".equals(generated.getString("Status")), "Saved chunk must be FULL: " + pos);
                require(generated.getInt("xPos") == pos.x && generated.getInt("zPos") == pos.z,
                        "Saved chunk coordinates must match: " + pos);
                require(!generated.getList("sections", 10).isEmpty(), "Saved chunk must contain readable terrain sections: " + pos);
            }
        }

        DynamicBatchPreGen second = newTask(level, tile, tasks);
        long started = System.nanoTime();
        invoke(second, "processBatch", new Class<?>[]{int.class, int.class, ChunkBatchPlan.Tile.class}, 1, 1, tile);
        invoke(second, "awaitPendingWrites", new Class<?>[0]);
        require(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 30, "Small repeat tile must complete promptly");
        require((int) field(second, "completedChunksCount") == 4, "Skipped tile must count all target chunks");
        require(((AtomicInteger) field(second, "keptExistingChunks")).get() == 0, "Repeat regeneration must preserve no old chunks");
        require(((AtomicInteger) field(second, "writtenChunks")).get() == 4, "Repeat regeneration must overwrite all four chunks");
        require((int) field(second, "currentPhase") == 7, "All-existing tile must start generation without a disk prescan");
        require(InMemoryChunkGrid.getCurrentGrid() == null, "Skipped tile must leave no allocated grid");

        checkSpawnEdge(server, level, tasks);
        checkSaveFileResume(server, level, io, tasks);
        checkApi(level, tasks);
        checkCircle(level, io, tasks);
        checkAutoResume(server, level, tasks);
        checkFastFillParity(server, level);
        checkStoredBiomesMatchSampling(level);
        checkSkipExisting(server, level, io, tasks);
        checkHeldDuringBatch(server, level, io, tasks);
        checkBatchSeams(level, io, tasks);
        checkResumeEdges(level, io, tasks);
        checkSafeModeRetry(level, io, tasks);
        checkExistingNeighbors(server, level, io, tasks);
        checkOtherDimensions(server, tasks);
        checkLightParity(level, io, tasks);
    }

    /**
     * Light of generated chunks against Minecraft's light engine lighting the same blocks, as the
     * server does for a chunk loaded without light: an 8x8 tile is generated and saved, and every
     * block of its inner 4x4 chunks (whose light depends only on blocks of the tile) must have the
     * same sky and block light. The stored light is read the way the server loads it.
     */
    private static void checkLightParity(ServerLevel level, IOWorker io, List<DynamicBatchPreGen> tasks) throws Exception {
        ChunkBatchPlan.Tile tile = new ChunkBatchPlan.Tile(-20_000, 20_000, 8, 8);
        List<ChunkPos> area = new ArrayList<>();
        for (int x = tile.minChunkX(); x < tile.minChunkX() + tile.chunksX(); x++) {
            for (int z = tile.minChunkZ(); z < tile.minChunkZ() + tile.chunksZ(); z++) area.add(new ChunkPos(x, z));
        }
        for (ChunkPos pos : area) {
            require(io.loadAsync(pos).get(10, TimeUnit.SECONDS).isEmpty(), "Light parity coordinates must be absent: " + pos);
        }
        DynamicBatchPreGen task = newTask(level, tile, tasks);
        invoke(task, "processBatch", new Class<?>[]{int.class, int.class, ChunkBatchPlan.Tile.class}, 1, 1, tile);
        invoke(task, "awaitPendingWrites", new Class<?>[0]);

        int minSection = level.getMinSection();
        int sectionCount = level.getSectionsCount();
        var biomes = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
        Map<Long, net.minecraft.world.level.chunk.ProtoChunk> chunks = new java.util.HashMap<>();
        Map<Long, CompoundTag> stored = new java.util.HashMap<>();
        for (ChunkPos pos : area) {
            CompoundTag tag = io.loadAsync(pos).get(10, TimeUnit.SECONDS).orElseThrow();
            require(tag.getBoolean("isLightOn"), "A generated chunk must be stored lit: " + pos);
            SeamWrites.Final blocks = SeamWrites.finalFromStored(tag, minSection, sectionCount);
            require(blocks != null, "A generated chunk must be stored fully generated: " + pos);
            var chunk = new net.minecraft.world.level.chunk.ProtoChunk(pos, net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                    level, biomes, null);
            for (int i = 0; i < sectionCount; i++) {
                chunk.getSections()[i] = new net.minecraft.world.level.chunk.LevelChunkSection(blocks.states()[i],
                        chunk.getSections()[i].getBiomes());
            }
            chunk.initializeLightSources();
            chunks.put(pos.toLong(), chunk);
            stored.put(pos.toLong(), tag);
        }
        net.minecraft.world.level.chunk.LightChunkGetter blocksOnly = new net.minecraft.world.level.chunk.LightChunkGetter() {
            @Override
            public net.minecraft.world.level.chunk.LightChunk getChunkForLighting(int x, int z) {
                return chunks.get(ChunkPos.asLong(x, z));
            }

            @Override
            public net.minecraft.world.level.BlockGetter getLevel() {
                return level;
            }
        };
        boolean sky = level.dimensionType().hasSkyLight();
        // Minecraft lighting the stored blocks: initializeLight, then lightChunk, for every chunk.
        var vanilla = new net.minecraft.world.level.lighting.LevelLightEngine(blocksOnly, true, sky);
        // The stored light, loaded as ChunkSerializer and initializeLight of a lit chunk load it.
        var saved = new net.minecraft.world.level.lighting.LevelLightEngine(blocksOnly, true, sky);
        for (var chunk : chunks.values()) {
            ChunkPos pos = chunk.getPos();
            for (int i = 0; i < sectionCount; i++) {
                if (chunk.getSections()[i].hasOnlyAir()) continue;
                vanilla.updateSectionStatus(net.minecraft.core.SectionPos.of(pos, minSection + i), false);
                saved.updateSectionStatus(net.minecraft.core.SectionPos.of(pos, minSection + i), false);
            }
            vanilla.setLightEnabled(pos, false);
            vanilla.retainData(pos, false);
            var sections = stored.get(pos.toLong()).getList("sections", net.minecraft.nbt.Tag.TAG_COMPOUND);
            boolean retained = false;
            for (int i = 0; i < sections.size(); i++) {
                CompoundTag section = sections.getCompound(i);
                var sectionPos = net.minecraft.core.SectionPos.of(pos, section.getByte("Y"));
                boolean blockLight = section.contains("BlockLight", net.minecraft.nbt.Tag.TAG_BYTE_ARRAY);
                boolean skyLight = sky && section.contains("SkyLight", net.minecraft.nbt.Tag.TAG_BYTE_ARRAY);
                if ((blockLight || skyLight) && !retained) {
                    saved.retainData(pos, true);
                    retained = true;
                }
                if (blockLight) saved.queueSectionData(net.minecraft.world.level.LightLayer.BLOCK, sectionPos,
                        new net.minecraft.world.level.chunk.DataLayer(section.getByteArray("BlockLight")));
                if (skyLight) saved.queueSectionData(net.minecraft.world.level.LightLayer.SKY, sectionPos,
                        new net.minecraft.world.level.chunk.DataLayer(section.getByteArray("SkyLight")));
            }
            saved.setLightEnabled(pos, true);
            saved.retainData(pos, false);
        }
        for (var chunk : chunks.values()) vanilla.propagateLightSources(chunk.getPos());
        while (vanilla.hasLightWork()) vanilla.runLightUpdates();
        while (saved.hasLightWork()) saved.runLightUpdates();

        long compared = 0;
        long[] differing = new long[2];
        List<String> examples = new ArrayList<>();
        var layers = new net.minecraft.world.level.LightLayer[]{net.minecraft.world.level.LightLayer.SKY,
                net.minecraft.world.level.LightLayer.BLOCK};
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int cx = tile.minChunkX() + 2; cx < tile.minChunkX() + 6; cx++) {
            for (int cz = tile.minChunkZ() + 2; cz < tile.minChunkZ() + 6; cz++) {
                for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            at.set(cx * 16 + x, y, cz * 16 + z);
                            compared++;
                            for (int layer = 0; layer < 2; layer++) {
                                int expected = vanilla.getLayerListener(layers[layer]).getLightValue(at);
                                int actual = saved.getLayerListener(layers[layer]).getLightValue(at);
                                if (expected == actual) continue;
                                differing[layer]++;
                                if (examples.size() < 6) {
                                    examples.add(layers[layer] + "@" + at.toShortString() + "=" + actual + " (Minecraft " + expected + ")");
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.printf("LIGHT_PARITY sky_differing=%d block_differing=%d of %d blocks %s%n",
                differing[0], differing[1], compared, examples);
        require(differing[0] == 0 && differing[1] == 0, "Generated light must equal Minecraft's lighting of the same blocks");
        System.out.println("LIGHT_PARITY_CHECK PASS blocks=" + compared);
    }

    /**
     * Whatever generator the world has (run in a superflat world with -PonlyChecks=anyGenerator):
     * two batches complete with Minecraft's own steps and every chunk is stored fully generated.
     */
    private static void checkAnyGenerator(ServerLevel level, IOWorker io, List<DynamicBatchPreGen> tasks) throws Exception {
        int center = 50_000;
        PreGenSaveFile.delete(level);
        DynamicBatchPreGen task = new DynamicBatchPreGen(level, 1, 512, 4, center, center, message -> {});
        synchronized (tasks) {
            tasks.add(task);
        }
        task.setReleaseMemoryEachBatch(false);
        task.start();
        require("Completed".equals(task.getCurrentStageName()), "The run must complete");
        ChunkBatchPlan plan = new ChunkBatchPlan(1000, 512, center, center);
        Map<Long, BlockState[]> blocks = readArea(io, plan, 0);
        long nonAir = blocks.values().stream().mapToLong(states -> java.util.Arrays.stream(states).filter(b -> !b.isAir()).count()).sum();
        require(nonAir > 0, "The area must have blocks");
        System.out.println("ANY_GENERATOR_CHECK PASS generator=" + level.getChunkSource().getGenerator().getClass().getSimpleName()
                + " chunks=" + blocks.size() + " solid_blocks=" + nonAir);
    }

    /**
     * The Nether and the End: a run completes, every chunk of the area is stored fully
     * generated, and the server loads one of them as it would any chunk.
     */
    private static void checkOtherDimensions(MinecraftServer server, List<DynamicBatchPreGen> tasks) throws Exception {
        StringBuilder result = new StringBuilder();
        for (var key : List.of(net.minecraft.world.level.Level.NETHER, net.minecraft.world.level.Level.END)) {
            ServerLevel dimension = server.getLevel(key);
            require(dimension != null, "Dimension " + key.location() + " must exist");
            int center = key == net.minecraft.world.level.Level.END ? 4_000 : 120_000;
            PreGenSaveFile.delete(dimension);
            DynamicBatchPreGen task = new DynamicBatchPreGen(dimension, 1, 512, 4, center, center, message -> {});
            synchronized (tasks) {
                tasks.add(task);
            }
            task.setReleaseMemoryEachBatch(false);
            task.start();
            require("Completed".equals(task.getCurrentStageName()), key.location() + " run must complete");
            IOWorker io = ((ChunkStorageAccessor) dimension.getChunkSource().chunkMap).pregen$getWorker();
            ChunkBatchPlan plan = new ChunkBatchPlan(1000, 512, center, center);
            int minX = Math.floorDiv(plan.minBlockX(), 16);
            int minZ = Math.floorDiv(plan.minBlockZ(), 16);
            for (int x = minX; x < minX + plan.chunksX(); x++) {
                for (int z = minZ; z < minZ + plan.chunksZ(); z++) {
                    CompoundTag tag = io.loadAsync(new ChunkPos(x, z)).get(10, TimeUnit.SECONDS).orElseThrow(
                            () -> new AssertionError(key.location() + " chunk missing"));
                    require("minecraft:full".equals(tag.getString("Status")), key.location() + " chunk not full");
                }
            }
            ChunkPos middle = new ChunkPos(center >> 4, center >> 4);
            int blocks = server.submit(() -> {
                var chunk = dimension.getChunk(middle.x, middle.z);
                int solid = 0;
                for (var section : chunk.getSections()) if (!section.hasOnlyAir()) solid++;
                return solid;
            }).get(60, TimeUnit.SECONDS);
            require(blocks > 0, key.location() + " loaded chunk must have blocks");
            result.append(' ').append(key.location().getPath()).append("=").append(plan.chunksX() * plan.chunksZ()).append("_chunks");
        }
        System.out.println("OTHER_DIMENSIONS_CHECK PASS" + result);
    }

    /**
     * Stopping after two of four batches and resuming must give the blocks of an uninterrupted
     * run of the same batches: the writes waiting at the stop come back from the part-generated
     * chunks saved with them, and the saved batches take the later batches' writes. The area is
     * erased between the two runs.
     */
    private static void checkResumeEdges(ServerLevel level, IOWorker io, List<DynamicBatchPreGen> tasks) throws Exception {
        int center = 700_000;
        ChunkBatchPlan plan = new ChunkBatchPlan(1000, 512, center, center);
        PreGenSaveFile.delete(level);
        DynamicBatchPreGen uninterrupted = new DynamicBatchPreGen(level, 1, 512, 4, center, center, message -> {});
        synchronized (tasks) {
            tasks.add(uninterrupted);
        }
        uninterrupted.setReleaseMemoryEachBatch(false);
        uninterrupted.start();
        require("Completed".equals(uninterrupted.getCurrentStageName()), "The uninterrupted run must complete");
        Map<Long, BlockState[]> reference = readArea(io, plan, 0);
        erase(io, plan, 1);

        PreGenSaveFile.delete(level);
        java.util.concurrent.atomic.AtomicReference<DynamicBatchPreGen> first = new java.util.concurrent.atomic.AtomicReference<>();
        DynamicBatchPreGen stopped = new DynamicBatchPreGen(level, 1, 512, 4, center, center, message -> {
            if (message.startsWith("[2/4] Completed Tile")) first.get().cancel();
        });
        first.set(stopped);
        DynamicBatchPreGen resumed = new DynamicBatchPreGen(level, 1, 512, 4, center, center, message -> {});
        synchronized (tasks) {
            tasks.add(stopped);
            tasks.add(resumed);
        }
        stopped.setReleaseMemoryEachBatch(false);
        stopped.start();
        require("Cancelled".equals(stopped.getCurrentStageName()), "The first run must stop after two batches");
        resumed.setReleaseMemoryEachBatch(false);
        resumed.start();
        require("Completed".equals(resumed.getCurrentStageName()), "The resumed run must complete");
        long differing = compareStored(io, reference, "resume");
        // Leaves next to a bee nest that moved can get another distance; nothing else may differ.
        require(differing <= 8, "A resumed run must give the blocks of an uninterrupted one: " + differing + " differ");
        System.out.println("RESUME_EDGE_CHECK PASS differing_blocks=" + differing);
    }

    /**
     * A batch that fails on the fast paths (a failure injected before its features, after it
     * took the writes its neighbors left) is generated again the way Minecraft does, and the run
     * completes with the blocks of an undisturbed run: the writes it took are put back.
     */
    private static void checkSafeModeRetry(ServerLevel level, IOWorker io, List<DynamicBatchPreGen> tasks) throws Exception {
        int center = 820_000;
        ChunkBatchPlan plan = new ChunkBatchPlan(1000, 512, center, center);
        PreGenSaveFile.delete(level);
        DynamicBatchPreGen normal = new DynamicBatchPreGen(level, 1, 512, 4, center, center, message -> {});
        synchronized (tasks) {
            tasks.add(normal);
        }
        normal.setReleaseMemoryEachBatch(false);
        normal.start();
        require("Completed".equals(normal.getCurrentStageName()), "The undisturbed run must complete");
        Map<Long, BlockState[]> reference = readArea(io, plan, 0);
        erase(io, plan, 1);

        PreGenSaveFile.delete(level);
        java.util.concurrent.atomic.AtomicBoolean announced = new java.util.concurrent.atomic.AtomicBoolean();
        DynamicBatchPreGen failing = new DynamicBatchPreGen(level, 1, 512, 4, center, center, message -> {
            if (message.contains("Batch failed on the fast paths")) announced.set(true);
        });
        synchronized (tasks) {
            tasks.add(failing);
        }
        failing.setReleaseMemoryEachBatch(false);
        DynamicBatchPreGen.testFailureBeforeFeatures = index -> {
            if (index == 2) throw new IllegalStateException("injected by SAFE_MODE_RETRY_CHECK");
        };
        try {
            failing.start();
        } finally {
            DynamicBatchPreGen.testFailureBeforeFeatures = null;
        }
        require("Completed".equals(failing.getCurrentStageName()), "The run with a failing batch must complete");
        require((boolean) field(failing, "safeMode"), "The failing batch must switch the run to Minecraft's way");
        require(announced.get(), "The switch must be announced");
        long differing = compareStored(io, reference, "safe mode");
        require(differing <= 8, "Generating a failed batch again must give the blocks of an undisturbed run: " + differing + " differ");
        System.out.println("SAFE_MODE_RETRY_CHECK PASS differing_blocks=" + differing + " retried_batch=2");
    }

    /**
     * resume_after_restart: a run recorded as cut short (neither completed nor stopped) is continued
     * and completes, which clears its count of continuations; a run stopped on purpose is not
     * continued, nor one continued MAX_AUTO_RESUMES times in a row without saving a batch.
     */
    private static void checkAutoResume(MinecraftServer server, ServerLevel level, List<DynamicBatchPreGen> tasks) throws Exception {
        // The server's own check on its first tick (resume_after_restart) must not race this one.
        long ticked = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
        while (!PregenRuns.checkedForResume(server) && System.nanoTime() - ticked < 0) Thread.sleep(50);
        require(PregenRuns.checkedForResume(server), "The server's first tick must check for a run to continue");
        int center = 940_000;
        PreGenSaveFile.delete(level);
        PreGenSaveFile record = PreGenSaveFile.load(level);
        record.setLastRun(512, center, center, ChunkBatchPlan.Shape.SQUARE, false);
        record.setLastStopped(true);
        record.save();
        require(PregenRuns.resumeAfterRestart(server) == null && !PregenRuns.isRunning(),
                "A run stopped on purpose must not be continued");

        record.setLastStopped(false);
        for (int i = 0; i < PregenRuns.MAX_AUTO_RESUMES; i++) record.countAutoResume();
        record.save();
        String gaveUp = PregenRuns.resumeAfterRestart(server);
        require(gaveUp != null && gaveUp.startsWith("Not continuing") && !PregenRuns.isRunning(),
                "A run continued " + PregenRuns.MAX_AUTO_RESUMES + " times without progress must not be continued again: " + gaveUp);

        record.clearAutoResumes();
        record.save();
        String continued = PregenRuns.resumeAfterRestart(server);
        require(continued != null && continued.startsWith("Continuing"), "A run cut short must be continued: " + continued);
        DynamicBatchPreGen task = PregenRuns.current();
        synchronized (tasks) {
            tasks.add(task);
        }
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        while (task.isRunning() && System.nanoTime() < deadline) Thread.sleep(200);
        require("Completed".equals(task.getCurrentStageName()), "The continued run must complete: " + task.getCurrentStageName());
        PreGenSaveFile after = PreGenSaveFile.load(level);
        require(after.lastCompleted() && !after.lastStopped() && after.autoResumes() == 0,
                "A completed run must clear its continuations: completed=" + after.lastCompleted() + " continuations="
                        + after.autoResumes());
        System.out.println("AUTO_RESUME_CHECK PASS stopped_not_continued=true gave_up_after=" + PregenRuns.MAX_AUTO_RESUMES
                + " continued_and_completed=true chunks=" + task.getTotalWorldChunks());
        checkServerStop(server, level, tasks);
    }

    /**
     * The server stops while a batch generates and the one before it is being lit
     * (PregenRuns#stopForServer, as MinecraftServerMixin calls it): the generating batch is
     * dropped without touching the light the other one is computing, that one is saved, the run
     * is not marked as stopped, and the next server start continues it.
     */
    private static void checkServerStop(MinecraftServer server, ServerLevel level, List<DynamicBatchPreGen> tasks) throws Exception {
        int center = 960_000;
        ChunkBatchPlan plan = new ChunkBatchPlan(1000, 512, center, center);
        PreGenSaveFile.delete(level);
        java.util.concurrent.atomic.AtomicReference<DynamicBatchPreGen> cut = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch secondBatch = new java.util.concurrent.CountDownLatch(1);
        DynamicBatchPreGen stopped = new DynamicBatchPreGen(level, 1, 512, 4, center, center, message -> {
            if (message.startsWith("[2/4]") && message.contains("Starting Tile")) secondBatch.countDown();
        });
        cut.set(stopped);
        synchronized (tasks) {
            tasks.add(stopped);
        }
        stopped.setReleaseMemoryEachBatch(false);
        Thread runner = new Thread(stopped::start, "PartialBatchCheck-ServerStop");
        runner.setDaemon(true);
        runner.start();
        require(secondBatch.await(5, TimeUnit.MINUTES), "The run must reach its second batch");
        // The first batch lights beside the second one's generation: stop then.
        Field gridsField = InMemoryChunkGrid.class.getDeclaredField("activeGrids");
        gridsField.setAccessible(true);
        java.util.Set<?> grids = (java.util.Set<?>) gridsField.get(null);
        long lightDeadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        boolean firstLighting = false;
        while (!firstLighting && System.nanoTime() < lightDeadline) {
            for (Object grid : grids) firstLighting |= (Boolean) field(grid, "lighting");
            if (!firstLighting) Thread.sleep(1);
        }
        require(firstLighting, "The first batch must be lit while the second one generates");
        long stopStarted = System.nanoTime();
        // From this thread: the server keeps answering the run's requests, as a stopping server
        // answers them on the threads that make them.
        PregenRuns.stopForServer(server, stopped);
        long waitedMillis = (System.nanoTime() - stopStarted) / 1_000_000;
        require(stopped.awaitEnd(0), "The stopping server must wait until the run has ended");
        require(!stopped.hasFailed() && !stopped.isCompleted()
                        && "Stopped by the server stop".equals(stopped.getCurrentStageName()),
                "A server stop must stop the run without failing it: " + stopped.getCurrentStageName());
        PreGenSaveFile cutShort = PreGenSaveFile.load(level);
        int firstBatch = plan.areaChunks(plan.tile(0, 0));
        require(cutShort.hasLastRun() && !cutShort.lastCompleted() && !cutShort.lastStopped()
                        && cutShort.doneChunks() == firstBatch,
                "A run cut short by a server stop must keep its saved batch and stay continuable: completed="
                        + cutShort.lastCompleted() + " stopped=" + cutShort.lastStopped() + " saved=" + cutShort.doneChunks()
                        + " first batch=" + firstBatch);

        String continued = PregenRuns.resumeAfterRestart(server);
        require(continued != null && continued.startsWith("Continuing"),
                "A run cut short by a server stop must be continued: " + continued);
        DynamicBatchPreGen rest = PregenRuns.current();
        synchronized (tasks) {
            tasks.add(rest);
        }
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        while (rest.isRunning() && System.nanoTime() < deadline) Thread.sleep(200);
        require("Completed".equals(rest.getCurrentStageName()) && rest.getKeptChunksCount() >= firstBatch,
                "The continued run must complete and keep the saved batch: " + rest.getCurrentStageName() + ", kept "
                        + rest.getKeptChunksCount());
        System.out.println("SERVER_STOP_CHECK PASS saved_batch_kept=" + firstBatch + " continued_and_completed=true wait_ms="
                + waitedMillis);
    }

    /**
     * A circle: every chunk whose center lies within the radius is stored fully generated and no
     * other chunk of the square around it is; the circle's inner chunks (every chunk within four of
     * them in the circle too) have the blocks of the same chunks generated as part of the whole
     * square. Features that read their neighbors carry the edge's different order a few chunks in
     * (26.x's leaf litter, around trees, reached four), as in Minecraft whenever chunks generate in
     * another order: a few dozen blocks may differ, where a broken circle differs in thousands.
     */
    private static void checkCircle(ServerLevel level, IOWorker io, List<DynamicBatchPreGen> tasks) throws Exception {
        int center = 900_000;
        int size = 600;
        ChunkBatchPlan square = new ChunkBatchPlan(size, 512, center, center);
        ChunkBatchPlan circle = new ChunkBatchPlan(size, 512, center, center, ChunkBatchPlan.Shape.CIRCLE);
        PreGenSaveFile.delete(level);
        DynamicBatchPreGen whole = DynamicBatchPreGen.area(level, size, ChunkBatchPlan.Shape.SQUARE, 512, 4, center, center,
                message -> {});
        synchronized (tasks) {
            tasks.add(whole);
        }
        whole.setReleaseMemoryEachBatch(false);
        whole.start();
        require("Completed".equals(whole.getCurrentStageName()), "The square run must complete");
        Map<Long, BlockState[]> squareBlocks = readArea(io, square, 0);
        erase(io, square, 1);

        PreGenSaveFile.delete(level);
        DynamicBatchPreGen round = DynamicBatchPreGen.area(level, size, ChunkBatchPlan.Shape.CIRCLE, 512, 4, center, center,
                message -> {});
        synchronized (tasks) {
            tasks.add(round);
        }
        round.setReleaseMemoryEachBatch(false);
        round.start();
        require("Completed".equals(round.getCurrentStageName()), "The circle run must complete: " + round.getCurrentStageName());
        require(round.getTotalWorldChunks() == circle.totalChunks() && round.getCompletedChunksCount() == circle.totalChunks(),
                "The circle run must count the circle's chunks: " + round.getCompletedChunksCount() + "/" + round.getTotalWorldChunks()
                        + " of " + circle.totalChunks());
        int minX = Math.floorDiv(square.minBlockX(), 16);
        int minZ = Math.floorDiv(square.minBlockZ(), 16);
        int full = 0;
        int outsideFull = 0;
        Map<Long, BlockState[]> inner = new java.util.HashMap<>();
        for (int x = minX - 1; x <= minX + square.chunksX(); x++) {
            for (int z = minZ - 1; z <= minZ + square.chunksZ(); z++) {
                CompoundTag tag = io.loadAsync(new ChunkPos(x, z)).get(10, TimeUnit.SECONDS).orElse(null);
                boolean isFull = tag != null && "minecraft:full".equals(tag.getString("Status"));
                if (!circle.containsChunk(x, z)) {
                    if (isFull) outsideFull++;
                    continue;
                }
                require(isFull, "Every chunk of the circle must be stored fully generated: " + x + ", " + z);
                full++;
                boolean innerChunk = true;
                for (int dx = -4; dx <= 4 && innerChunk; dx++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        if (!circle.containsChunk(x + dx, z + dz)) {
                            innerChunk = false;
                            break;
                        }
                    }
                }
                if (innerChunk) inner.put(ChunkPos.asLong(x, z), squareBlocks.get(ChunkPos.asLong(x, z)));
            }
        }
        require(outsideFull == 0, "No chunk outside the circle may be stored fully generated: " + outsideFull);
        require(inner.size() > full / 3, "The circle must have inner chunks to compare: " + inner.size());
        long differing = compareStored(io, inner, "circle");
        require(differing <= 64, "The circle's inner chunks must have the square run's blocks: " + differing + " differ");
        PreGenSaveFile record = PreGenSaveFile.load(level);
        require(record.lastShape() == ChunkBatchPlan.Shape.CIRCLE && record.lastSizeBlocks() == size,
                "The save file must record the circle for /pregen resume");
        System.out.println("CIRCLE_CHECK PASS chunks=" + full + " inner_compared=" + inner.size() + " differing_blocks="
                + differing + " outside_full=0");
    }

    /**
     * The API: a run started through it, with a radius that is no multiple of 500, completes; its
     * listener sees the start, every saved chunk once (all inside the area) and the completed end;
     * a second start while it runs is refused.
     */
    private static void checkApi(ServerLevel level, List<DynamicBatchPreGen> tasks) throws Exception {
        PregenApi api = UltraFastPregenApi.get();
        require(api.apiVersion() == UltraFastPregenApi.VERSION, "The API must report its version");
        String dimension = level.dimension().location().toString();
        int center = 860_000;
        int radius = 300;
        ChunkBatchPlan plan = new ChunkBatchPlan(2 * radius, 1000, center, center);
        PreGenSaveFile.delete(level);
        List<PregenStatus> starts = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<PregenStatus> finishes = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicInteger progress = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        AtomicInteger outside = new AtomicInteger();
        it.unimi.dsi.fastutil.longs.LongSet saved = it.unimi.dsi.fastutil.longs.LongSets.synchronize(
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet());
        PregenListener listener = new PregenListener() {
            @Override
            public void onStart(PregenStatus status) {
                starts.add(status);
            }

            @Override
            public void onProgress(PregenStatus status) {
                progress.incrementAndGet();
            }

            @Override
            public void onChunksSaved(String savedIn, long[] chunks) {
                for (long chunk : chunks) {
                    if (!savedIn.equals(dimension) || !plan.containsChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) {
                        outside.incrementAndGet();
                    }
                    if (!saved.add(chunk)) duplicates.incrementAndGet();
                }
            }

            @Override
            public void onFinish(PregenStatus status) {
                finishes.add(status);
            }
        };
        api.addListener(listener);
        try {
            StartResult result = api.start(PregenRequest.square(dimension, center, center, radius));
            require(result.started(), "A start through the API must start: " + result.message());
            synchronized (tasks) {
                tasks.add(PregenRuns.current());
            }
            StartResult second = api.start(PregenRequest.square(dimension, -center, -center, radius));
            require(!second.started(), "A second start while a run is active must be refused");
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
            while (api.isRunning() && System.nanoTime() < deadline) Thread.sleep(200);
            PregenStatus status = api.status().orElseThrow();
            require(status.state() == PregenStatus.State.COMPLETED, "The API run must complete: " + status.state());
            require(status.totalChunks() == plan.totalChunks() && status.doneChunks() == plan.totalChunks(),
                    "The run must cover the requested square: " + status.doneChunks() + "/" + status.totalChunks()
                            + " of " + plan.totalChunks());
            require(status.request().radius() == radius && status.request().dimension().equals(dimension),
                    "The status must describe the request");
            require(starts.size() == 1 && starts.get(0).state() == PregenStatus.State.RUNNING, "onStart must come once");
            require(finishes.size() == 1 && finishes.get(0).state() == PregenStatus.State.COMPLETED,
                    "onFinish must come once, completed");
            require(saved.size() == plan.totalChunks() && duplicates.get() == 0 && outside.get() == 0,
                    "onChunksSaved must report every saved chunk once: " + saved.size() + "/" + plan.totalChunks()
                            + ", duplicates " + duplicates.get() + ", outside " + outside.get());
            require(progress.get() > 0, "onProgress must be called");
        } finally {
            api.removeListener(listener);
        }
        System.out.println("API_CHECK PASS chunks=" + plan.totalChunks() + " radius=" + radius + " saved_events="
                + saved.size() + " progress_events=" + progress.get() + " second_start_refused=true");
    }

    /**
     * A small area generated first and a larger one around it later (keeping the existing
     * chunks): the existing chunks keep their blocks and take their new neighbors' writes, and
     * the new chunks get the writes saved in the part-generated chunks around the small area.
     * Measured like the batch seams: near the small area's edge, against one grid whose features
     * run the small area first, the result must differ far less than without joining stored
     * chunks (as before 1.20.1-10). Afterwards the server must finish a border chunk.
     */
    private static void checkExistingNeighbors(MinecraftServer server, ServerLevel level, IOWorker io,
                                               List<DynamicBatchPreGen> tasks) throws Exception {
        int center = 800_000;
        ChunkBatchPlan plan = new ChunkBatchPlan(1000, 1000, center, center);
        int minX = plan.tile(0, 0).minChunkX() + 20;
        int minZ = plan.tile(0, 0).minChunkZ() + 18;
        ChunkBatchPlan.Tile small = new ChunkBatchPlan.Tile(minX, minZ, 16, 16);
        Map<Long, BlockState[]> reference = memoryBlocks(level, plan, (x, z) ->
                x >= minX && x < minX + 16 && z >= minZ && z < minZ + 16 ? 0 : 1);
        Map<Long, BlockState[]> broken;
        SeamWrites.joinStoredChunks = false;
        try {
            broken = smallThenAround(level, io, tasks, plan, small, center);
        } finally {
            SeamWrites.joinStoredChunks = true;
        }
        erase(io, plan, 1);
        Map<Long, BlockState[]> fixed = smallThenAround(level, io, tasks, plan, small, center);
        double[] fixedEdge = edgeProfile(reference, fixed, minX, minZ);
        double[] brokenEdge = edgeProfile(reference, broken, minX, minZ);
        System.out.printf(java.util.Locale.ROOT,
                "EXISTING_EDGE_PROFILE near_edge(blocks): fixed=%.0f broken=%.0f | away: fixed=%.0f broken=%.0f%n",
                fixedEdge[0], brokenEdge[0], fixedEdge[1], brokenEdge[1]);
        require(brokenEdge[0] > 1000 && brokenEdge[0] > 4 * brokenEdge[1], "Without joining, the small area's edge must stand out (setup)");
        require(fixedEdge[0] < brokenEdge[0] / 4, "Joining stored chunks must remove most of the edge difference");

        int outsideX = plan.tile(0, 0).minChunkX() - 1;
        int outsideZ = plan.tile(0, 0).minChunkZ() + 30;
        CompoundTag border = io.loadAsync(new ChunkPos(outsideX, outsideZ)).get(10, TimeUnit.SECONDS).orElseThrow(
                () -> new AssertionError("A border chunk with neighbor writes must be saved part-generated"));
        require("minecraft:carvers".equals(border.getString("Status")), "Border chunks are saved at status carvers: " + border.getString("Status"));
        server.submit(() -> {
            require(level.getChunk(outsideX, outsideZ) != null, "The server must finish a part-generated border chunk");
            return null;
        }).get(120, TimeUnit.SECONDS);
        System.out.printf(java.util.Locale.ROOT, "EXISTING_NEIGHBORS_CHECK PASS edge_diff_blocks fixed=%.0f broken=%.0f border_finished=true%n",
                fixedEdge[0], brokenEdge[0]);
    }

    /** The small tile generated alone, then the whole plan around it keeping existing chunks. */
    private static Map<Long, BlockState[]> smallThenAround(ServerLevel level, IOWorker io, List<DynamicBatchPreGen> tasks,
                                                           ChunkBatchPlan plan, ChunkBatchPlan.Tile small, int center)
            throws Exception {
        PreGenSaveFile.delete(level);
        DynamicBatchPreGen first = newTask(level, small, tasks);
        setField(first, "storedAtStart", WorldChunkIO.StoredChunks.NONE);
        invoke(first, "processBatch", new Class<?>[]{int.class, int.class, ChunkBatchPlan.Tile.class}, 1, 1, small);
        invoke(first, "awaitPendingWrites", new Class<?>[0]);
        // Kept as chunks of the world, not as this mod's own earlier run.
        PreGenSaveFile.delete(level);
        DynamicBatchPreGen around = new DynamicBatchPreGen(level, 1, 1000, 4, center, center, message -> {});
        synchronized (tasks) {
            tasks.add(around);
        }
        around.setSkipExistingChunks(true);
        around.setReleaseMemoryEachBatch(false);
        around.start();
        require("Completed".equals(around.getCurrentStageName()), "The larger run must complete");
        require(((AtomicInteger) field(around, "keptExistingChunks")).get() == 256, "The small area's chunks must be kept");
        return readArea(io, plan, 0);
    }

    /** Differing blocks around the small area: [within 3 blocks of its edge, 24..40 blocks from it]. */
    private static double[] edgeProfile(Map<Long, BlockState[]> a, Map<Long, BlockState[]> b, int minChunkX, int minChunkZ) {
        int minX = minChunkX * 16;
        int minZ = minChunkZ * 16;
        int maxX = minX + 16 * 16;
        int maxZ = minZ + 16 * 16;
        long near = 0;
        long away = 0;
        for (Map.Entry<Long, BlockState[]> entry : a.entrySet()) {
            BlockState[] other = b.get(entry.getKey());
            if (other == null) continue;
            int chunkX = ChunkPos.getX(entry.getKey()) * 16;
            int chunkZ = ChunkPos.getZ(entry.getKey()) * 16;
            BlockState[] mine = entry.getValue();
            for (int i = 0; i < mine.length; i++) {
                if (mine[i] == other[i]) continue;
                int x = chunkX + (i & 15);
                int z = chunkZ + ((i >> 4) & 15);
                int distance = Math.min(Math.min(Math.abs(x - minX), Math.abs(x - maxX)), Math.min(Math.abs(z - minZ), Math.abs(z - maxZ)));
                boolean alongEdge = x >= minX - 48 && x < maxX + 48 && z >= minZ - 48 && z < maxZ + 48;
                if (!alongEdge) continue;
                if (distance <= 3) near++;
                else if (distance >= 24 && distance <= 40) away++;
            }
        }
        return new double[]{near, away};
    }

    /** The stored blocks of the plan's chunks. */
    private static Map<Long, BlockState[]> readArea(IOWorker io, ChunkBatchPlan plan, int ring) throws Exception {
        Map<Long, BlockState[]> blocks = new java.util.HashMap<>();
        int minX = Math.floorDiv(plan.minBlockX(), 16) - ring;
        int minZ = Math.floorDiv(plan.minBlockZ(), 16) - ring;
        for (int x = minX; x < minX + plan.chunksX() + 2 * ring; x++) {
            for (int z = minZ; z < minZ + plan.chunksZ() + 2 * ring; z++) {
                ChunkPos pos = new ChunkPos(x, z);
                CompoundTag tag = io.loadAsync(pos).get(10, TimeUnit.SECONDS).orElseThrow(
                        () -> new AssertionError("Chunk " + pos + " must be stored"));
                blocks.put(pos.toLong(), decodeBlocks(tag));
            }
        }
        return blocks;
    }

    /** Removes the plan's chunks and the ring around them from the region files. */
    private static void erase(IOWorker io, ChunkBatchPlan plan, int ring) throws Exception {
        int minX = Math.floorDiv(plan.minBlockX(), 16) - ring;
        int minZ = Math.floorDiv(plan.minBlockZ(), 16) - ring;
        for (int x = minX; x < minX + plan.chunksX() + 2 * ring; x++) {
            for (int z = minZ; z < minZ + plan.chunksZ() + 2 * ring; z++) {
                io.store(new ChunkPos(x, z), null);
            }
        }
        io.synchronize(true).get(60, TimeUnit.SECONDS);
    }

    /** Blocks of the plan's chunks generated in one grid in memory, features ordered by group. */
    private static Map<Long, BlockState[]> memoryBlocks(ServerLevel level, ChunkBatchPlan plan,
                                                        java.util.function.IntBinaryOperator groups) {
        InMemoryChunkGrid grid = new InMemoryChunkGrid(level, plan.minBlockX(), plan.minBlockZ(),
                plan.chunksX(), plan.chunksZ(), true, true);
        InMemoryChunkGrid.setCurrentGrid(grid);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        InMemoryChunkGrid.featureGroups = groups;
        try {
            grid.executeLayer(ChunkLayer.STRUCTURES, pool, null);
            grid.executeLayer(ChunkLayer.TERRAIN_PIPELINE, pool, null);
            grid.executeLayer(ChunkLayer.CARVERS, pool, null);
            grid.executePhased(ChunkLayer.FEATURES, pool, null);
            Map<Long, BlockState[]> blocks = new java.util.HashMap<>();
            for (long packed : grid.getCorePositions()) {
                var chunk = grid.getChunk(ChunkPos.getX(packed), ChunkPos.getZ(packed));
                BlockState[] states = new BlockState[24 * 4096];
                var sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) states[i * 4096 + (y << 8 | z << 4 | x)] = sections[i].getBlockState(x, y, z);
                        }
                    }
                }
                blocks.put(packed, states);
            }
            return blocks;
        } finally {
            InMemoryChunkGrid.featureGroups = null;
            grid.dispose();
            InMemoryChunkGrid.setCurrentGrid(null);
            pool.shutdownNow();
        }
    }

    /**
     * Differing blocks between the stored chunks and the reference, prints the first few. Bee
     * nests are counted apart: BeehiveDecorator picks their side with an unseeded shuffle in
     * 1.20.1, so two runs of Minecraft itself already differ there.
     */
    private static long compareStored(IOWorker io, Map<Long, BlockState[]> reference, String label) throws Exception {
        long differing = 0;
        long beeNests = 0;
        for (Map.Entry<Long, BlockState[]> entry : reference.entrySet()) {
            ChunkPos pos = new ChunkPos(entry.getKey());
            CompoundTag tag = io.loadAsync(pos).get(10, TimeUnit.SECONDS).orElseThrow(
                    () -> new AssertionError("Chunk " + pos + " must be stored"));
            BlockState[] stored = decodeBlocks(tag);
            BlockState[] expected = entry.getValue();
            for (int i = 0; i < expected.length; i++) {
                if (expected[i] == stored[i]) continue;
                if (expected[i].is(Blocks.BEE_NEST) || stored[i].is(Blocks.BEE_NEST)) {
                    beeNests++;
                    continue;
                }
                if (differing++ < 10) {
                    System.out.println("EDGE_DIFF " + label + " " + (pos.getMinBlockX() + (i & 15)) + "," + ((i >> 8) - 64) + ","
                            + (pos.getMinBlockZ() + ((i >> 4) & 15)) + " expected=" + expected[i] + " stored=" + stored[i]);
                }
            }
        }
        System.out.println("EDGE_COMPARE " + label + " differing=" + differing + " bee_nests=" + beeNests);
        return differing;
    }

    /**
     * Features that cross a batch boundary: one area generated as a single batch (no internal
     * edge) and as 512-block batches, with and without SeamWrites. Near the internal edge, the
     * batched result must differ from the single batch no more than elsewhere when the writes
     * are carried across, and clearly more without them.
     */
    private static void checkBatchSeams(ServerLevel level, IOWorker io, List<DynamicBatchPreGen> tasks) throws Exception {
        int center = 600_000;
        ChunkBatchPlan plan = new ChunkBatchPlan(1000, 512, center, center);
        int seamChunk = plan.tile(1, 0).minChunkX();
        int minChunk = plan.tile(0, 0).minChunkX();
        int maxChunk = minChunk + plan.chunksX() - 1;
        Map<Long, BlockState[]> single = runAndRead(level, io, tasks, center, 1000, true, seamChunk, minChunk, maxChunk);
        Map<Long, BlockState[]> withSeams = runAndRead(level, io, tasks, center, 512, true, seamChunk, minChunk, maxChunk);
        // The same order in one grid: every tile's features after those of the tiles before it,
        // which is the order a batched run gives Minecraft (tiles x-major, as start() runs them).
        int seamZ = plan.tile(0, 1).minChunkZ();
        InMemoryChunkGrid.featureGroups = (x, z) -> (x >= seamChunk ? 2 : 0) + (z >= seamZ ? 1 : 0);
        Map<Long, BlockState[]> tileOrder;
        try {
            tileOrder = runAndRead(level, io, tasks, center, 1000, true, seamChunk, minChunk, maxChunk);
        } finally {
            InMemoryChunkGrid.featureGroups = null;
        }
        long orderDiff = 0;
        for (Map.Entry<Long, BlockState[]> entry : tileOrder.entrySet()) {
            BlockState[] a = entry.getValue();
            BlockState[] b = withSeams.get(entry.getKey());
            for (int i = 0; i < a.length; i++) {
                if (a[i] == b[i]) continue;
                if (orderDiff++ < 10) {
                    System.out.println("BATCH_ORDER_DIFF " + (ChunkPos.getX(entry.getKey()) * 16 + (i & 15)) + "," + ((i >> 8) - 64)
                            + "," + (ChunkPos.getZ(entry.getKey()) * 16 + ((i >> 4) & 15)) + " sameOrder=" + a[i] + " batched=" + b[i]);
                }
            }
        }
        System.out.println("BATCH_ORDER_PARITY differing_blocks=" + orderDiff + " of " + (long) tileOrder.size() * 24 * 4096);
        // A few blocks may differ between any two runs: BeehiveDecorator places hives with an
        // unseeded shuffle in 1.20.1, so the same order allows only that much.
        require(orderDiff <= 32, "Batched features must equal one grid running the tiles in the same order: " + orderDiff + " blocks differ");
        Map<Long, BlockState[]> withoutSeams = runAndRead(level, io, tasks, center, 512, false, seamChunk, minChunk, maxChunk);
        int seamX = seamChunk * 16;
        double[] fixed = seamProfile(single, withSeams, seamX);
        double[] broken = seamProfile(single, withoutSeams, seamX);
        System.out.printf(java.util.Locale.ROOT,
                "BATCH_SEAM_PROFILE near_edge(per column): fixed=%.1f broken=%.1f | away: fixed=%.1f broken=%.1f%n",
                fixed[0], broken[0], fixed[1], broken[1]);
        require(broken[0] > 4 * Math.max(1.0, broken[1]), "Without seam writes the edge must stand out (regression setup)");
        require(fixed[0] < broken[0] / 4, "Carrying feature writes across batch edges must remove most of the edge difference");
        System.out.printf(java.util.Locale.ROOT, "BATCH_SEAM_CHECK PASS edge_diff_per_column fixed=%.1f broken=%.1f same_order_diff=%d%n",
                fixed[0], broken[0], orderDiff);
    }

    private static Map<Long, BlockState[]> runAndRead(ServerLevel level, IOWorker io, List<DynamicBatchPreGen> tasks,
                                                      int center, int batch, boolean seams, int seamChunk,
                                                      int minChunk, int maxChunk) throws Exception {
        PreGenSaveFile.delete(level);
        SeamWrites.enabled = seams;
        // The same area again: the previous run's border chunks would bring its writes along.
        SeamWrites.importStoredChunks = false;
        try {
            DynamicBatchPreGen task = new DynamicBatchPreGen(level, 1, batch, 4, center, center, message -> {});
            synchronized (tasks) {
                tasks.add(task);
            }
            task.setReleaseMemoryEachBatch(false);
            task.start();
            require("Completed".equals(task.getCurrentStageName()), "Seam run must complete");
        } finally {
            SeamWrites.enabled = true;
            SeamWrites.importStoredChunks = true;
        }
        Map<Long, BlockState[]> blocks = new java.util.HashMap<>();
        for (int cx = seamChunk - 6; cx <= seamChunk + 5; cx++) {
            for (int cz = minChunk + 2; cz <= maxChunk - 2; cz++) {
                CompoundTag tag = io.loadAsync(new ChunkPos(cx, cz)).get(10, TimeUnit.SECONDS).orElseThrow();
                blocks.put(ChunkPos.asLong(cx, cz), decodeBlocks(tag));
            }
        }
        return blocks;
    }

    private static BlockState[] decodeBlocks(CompoundTag chunk) {
        BlockState[] states = new BlockState[24 * 4096];
        java.util.Arrays.fill(states, Blocks.AIR.defaultBlockState());
        net.minecraft.nbt.ListTag sections = chunk.getList("sections", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            int index = section.getByte("Y") + 4;
            if (index < 0 || index >= 24 || !section.contains("block_states")) continue;
            var container = SeamWrites.BLOCK_STATE_CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,
                    section.getCompound("block_states")).result().orElseThrow();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) states[index * 4096 + (y << 8 | z << 4 | x)] = container.get(x, y, z);
                }
            }
        }
        return states;
    }

    /** Differing blocks per x column: [mean within 3 columns of the edge, mean 24..80 columns away]. */
    private static double[] seamProfile(Map<Long, BlockState[]> a, Map<Long, BlockState[]> b, int seamX) {
        java.util.Map<Integer, Integer> perColumn = new java.util.HashMap<>();
        for (Map.Entry<Long, BlockState[]> entry : a.entrySet()) {
            BlockState[] other = b.get(entry.getKey());
            int minX = ChunkPos.getX(entry.getKey()) * 16;
            BlockState[] mine = entry.getValue();
            for (int i = 0; i < mine.length; i++) {
                if (mine[i] != other[i]) perColumn.merge(minX + (i & 15) - seamX, 1, Integer::sum);
            }
        }
        double near = 0;
        for (int d = -3; d <= 2; d++) near += perColumn.getOrDefault(d, 0);
        double away = 0;
        int awayColumns = 0;
        for (int d = -96; d <= 95; d++) {
            if (Math.abs(d) >= 24 && Math.abs(d) <= 80) {
                away += perColumn.getOrDefault(d, 0);
                awayColumns++;
            }
        }
        return new double[]{near / 6, away / Math.max(1, awayColumns)};
    }

    /**
     * skip_existing_chunks through a full start(): chunks on disk and chunks the server holds are
     * kept, every other chunk is generated, and a second run finds the whole area existing.
     */
    private static void checkSkipExisting(MinecraftServer server, ServerLevel level, IOWorker io,
                                          List<DynamicBatchPreGen> tasks) throws Exception {
        PreGenSaveFile.delete(level);
        int center = 400_000;
        int minChunk = Math.floorDiv(center - 500, 16);
        ChunkPos storedA = new ChunkPos(minChunk + 3, minChunk + 5);
        ChunkPos storedB = new ChunkPos(minChunk + 40, minChunk + 41);
        ChunkPos held = new ChunkPos(minChunk + 20, minChunk + 30);
        // Part-way generated on disk: does not exist yet, so it is generated and overwritten.
        ChunkPos storedProto = new ChunkPos(minChunk + 50, minChunk + 7);
        for (ChunkPos pos : new ChunkPos[]{storedA, storedB, storedProto}) {
            CompoundTag marker = new CompoundTag();
            marker.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            marker.putInt("xPos", pos.x);
            marker.putInt("zPos", pos.z);
            marker.putString("Status", pos == storedProto ? "minecraft:structure_starts" : "minecraft:full");
            marker.putString("PartialBatchCheckMarker", MARKER);
            io.store(pos, marker).get(10, TimeUnit.SECONDS);
        }
        io.synchronize(true).get(10, TimeUnit.SECONDS);
        BlockPos build = new BlockPos(held.getMinBlockX() + 8, 300, held.getMinBlockZ() + 8);
        server.submit(() -> {
            level.getChunkSource().addRegionTicket(net.minecraft.server.level.TicketType.FORCED, held, 0, held);
            level.getChunk(held.x, held.z);
            level.setBlock(build, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            return null;
        }).get(120, TimeUnit.SECONDS);
        String heldStatuses = server.submit(() -> {
            var map = (com.example.pregen.mixin.ChunkMapAccessor) level.getChunkSource().chunkMap;
            java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
            for (int x = minChunk; x < minChunk + 63; x++) {
                for (int z = minChunk; z < minChunk + 63; z++) {
                    var holder = map.pregen$getVisibleChunk(ChunkPos.asLong(x, z));
                    if (holder == null) continue;
                    var chunk = holder.getLatestChunk();
                    String key = chunk == null ? "null" : chunk.getClass().getSimpleName() + ":" + chunk.getPersistedStatus();
                    counts.merge(key, 1, Integer::sum);
                }
            }
            return counts.toString();
        }).get(10, TimeUnit.SECONDS);
        System.out.println("SKIP_EXISTING_HELD " + heldStatuses);
        try {
            DynamicBatchPreGen first = new DynamicBatchPreGen(level, 1, 1000, 4, center, center, System.out::println);
            synchronized (tasks) {
                tasks.add(first);
            }
            first.setSkipExistingChunks(true);
            first.setReleaseMemoryEachBatch(false);
            first.start();
            int total = first.getTotalWorldChunks();
            require("Completed".equals(first.getCurrentStageName()), "Skip-existing run must complete");
            int written = ((AtomicInteger) field(first, "writtenChunks")).get();
            require(written == total - 3, "All chunks but the 2 stored and 1 held must be written: " + written + "/" + total);
            for (ChunkPos pos : new ChunkPos[]{storedA, storedB}) {
                require(io.loadAsync(pos).get(10, TimeUnit.SECONDS).orElseThrow().contains("PartialBatchCheckMarker"),
                        "A chunk stored before the run must be kept: " + pos);
            }
            require(server.submit(() -> level.getBlockState(build).is(Blocks.DIAMOND_BLOCK)).get(10, TimeUnit.SECONDS),
                    "A chunk the server holds must keep its blocks");
            ChunkPos generated = new ChunkPos(minChunk + 10, minChunk + 10);
            require("minecraft:full".equals(io.loadAsync(generated).get(10, TimeUnit.SECONDS).orElseThrow().getString("Status")),
                    "A missing chunk must be generated");
            CompoundTag proto = io.loadAsync(storedProto).get(10, TimeUnit.SECONDS).orElseThrow();
            require("minecraft:full".equals(proto.getString("Status")) && !proto.contains("PartialBatchCheckMarker"),
                    "A part-way generated chunk on disk must be generated and overwritten");
            // The server held part-way generated chunks around the loaded one; none may stay unfinished.
            int unfinished = 0;
            for (int x = minChunk; x < minChunk + 63; x++) {
                for (int z = minChunk; z < minChunk + 63; z++) {
                    var stored = io.loadAsync(new ChunkPos(x, z)).get(10, TimeUnit.SECONDS);
                    if (stored.isPresent() && !"minecraft:full".equals(stored.get().getString("Status"))) unfinished++;
                }
            }
            require(unfinished == 0, unfinished + " chunks in the area are stored part-way generated");

            DynamicBatchPreGen second = new DynamicBatchPreGen(level, 1, 1000, 4, center, center, System.out::println);
            synchronized (tasks) {
                tasks.add(second);
            }
            second.setSkipExistingChunks(true);
            second.start();
            require("Completed".equals(second.getCurrentStageName()), "Second skip-existing run must complete");
            require(((AtomicInteger) field(second, "writtenChunks")).get() == 0, "A fully existing area must write nothing");
            require((int) field(second, "existingBatches") == 1, "The batch must be skipped as already existing");
            System.out.printf(java.util.Locale.ROOT, "SKIP_EXISTING_CHECK PASS chunks=%d written=%d kept_stored=2 kept_held=1 rerun_skipped_batches=1%n",
                    total, written);
        } finally {
            server.submit(() -> {
                level.getChunkSource().removeRegionTicket(net.minecraft.server.level.TicketType.FORCED, held, 0, held);
                return null;
            }).get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Chunks the server finishes while a batch generates (a player exploring, or another mod
     * generating ahead): when keeping existing chunks they stay the server's, so the batch must
     * complete without lighting, spawning mobs in or saving them. Minecraft's spawn step reads the
     * chunk itself only for some chunks (where the corner biome lookup lands, whether a mob is
     * tried), so a 5x5 area is held.
     */
    private static void checkHeldDuringBatch(MinecraftServer server, ServerLevel level, IOWorker io,
                                             List<DynamicBatchPreGen> tasks) throws Exception {
        PreGenSaveFile.delete(level);
        int center = -400_000;
        int minChunk = Math.floorDiv(center - 500, 16);
        ChunkPos held = new ChunkPos(minChunk + 31, minChunk + 31);
        int heldRadius = 2;
        int heldChunks = (2 * heldRadius + 1) * (2 * heldRadius + 1);
        BlockPos build = new BlockPos(held.getMinBlockX() + 8, 300, held.getMinBlockZ() + 8);
        require(io.loadAsync(held).get(10, TimeUnit.SECONDS).isEmpty(), "The held-during-batch chunk must not exist yet");
        DynamicBatchPreGen task = new DynamicBatchPreGen(level, 1, 1000, 4, center, center, System.out::println);
        synchronized (tasks) {
            tasks.add(task);
        }
        task.setSkipExistingChunks(true);
        task.setReleaseMemoryEachBatch(false);
        java.util.concurrent.CompletableFuture<Void> run = java.util.concurrent.CompletableFuture.runAsync(task::start);
        try {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
            while (!String.valueOf(task.getCurrentStageName()).startsWith("Stage 2")) {
                require(System.nanoTime() < deadline && !run.isDone(), "The batch must reach its terrain stage");
                Thread.sleep(10);
            }
            server.submit(() -> {
                level.getChunkSource().addRegionTicket(net.minecraft.server.level.TicketType.FORCED, held, heldRadius, held);
                for (int x = -heldRadius; x <= heldRadius; x++) {
                    for (int z = -heldRadius; z <= heldRadius; z++) level.getChunk(held.x + x, held.z + z);
                }
                level.setBlock(build, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
                return null;
            }).get(120, TimeUnit.SECONDS);
            String stageWhenHeld = task.getCurrentStageName();
            require(stageWhenHeld.startsWith("Stage 2") || stageWhenHeld.startsWith("Stage 3") || stageWhenHeld.startsWith("Stage 4"),
                    "The server must finish the chunk before the batch lights it (inconclusive otherwise): " + stageWhenHeld);
            run.get(10, TimeUnit.MINUTES);
            int total = task.getTotalWorldChunks();
            require("Completed".equals(task.getCurrentStageName()), "A run with a chunk finished by the server meanwhile must complete");
            int written = ((AtomicInteger) field(task, "writtenChunks")).get();
            require(written == total - heldChunks, "All chunks but those the server finished must be written: " + written + "/" + total);
            int kept = ((AtomicInteger) field(task, "keptExistingChunks")).get();
            require(kept == heldChunks, "The chunks the server finished must count as kept: " + kept);
            require(server.submit(() -> level.getBlockState(build).is(Blocks.DIAMOND_BLOCK)).get(10, TimeUnit.SECONDS),
                    "A chunk the server finished must keep its blocks");
            System.out.printf(java.util.Locale.ROOT, "HELD_DURING_BATCH_CHECK PASS chunks=%d written=%d held_at=%s%n",
                    total, written, stageWhenHeld);
        } finally {
            server.submit(() -> {
                level.getChunkSource().removeRegionTicket(net.minecraft.server.level.TicketType.FORCED, held, heldRadius, held);
                return null;
            }).get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Surface reads a neighbor's stored biomes when that neighbor is ready and samples the biome
     * source otherwise, so generation order must not matter: every stored biome must equal the
     * sampled one at the same quart.
     */
    private static void checkStoredBiomesMatchSampling(ServerLevel level) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        InMemoryChunkGrid grid = new InMemoryChunkGrid(level, 2_000 * 16, -3_000 * 16, 6, 6, true, true);
        InMemoryChunkGrid.setCurrentGrid(grid);
        long compared = 0;
        try {
            grid.executeLayer(ChunkLayer.STRUCTURES, pool, null);
            grid.executeLayer(ChunkLayer.TERRAIN_PIPELINE, pool, null);
            for (int cx = 2_000 - 1; cx <= 2_000 + 6; cx++) {
                for (int cz = -3_000 - 1; cz <= -3_000 + 6; cz++) {
                    var chunk = grid.getChunk(cx, cz);
                    int minQuartY = net.minecraft.core.QuartPos.fromBlock(chunk.getMinBuildHeight());
                    int quartsY = net.minecraft.core.QuartPos.fromBlock(chunk.getHeight());
                    for (int qx = cx * 4; qx < cx * 4 + 4; qx++) {
                        for (int qz = cz * 4; qz < cz * 4 + 4; qz++) {
                            for (int qy = minQuartY; qy < minQuartY + quartsY; qy++) {
                                var stored = chunk.getNoiseBiome(qx, qy, qz);
                                var sampled = level.getUncachedNoiseBiome(qx, qy, qz);
                                require(stored.equals(sampled), "Stored biome " + stored + " != sampled " + sampled
                                        + " at quart " + qx + ", " + qy + ", " + qz);
                                compared++;
                            }
                        }
                    }
                }
            }
        } finally {
            grid.dispose();
            InMemoryChunkGrid.setCurrentGrid(null);
            pool.shutdownNow();
        }
        System.out.println("STORED_BIOME_CHECK PASS quarts=" + compared);
    }

    /**
     * Terrain around a village (structure pieces feed the beardifier) must be identical, block
     * for block and in its heightmaps, with the cell-skipping fill and with Minecraft's own.
     */
    private static void checkFastFillParity(MinecraftServer server, ServerLevel level) throws Exception {
        BlockPos village = server.submit(() -> level.findNearestMapStructure(
                net.minecraft.tags.StructureTags.VILLAGE, level.getSharedSpawnPos(), 100, false)).get(60, TimeUnit.SECONDS);
        require(village != null, "A village must exist near spawn for the beardifier parity check");
        ChunkPos center = new ChunkPos(village);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            long beardBefore = com.example.pregen.terrain.DensityBounds.STRUCTURE_CHUNKS.sum();
            long skippedBefore = com.example.pregen.terrain.FastNoiseFill.AIR_CELLS.sum()
                    + com.example.pregen.terrain.FastNoiseFill.SOLID_CELLS.sum();
            com.example.pregen.terrain.FastNoiseFill.enabled = false;
            String vanilla = terrainHash(level, center, pool, false);
            com.example.pregen.terrain.FastNoiseFill.enabled = true;
            String fast = terrainHash(level, center, pool, false);
            long beardChunks = com.example.pregen.terrain.DensityBounds.STRUCTURE_CHUNKS.sum() - beardBefore;
            long skipped = com.example.pregen.terrain.FastNoiseFill.AIR_CELLS.sum()
                    + com.example.pregen.terrain.FastNoiseFill.SOLID_CELLS.sum() - skippedBefore;
            require(beardChunks > 0, "The village tile must include chunks with structure pieces");
            require(skipped > 0, "The fast fill must have decided cells");
            require(vanilla.equals(fast), "Fast fill terrain differs from Minecraft's: " + vanilla + " != " + fast);
            System.out.printf(java.util.Locale.ROOT,
                    "FAST_FILL_PARITY_CHECK PASS village=%s structure_chunks=%d skipped_cells=%d hash=%s%n",
                    center, beardChunks, skipped, fast);
            // Features: neighbor-ordered scheduling must equal the barrier-separated phases.
            InMemoryChunkGrid.featuresByNeighbors = false;
            String phased = terrainHash(level, center, pool, true);
            InMemoryChunkGrid.featuresByNeighbors = true;
            String byNeighbors = terrainHash(level, center, pool, true);
            String repeated = terrainHash(level, center, pool, true);
            require(byNeighbors.equals(repeated), "Neighbor-ordered features must be deterministic");
            require(phased.equals(byNeighbors), "Neighbor-ordered features differ from phases: " + phased + " != " + byNeighbors);
            System.out.println("FEATURE_ORDER_PARITY_CHECK PASS hash=" + byNeighbors);
            // terrain_pipeline=vanilla: Minecraft's own ChunkStatus tasks on the same grid.
            String perStatus = terrainHash(level, center, pool, true, false);
            require(perStatus.equals(byNeighbors), "Minecraft's per-status terrain tasks differ from the fused path: "
                    + perStatus + " != " + byNeighbors);
            long surfaceChunks = com.example.pregen.terrain.SurfaceBiomes.READ_CHUNKS.sum();
            require(surfaceChunks > 0, "The fused path must have run the surface rule shortcuts");
            System.out.println("VANILLA_PIPELINE_PARITY_CHECK PASS hash=" + perStatus + " surface_shortcut_chunks=" + surfaceChunks);
        } finally {
            com.example.pregen.terrain.FastNoiseFill.enabled = true;
            InMemoryChunkGrid.featuresByNeighbors = true;
            pool.shutdownNow();
        }
    }

    private static String terrainHash(ServerLevel level, ChunkPos center, ExecutorService pool, boolean features)
            throws Exception {
        return terrainHash(level, center, pool, features, true);
    }

    private static String terrainHash(ServerLevel level, ChunkPos center, ExecutorService pool, boolean features,
                                      boolean fused) throws Exception {
        InMemoryChunkGrid grid = new InMemoryChunkGrid(level, (center.x - 4) * 16, (center.z - 4) * 16, 8, 8, fused, true);
        InMemoryChunkGrid.setCurrentGrid(grid);
        try {
            grid.executeLayer(ChunkLayer.STRUCTURES, pool, null);
            grid.executeLayer(ChunkLayer.TERRAIN_PIPELINE, pool, null);
            if (features) {
                grid.executeLayer(ChunkLayer.CARVERS, pool, null);
                grid.executePhased(ChunkLayer.FEATURES, pool, null);
            }
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            for (long packed : grid.getCorePositions()) {
                var chunk = grid.getChunk(ChunkPos.getX(packed), ChunkPos.getZ(packed));
                for (var section : chunk.getSections()) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                digest.update(section.getBlockState(x, y, z).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            }
                        }
                    }
                }
                for (var type : net.minecraft.world.level.levelgen.Heightmap.Types.values()) {
                    if (!chunk.hasPrimedHeightmap(type)) continue;
                    for (long word : chunk.getOrCreateHeightmapUnprimed(type).getRawData()) {
                        digest.update(java.nio.ByteBuffer.allocate(8).putLong(word).array());
                    }
                }
                digest.update(java.util.Arrays.toString(chunk.getPostProcessing()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } finally {
            grid.dispose();
            InMemoryChunkGrid.setCurrentGrid(null);
        }
    }

    /**
     * The save file: a full start() records every saved chunk, a second start() of the same area
     * skips the whole tile, and a partly recorded tile keeps its recorded chunks on disk.
     */
    private static void checkSaveFileResume(MinecraftServer server, ServerLevel level, IOWorker io,
                                            List<DynamicBatchPreGen> tasks) throws Exception {
        PreGenSaveFile.delete(level);
        int centerX = 200_000;
        int centerZ = 200_000;
        DynamicBatchPreGen first = new DynamicBatchPreGen(level, 1, 1000, 4, centerX, centerZ, System.out::println);
        synchronized (tasks) {
            tasks.add(first);
        }
        first.setReleaseMemoryEachBatch(false);
        long started = System.nanoTime();
        first.start();
        double firstSeconds = (System.nanoTime() - started) / 1e9;
        int total = first.getTotalWorldChunks();
        require("Completed".equals(first.getCurrentStageName()), "First run must complete: " + first.getCurrentStageName());
        require(((AtomicInteger) field(first, "writtenChunks")).get() == total, "First run must write every chunk");

        PreGenSaveFile record = PreGenSaveFile.load(level);
        require(record.doneChunks() == total, "Save file must record every saved chunk: " + record.doneChunks());
        require(record.hasLastRun() && record.lastCompleted() && record.lastSizeBlocks() == 1000
                && record.lastCenterX() == centerX && record.lastCenterZ() == centerZ, "Save file must record the last run");

        DynamicBatchPreGen second = new DynamicBatchPreGen(level, 1, 1000, 4, centerX, centerZ, System.out::println);
        synchronized (tasks) {
            tasks.add(second);
        }
        started = System.nanoTime();
        second.start();
        double secondSeconds = (System.nanoTime() - started) / 1e9;
        require("Completed".equals(second.getCurrentStageName()), "Resumed run must complete");
        require(((AtomicInteger) field(second, "writtenChunks")).get() == 0, "Resumed run must not regenerate saved chunks");
        require(second.getResumedBatchesCount() == 1 && second.getResumedChunksCount() == total,
                "Resumed run must skip the whole recorded tile");

        // A tile recorded except for one chunk: the recorded chunks stay as stored.
        ChunkBatchPlan.Tile tile = new ChunkBatchPlan.Tile(14_000, 14_000, 2, 2);
        ChunkPos kept = new ChunkPos(tile.minChunkX(), tile.minChunkZ());
        ChunkPos missing = new ChunkPos(tile.minChunkX() + 1, tile.minChunkZ() + 1);
        CompoundTag marker = new CompoundTag();
        marker.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        marker.putInt("xPos", kept.x);
        marker.putInt("zPos", kept.z);
        marker.putString("Status", "minecraft:full");
        marker.putString("PartialBatchCheckMarker", MARKER);
        io.store(kept, marker).get(10, TimeUnit.SECONDS);
        io.synchronize(true).get(10, TimeUnit.SECONDS);
        List<ChunkPos> recorded = new ArrayList<>();
        it.unimi.dsi.fastutil.longs.LongSet done = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                ChunkPos pos = new ChunkPos(tile.minChunkX() + x, tile.minChunkZ() + z);
                if (pos.equals(missing)) continue;
                recorded.add(pos);
                done.add(pos.toLong());
            }
        }
        PreGenSaveFile partial = PreGenSaveFile.load(level);
        partial.markDone(recorded);
        DynamicBatchPreGen third = newTask(level, tile, tasks);
        setField(third, "saveFile", partial);
        invoke(third, "processBatch", new Class<?>[]{int.class, int.class, ChunkBatchPlan.Tile.class,
                it.unimi.dsi.fastutil.longs.LongSet.class}, 1, 1, tile, done);
        invoke(third, "awaitPendingWrites", new Class<?>[0]);
        require(((AtomicInteger) field(third, "writtenChunks")).get() == 1, "Only the unrecorded chunk must be written");
        require(io.loadAsync(kept).get(10, TimeUnit.SECONDS).orElseThrow().contains("PartialBatchCheckMarker"),
                "A recorded chunk must be kept as stored");
        require("minecraft:full".equals(io.loadAsync(missing).get(10, TimeUnit.SECONDS).orElseThrow().getString("Status")),
                "The unrecorded chunk must be generated");
        require(PreGenSaveFile.load(level).isDone(missing.x, missing.z), "The newly saved chunk must be recorded");
        System.out.printf(java.util.Locale.ROOT,
                "SAVE_FILE_RESUME_CHECK PASS chunks=%d first_run=%.1fs resumed_run=%.1fs partial_tile_written=1%n",
                total, firstSeconds, secondSeconds);
    }

    /**
     * The live crash: regenerating around spawn, where the tile holds full chunks, chunks the
     * server left at lower statuses, and full chunks whose neighbors are not full. The server
     * thread must keep answering throughout, and must still be able to bring every replaced
     * chunk to full afterwards.
     */
    private static void checkSpawnEdge(MinecraftServer server, ServerLevel level, List<DynamicBatchPreGen> tasks)
            throws Exception {
        ChunkPos spawn = new ChunkPos(server.submit(level::getSharedSpawnPos).get(10, TimeUnit.SECONDS));
        // Spawn chunks are full up to spawnChunkRadius (2 by default in 1.21) and partly generated
        // for 11 chunks beyond; the tile starts inside the full area and reaches past the rest.
        int spawnRadius = server.submit(() -> level.getGameRules().getInt(net.minecraft.world.level.GameRules.RULE_SPAWN_CHUNK_RADIUS))
                .get(10, TimeUnit.SECONDS);
        int offset = Math.max(0, spawnRadius - 1);
        ChunkBatchPlan.Tile tile = new ChunkBatchPlan.Tile(spawn.x + offset, spawn.z + offset, 16, 16);
        int[] held = server.submit(() -> {
            var map = (com.example.pregen.mixin.ChunkMapAccessor) level.getChunkSource().chunkMap;
            int full = 0;
            int partial = 0;
            for (int x = tile.minChunkX(); x < tile.minChunkX() + tile.chunksX(); x++) {
                for (int z = tile.minChunkZ(); z < tile.minChunkZ() + tile.chunksZ(); z++) {
                    if (level.getChunkSource().getChunkNow(x, z) != null) full++;
                    else if (map.pregen$getVisibleChunk(ChunkPos.asLong(x, z)) != null) partial++;
                }
            }
            return new int[]{full, partial};
        }).get(10, TimeUnit.SECONDS);
        require(held[0] > 0 && held[1] > 0,
                "The spawn-edge tile must hold both full and partially generated chunks: " + held[0] + "/" + held[1]);

        AtomicLong worstLatency = new AtomicLong();
        AtomicBoolean probing = new AtomicBoolean(true);
        Thread probe = new Thread(() -> {
            while (probing.get()) {
                long sent = System.nanoTime();
                try {
                    server.submit(() -> null).get(60, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    worstLatency.set(Long.MAX_VALUE);
                    return;
                }
                worstLatency.accumulateAndGet(System.nanoTime() - sent, Math::max);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }, "PartialBatchCheck-Probe");
        probe.setDaemon(true);
        probe.start();
        try {
            DynamicBatchPreGen task = newTask(level, tile, tasks);
            invoke(task, "processBatch", new Class<?>[]{int.class, int.class, ChunkBatchPlan.Tile.class}, 1, 1, tile);
            invoke(task, "awaitPendingWrites", new Class<?>[0]);
            require(((AtomicInteger) field(task, "writtenChunks")).get() == tile.totalChunks(),
                    "Every spawn-edge chunk must be written");
        } finally {
            probing.set(false);
            probe.join(5_000);
        }
        double worstSeconds = worstLatency.get() / 1e9;
        require(worstSeconds < 5.0, "The server thread stalled for " + worstSeconds + " s during regeneration");

        long started = System.nanoTime();
        server.submit(() -> {
            for (int x = tile.minChunkX(); x < tile.minChunkX() + tile.chunksX(); x++) {
                for (int z = tile.minChunkZ(); z < tile.minChunkZ() + tile.chunksZ(); z++) {
                    require(level.getChunk(x, z) != null, "Replaced chunk must load as full: " + x + ", " + z);
                }
            }
            return null;
        }).get(60, TimeUnit.SECONDS);
        // A replaced chunk must keep light: the open sky above its surface is lit 15 (one that lost
        // its light shows dark in game).
        List<String> dark = server.submit(() -> {
            List<String> unlit = new ArrayList<>();
            for (int x = tile.minChunkX(); x < tile.minChunkX() + tile.chunksX(); x++) {
                for (int z = tile.minChunkZ(); z < tile.minChunkZ() + tile.chunksZ(); z++) {
                    for (int[] column : new int[][]{{2, 2}, {13, 2}, {8, 8}, {2, 13}, {13, 13}}) {
                        int bx = x * 16 + column[0];
                        int bz = z * 16 + column[1];
                        int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, bx, bz);
                        if (level.getBrightness(net.minecraft.world.level.LightLayer.SKY, new BlockPos(bx, top, bz)) < 15) {
                            unlit.add(x + "," + z);
                            break;
                        }
                    }
                }
            }
            return unlit;
        }).get(60, TimeUnit.SECONDS);
        require(dark.isEmpty(), dark.size() + " replaced chunks are dark under open sky: "
                + dark.subList(0, Math.min(10, dark.size())));
        requireCavesDark(server, level, tile);
        System.out.printf(java.util.Locale.ROOT,
                "SPAWN_EDGE_REGENERATION_CHECK PASS full_held=%d partial_held=%d worst_server_latency=%.3fs reload_full=%.1fs caves_dark=true%n",
                held[0], held[1], worstSeconds, (System.nanoTime() - started) / 1e9);
    }

    /**
     * Opaque blocks well below the surface hold no sky light. A chunk lit as if it had no blocks
     * (the light engine got none for it, as for replaced chunks the server held too early in
     * generation to report) has sky light 15 throughout, and its caves show bright in game.
     */
    private static void requireCavesDark(MinecraftServer server, ServerLevel level, ChunkBatchPlan.Tile tile) throws Exception {
        List<String> bright = server.submit(() -> {
            List<String> lit = new ArrayList<>();
            for (int x = tile.minChunkX(); x < tile.minChunkX() + tile.chunksX(); x++) {
                for (int z = tile.minChunkZ(); z < tile.minChunkZ() + tile.chunksZ(); z++) {
                    int opaque = 0;
                    int skyLit = 0;
                    for (int dx = 1; dx < 16; dx += 3) {
                        for (int dz = 1; dz < 16; dz += 3) {
                            int bx = x * 16 + dx;
                            int bz = z * 16 + dz;
                            int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, bx, bz);
                            for (int y = top - 60; y < top - 24; y += 4) {
                                BlockPos pos = new BlockPos(bx, y, bz);
                                if (!level.getBlockState(pos).canOcclude()) continue;
                                opaque++;
                                if (level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos) > 0) skyLit++;
                            }
                        }
                    }
                    if (opaque > 0 && skyLit * 4 > opaque) lit.add(x + "," + z + " (" + skyLit + "/" + opaque + ")");
                }
            }
            return lit;
        }).get(60, TimeUnit.SECONDS);
        require(bright.isEmpty(), bright.size() + " chunks hold sky light inside solid blocks underground (lit as if empty): "
                + bright.subList(0, Math.min(10, bright.size())));
    }

    /**
     * Whole /pregen runs of the same 1k area, alternating Minecraft's fill and the cell-skipping
     * fill, after one warm-up run; the save file is cleared so every run generates everything.
     */
    private static void pipelineBenchmark(ServerLevel level, List<DynamicBatchPreGen> tasks) throws Exception {
        int rounds = Integer.getInteger("pregen.pipelineBenchmark.rounds", 2);
        int threads = Integer.getInteger("pregen.pipelineBenchmark.threads", Runtime.getRuntime().availableProcessors());
        List<String> results = new ArrayList<>();
        for (int run = 0; run <= rounds * 2; run++) {
            boolean fast = run == 0 || run % 2 == 0;
            // Minecraft 1.21 always builds a chunk's surface after its neighbors' biomes, so only
            // the fill has a vanilla variant here.
            com.example.pregen.terrain.FastNoiseFill.enabled = fast;
            PreGenSaveFile.delete(level);
            DynamicBatchPreGen task = new DynamicBatchPreGen(level, 1, 1000, threads, 100_000, 100_000, message -> {});
            synchronized (tasks) {
                tasks.add(task);
            }
            task.setReleaseMemoryEachBatch(false);
            if (run == rounds * 2 && Boolean.getBoolean("pregen.pipelineBenchmark.profile")) task.enableProfiling();
            long started = System.nanoTime();
            task.start();
            double seconds = (System.nanoTime() - started) / 1e9;
            require("Completed".equals(task.getCurrentStageName()), "Benchmark run must complete");
            String line = String.format(java.util.Locale.ROOT, "%s %s %.1fs", run == 0 ? "warmup" : "run" + run,
                    fast ? "fast" : "vanilla", seconds);
            System.out.println("PIPELINE_BENCHMARK " + line);
            if (run > 0) results.add(line);
        }
        com.example.pregen.terrain.FastNoiseFill.enabled = true;
        System.out.println("PIPELINE_BENCHMARK_DONE threads=" + threads + " " + results);
    }

    /**
     * One area generated with each batch size (release_memory_each_batch on, as by default),
     * after a warm-up run, recording time and heap: the peak in use, and the peak left after
     * collections (close to what the batch really needs).
     */
    private static void batchBenchmark(ServerLevel level, List<DynamicBatchPreGen> tasks) throws Exception {
        int worldK = Integer.getInteger("pregen.batchBenchmark.worldK", 3);
        int threads = Integer.getInteger("pregen.batchBenchmark.threads", Runtime.getRuntime().availableProcessors());
        List<Integer> sizes = new ArrayList<>();
        for (String size : System.getProperty("pregen.batchBenchmark").split(",")) sizes.add(Integer.parseInt(size.trim()));
        List<String> results = new ArrayList<>();
        results.add(String.format(java.util.Locale.ROOT, "warmup %s", runBatch(level, tasks, 1, 1000, threads, 0)));
        for (int size : sizes) {
            results.add(String.format(java.util.Locale.ROOT, "batch=%d %s", size, runBatch(level, tasks, worldK, size, threads, 1)));
            System.out.println("BATCH_BENCHMARK " + results.get(results.size() - 1));
        }
        System.out.println("BATCH_BENCHMARK_DONE worldK=" + worldK + " threads=" + threads + " maxHeap="
                + Runtime.getRuntime().maxMemory() / (1024 * 1024) + "MB");
        for (String result : results) System.out.println("BATCH_BENCHMARK_RESULT " + result);
    }

    private static String runBatch(ServerLevel level, List<DynamicBatchPreGen> tasks, int worldK, int batch,
                                   int threads, int area) throws Exception {
        PreGenSaveFile.delete(level);
        System.gc();
        java.util.List<java.lang.management.MemoryPoolMXBean> pools = java.lang.management.ManagementFactory.getMemoryPoolMXBeans();
        AtomicLong peakUsed = new AtomicLong();
        AtomicLong peakAfterGc = new AtomicLong();
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread sampler = new Thread(() -> {
            Runtime runtime = Runtime.getRuntime();
            while (sampling.get()) {
                peakUsed.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), Math::max);
                long afterGc = 0;
                for (var pool : pools) {
                    var usage = pool.getCollectionUsage();
                    if (usage != null && pool.getType() == java.lang.management.MemoryType.HEAP) afterGc += usage.getUsed();
                }
                peakAfterGc.accumulateAndGet(afterGc, Math::max);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }, "BatchBenchmark-Heap");
        sampler.setDaemon(true);
        sampler.start();
        int center = 300_000 + area * 10_000;
        DynamicBatchPreGen task = new DynamicBatchPreGen(level, worldK, batch, threads, center, center, message -> {});
        synchronized (tasks) {
            tasks.add(task);
        }
        task.setReleaseMemoryEachBatch(true);
        task.start();
        sampling.set(false);
        sampler.join(1000);
        require("Completed".equals(task.getCurrentStageName()), "Benchmark run must complete: " + task.getCurrentStageName());
        double seconds = task.getElapsedMillis() / 1000.0;
        return String.format(java.util.Locale.ROOT, "batches=%d chunks=%d time=%.1fs speed=%.1f chunks/s peak_heap=%dMB peak_live=%dMB",
                task.getTotalBatchesCount(), task.getTotalWorldChunks(), seconds, task.getTotalWorldChunks() / seconds,
                peakUsed.get() / (1024 * 1024), peakAfterGc.get() / (1024 * 1024));
    }

    private static int fullNeighbors(ServerLevel level, ChunkPos center) {
        int full = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx != 0 || dz != 0) && level.getChunkSource().getChunkNow(center.x + dx, center.z + dz) != null) full++;
            }
        }
        return full;
    }

    private static DynamicBatchPreGen newTask(ServerLevel level, ChunkBatchPlan.Tile tile,
                                              List<DynamicBatchPreGen> tasks) throws ReflectiveOperationException {
        DynamicBatchPreGen task = new DynamicBatchPreGen(level, 1, 32, 2,
                tile.minBlockX(), tile.minBlockZ(), System.out::println);
        synchronized (tasks) {
            tasks.add(task);
        }
        setField(task, "chunkIO", WorldChunkIO.forLevel(level));
        setField(task, "fusedTerrain", true);
        setField(task, "currentBatchIndex", 1);
        task.setReleaseMemoryEachBatch(false);
        return task;
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw failure;
        }
    }

    /** A stored chunk without the fields that change with game time alone. */
    private static CompoundTag withoutTimes(CompoundTag chunk) {
        CompoundTag copy = chunk.copy();
        for (String key : new String[]{"LastUpdate", "InhabitedTime", "block_ticks", "fluid_ticks"}) copy.remove(key);
        return copy;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
