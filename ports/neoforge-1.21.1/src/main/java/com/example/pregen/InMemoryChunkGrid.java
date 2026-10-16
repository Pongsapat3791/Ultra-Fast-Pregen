package com.example.pregen;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import com.example.pregen.mixin.NoiseBasedChunkGeneratorInvoker;
import com.example.pregen.mixin.ThreadedLevelLightEngineInvoker;
import com.example.pregen.terrain.TerrainGenerationContext;
import com.example.pregen.terrain.FreshChunkBlending;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.HashMap;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import java.util.List;
import java.util.function.Function;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.LongAdder;

/**
 * High-performance 100% In-Memory chunk grid holding proto-chunks for a single tile batch.
 * Applies world generation layers sequentially across RAM, resolves race-conditions via
 * 4-phase checkerboard dispatching, and encodes completed core chunks for saving in one pass.
 *
 * Layout around the core (the chunks that are saved): a one-chunk terrain halo that core
 * features may write into, then an outer ring that only receives structure starts, so every
 * chunk that generates terrain can reference starts up to eight chunks away, as vanilla's
 * STRUCTURE_REFERENCES step does. The first chunk of that ring also gets biomes: Minecraft
 * 1.21 builds a chunk's noise and surface only once its eight neighbors have biomes.
 *
 * Every grid chunk has a {@link GridHolder}, so Minecraft's own ChunkStep tasks and
 * WorldGenRegion read the grid exactly as they read the server's chunk holders.
 */
public class InMemoryChunkGrid {
    /** Terrain is generated for the core plus this halo; core features may write into it. */
    static final int TERRAIN_HALO = 1;
    /** Vanilla STRUCTURE_REFERENCES range: starts up to 8 chunks away can reach a chunk. */
    static final int STRUCTURE_REFERENCE_RANGE = 8;
    static final int PADDING = TERRAIN_HALO + STRUCTURE_REFERENCE_RANGE;

    private static volatile InMemoryChunkGrid currentGrid = null;
    /** Every grid not disposed yet (a batch's grid stays while it is lit and saved). */
    private static final java.util.Set<InMemoryChunkGrid> activeGrids = ConcurrentHashMap.newKeySet();

    public static InMemoryChunkGrid getCurrentGrid() {
        return currentGrid;
    }

    public static void setCurrentGrid(InMemoryChunkGrid grid) {
        currentGrid = grid;
        if (grid != null) activeGrids.add(grid);
    }

    /** The active grid whose own chunks this region cache holds, or null (see ownsRegionCache). */
    public static InMemoryChunkGrid owning(net.minecraft.world.level.Level level, StaticCache2D<GenerationChunkHolder> cache,
                                           ChunkPos center) {
        for (InMemoryChunkGrid grid : activeGrids) {
            if (grid.level == level && grid.ownsRegionCache(cache, center)) return grid;
        }
        return null;
    }

    /**
     * For the server's light engine (ServerChunkCache#getChunkForLighting): a grid chunk while its
     * grid lights, or null for the server's answer. The light engine reads blocks only through
     * that lookup, which knows only chunks the server holds, so without this it lights the grid
     * as empty (caves at sky light 15, no light from lava). A position the server holds keeps the
     * server's chunk unless this batch replaces that chunk (see setLightOwnership).
     */
    public static net.minecraft.world.level.chunk.LightChunk chunkForLighting(net.minecraft.world.level.Level level,
                                                                          int chunkX, int chunkZ,
                                                                          net.minecraft.world.level.chunk.LightChunk held) {
        for (InMemoryChunkGrid grid : activeGrids) {
            if (!grid.lighting || grid.level != level || !grid.isTerrain(chunkX, chunkZ)) continue;
            long pos = ChunkPos.asLong(chunkX, chunkZ);
            if (held != null ? !grid.lightReplaced.contains(pos) : grid.lightHeld.contains(pos)) return null;
            return grid.getChunk(chunkX, chunkZ);
        }
        return null;
    }

    /** A thread of this mod's worker pool (see chunkForWorker). */
    public static final class WorkerThread extends Thread {
        private final net.minecraft.world.phys.shapes.VoxelShape[] fullShapeKeys = new net.minecraft.world.phys.shapes.VoxelShape[1024];
        private final boolean[] fullShapeValues = new boolean[1024];

        public WorkerThread(Runnable task, String name) {
            super(task, name);
        }

        /**
         * Block.isShapeFullBlock without its shared cache. That cache takes a lock on every miss,
         * and it misses all the time for shapes made on each call (offset blocks such as pointed
         * dripstone), so glow lichen spreading on many workers queued on it.
         */
        public boolean isShapeFullBlock(net.minecraft.world.phys.shapes.VoxelShape shape) {
            int i = System.identityHashCode(shape) & (fullShapeKeys.length - 1);
            if (fullShapeKeys[i] == shape) return fullShapeValues[i];
            boolean full = !net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(
                    net.minecraft.world.phys.shapes.Shapes.block(), shape, net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME);
            fullShapeKeys[i] = shape;
            fullShapeValues[i] = full;
            return full;
        }
    }

    /**
     * For this mod's workers: a grid chunk at least at {@code status}, or null. Feature code
     * sometimes asks the level itself for a chunk (a cat spawned in a village checks for a witch
     * hut through the level's structure manager). The server would then generate that chunk
     * again on its main thread up to the status, while the worker waits; the grid already holds
     * it with the same structure data.
     */
    public static ChunkAccess chunkForWorker(net.minecraft.world.level.Level level, int chunkX, int chunkZ, ChunkStatus status) {
        if (!(Thread.currentThread() instanceof WorkerThread)) return null;
        for (InMemoryChunkGrid grid : activeGrids) {
            if (grid.level != level) continue;
            ProtoChunk chunk = grid.getChunk(chunkX, chunkZ);
            if (chunk != null && chunk.getPersistedStatus().isOrAfter(status)) return chunk;
        }
        return null;
    }

    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final StructureTemplateManager templateManager;
    private final ThreadedLevelLightEngine lightEngine;
    private final WorldGenContext context;
    private final boolean fusedTerrain;
    private final boolean parallelSteps;
    /** Off when other mods keep state in feature placement (WorldgenCompat#featurePlacementMixins). */
    private boolean parallelFeatures;

    private final int coreMinChunkX;
    private final int coreMinChunkZ;
    private final int coreChunksX;
    private final int coreChunksZ;

    private final int gridMinChunkX;
    private final int gridMinChunkZ;
    private final int gridChunksX;
    private final int gridChunksZ;

    private ProtoChunk[][] chunks;
    /** While lighting runs (see chunkForLighting). */
    private volatile boolean lighting;
    /** Terrain positions the server held when lighting started, and those of them this batch replaces. */
    private volatile LongSet lightHeld = LongSets.EMPTY_SET;
    private volatile LongSet lightReplaced = LongSets.EMPTY_SET;
    private GridHolder[][] holders;
    private final Map<Long, ProtoChunk> fallbackChunks = new ConcurrentHashMap<>();
    private final LongAdder skippedBlendingScans = new LongAdder();
    private final LongAdder terrainSetupNanos = new LongAdder();
    private final LongAdder biomeNanos = new LongAdder();
    private final LongAdder noiseNanos = new LongAdder();
    private final LongAdder surfaceNanos = new LongAdder();
    private final LongAdder terrainChunks = new LongAdder();
    private final LongAdder encodeNanos = new LongAdder();
    private final LongAdder encodedChunks = new LongAdder();
    private final LongAdder spawnedEntities = new LongAdder();

    public ServerLevel getLevel() {
        return level;
    }

    public boolean isInsideGrid(int chunkX, int chunkZ) {
        int gx = chunkX - gridMinChunkX;
        int gz = chunkZ - gridMinChunkZ;
        return gx >= 0 && gx < gridChunksX && gz >= 0 && gz < gridChunksZ;
    }

    /**
     * A grid chunk as Minecraft's generation code sees a server chunk holder. It has reached a
     * status exactly when its proto chunk has, as a server holder's status futures complete.
     */
    static final class GridHolder extends GenerationChunkHolder {
        private final InMemoryChunkGrid grid;
        private final ProtoChunk chunk;

        GridHolder(InMemoryChunkGrid grid, ProtoChunk chunk) {
            super(chunk.getPos());
            this.grid = grid;
            this.chunk = chunk;
        }

        @Override
        public int getTicketLevel() {
            return 0;
        }

        @Override
        public int getQueueLevel() {
            return 0;
        }

        @Override
        public ChunkAccess getChunkIfPresentUnchecked(ChunkStatus status) {
            return chunk.getPersistedStatus().isOrAfter(status) ? chunk : null;
        }

        @Override
        public ChunkAccess getChunkIfPresent(ChunkStatus status) {
            return getChunkIfPresentUnchecked(status);
        }

        @Override
        public ChunkAccess getLatestChunk() {
            return chunk;
        }

        @Override
        public ChunkStatus getPersistedStatus() {
            return chunk.getPersistedStatus();
        }
    }

    /** Live server generation may run at the same coordinates; only redirect our own regions. */
    public boolean ownsRegionCache(StaticCache2D<GenerationChunkHolder> cache, ChunkPos center) {
        return chunks != null && cache.contains(center.x, center.z)
                && cache.get(center.x, center.z) instanceof GridHolder holder && holder.grid == this;
    }

    /**
     * Vanilla's old-chunk query scans whole disk regions on a cache miss. This region
     * reads only our freshly allocated chunks, so that scan cannot provide blending
     * data. Check object ownership as well as coordinates to leave live-world regions
     * alone, and retain vanilla behavior if another mod supplies blending data.
     */
    public boolean canSkipBlendingScan(StaticCache2D<GenerationChunkHolder> cache, ChunkPos center, int radius) {
        if (!ownsRegionCache(cache, center)) return false;
        List<ChunkAccess> regionCache = new ArrayList<>();
        cache.forEach(holder -> regionCache.add(holder.getLatestChunk()));
        boolean fresh = FreshChunkBlending.canSkip(regionCache, center, radius, (x, z) -> {
            ChunkAccess chunk = getChunk(x, z);
            return chunk != null ? chunk : fallbackChunks.get(ChunkPos.asLong(x, z));
        });
        if (fresh) skippedBlendingScans.increment();
        return fresh;
    }

    public String getTerrainDiagnostics() {
        long count = terrainChunks.sum();
        if (count == 0) return "Stage 2 used Minecraft's per-status pipeline | Skipped disk blending queries: " + skippedBlendingScans.sum();
        double divisor = count * 1_000_000.0;
        return String.format(java.util.Locale.ROOT,
                "Stage 2 worker time/chunk: setup=%.2fms, biomes=%.2fms, noise=%.2fms, surface=%.2fms | Skipped disk blending queries: %d",
                terrainSetupNanos.sum() / divisor, biomeNanos.sum() / divisor,
                noiseNanos.sum() / divisor, surfaceNanos.sum() / divisor, skippedBlendingScans.sum());
    }

    public ChunkAccess getChunkOrFallback(int worldChunkX, int worldChunkZ) {
        ProtoChunk c = getChunk(worldChunkX, worldChunkZ);
        if (c != null) {
            return c;
        }
        long key = ChunkPos.asLong(worldChunkX, worldChunkZ);
        return fallbackChunks.computeIfAbsent(key, k -> {
            ProtoChunk fallback = new ProtoChunk(
                new ChunkPos(worldChunkX, worldChunkZ),
                UpgradeData.EMPTY,
                level,
                level.registryAccess().registryOrThrow(Registries.BIOME),
                null
            );
            fallback.setPersistedStatus(ChunkStatus.FULL);
            return fallback;
        });
    }

    public InMemoryChunkGrid(ServerLevel level, int minBlockX, int minBlockZ, int coreChunksX, int coreChunksZ,
                             boolean fusedTerrain, boolean parallelSteps) {
        this.level = level;
        this.generator = level.getChunkSource().getGenerator();
        this.templateManager = level.getStructureManager();
        this.lightEngine = level.getChunkSource().getLightEngine();
        // The main-thread mailbox serves only the FULL step, which this grid never runs.
        this.context = new WorldGenContext(level, generator, templateManager, lightEngine, null);
        this.fusedTerrain = fusedTerrain;
        this.parallelSteps = parallelSteps;
        this.parallelFeatures = parallelSteps;

        this.coreMinChunkX = Math.floorDiv(minBlockX, 16);
        this.coreMinChunkZ = Math.floorDiv(minBlockZ, 16);
        this.coreChunksX = coreChunksX;
        this.coreChunksZ = coreChunksZ;

        this.gridMinChunkX = this.coreMinChunkX - PADDING;
        this.gridMinChunkZ = this.coreMinChunkZ - PADDING;
        this.gridChunksX = this.coreChunksX + (2 * PADDING);
        this.gridChunksZ = this.coreChunksZ + (2 * PADDING);

        this.chunks = new ProtoChunk[gridChunksX][gridChunksZ];
        this.holders = new GridHolder[gridChunksX][gridChunksZ];

        var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);

        // Pre-allocate all ProtoChunks in RAM
        for (int gx = 0; gx < gridChunksX; gx++) {
            for (int gz = 0; gz < gridChunksZ; gz++) {
                int worldChunkX = gridMinChunkX + gx;
                int worldChunkZ = gridMinChunkZ + gz;
                chunks[gx][gz] = new ProtoChunk(
                    new ChunkPos(worldChunkX, worldChunkZ),
                    UpgradeData.EMPTY,
                    level,
                    biomeRegistry,
                    null
                );
                holders[gx][gz] = new GridHolder(this, chunks[gx][gz]);
            }
        }
    }

    public ProtoChunk getChunk(int worldChunkX, int worldChunkZ) {
        int gx = worldChunkX - gridMinChunkX;
        int gz = worldChunkZ - gridMinChunkZ;
        if (gx >= 0 && gx < gridChunksX && gz >= 0 && gz < gridChunksZ && chunks != null) {
            return chunks[gx][gz];
        }
        return null;
    }

    /**
     * The square neighborhood of grid chunk holders around (worldChunkX, worldChunkZ), as the
     * server passes a generation step. Chunks further away (up to the grid edge) are still
     * readable through WorldGenRegionMixin.
     */
    public StaticCache2D<GenerationChunkHolder> getNeighborhoodCache(int worldChunkX, int worldChunkZ, int radius) {
        return StaticCache2D.create(worldChunkX, worldChunkZ, radius, (cx, cz) -> {
            int gx = cx - gridMinChunkX;
            int gz = cz - gridMinChunkZ;
            if (holders == null || gx < 0 || gx >= gridChunksX || gz < 0 || gz >= gridChunksZ) {
                throw new IllegalStateException(String.format("Missing neighbor chunk at [%d, %d] in grid cache", cx, cz));
            }
            return holders[gx][gz];
        });
    }

    /** Minecraft's own step for {@code status} on one grid chunk, as the server applies it. */
    private CompletableFuture<ChunkAccess> generate(ChunkStatus status, ChunkAccess chunk, int radius) {
        ChunkPos pos = chunk.getPos();
        return ChunkPyramid.GENERATION_PYRAMID.getStepTo(status)
                .apply(context, getNeighborhoodCache(pos.x, pos.z, radius), chunk);
    }

    public int getGridTotalChunks() {
        return gridChunksX * gridChunksZ;
    }

    /** Core chunks plus the terrain halo. */
    public int getPaddedChunksCount() {
        return (coreChunksX + 2 * TERRAIN_HALO) * (coreChunksZ + 2 * TERRAIN_HALO);
    }

    /** Stage 1 progress: one start per grid chunk plus one reference pass per terrain chunk. */
    public int getStructureStageChunks() {
        return getGridTotalChunks() + getPaddedChunksCount();
    }

    public int getCoreTotalChunks() {
        return coreChunksX * coreChunksZ;
    }

    /** Packed positions of the core chunks, i.e. the chunks this tile may save. */
    public long[] getCorePositions() {
        long[] positions = new long[coreChunksX * coreChunksZ];
        int index = 0;
        for (int cx = coreMinChunkX; cx < coreMinChunkX + coreChunksX; cx++) {
            for (int cz = coreMinChunkZ; cz < coreMinChunkZ + coreChunksZ; cz++) {
                positions[index++] = ChunkPos.asLong(cx, cz);
            }
        }
        return positions;
    }

    /**
     * STRUCTURE_STARTS and every "simple" status task (references, carvers, features, spawn) do
     * their work inside generate() and return a finished future, ignoring the executor. Called
     * in a loop they all run on the calling thread, so the call itself is moved onto the pool.
     */
    public void setParallelFeatures(boolean parallel) {
        parallelFeatures = parallel && parallelSteps;
    }

    private CompletableFuture<?> generateOnPool(ChunkStatus status, ExecutorService workerPool, ChunkAccess chunk, int radius) {
        return generateOnPool(status, workerPool, chunk, radius, parallelSteps);
    }

    private CompletableFuture<?> generateOnPool(ChunkStatus status, ExecutorService workerPool, ChunkAccess chunk, int radius,
                                                boolean parallel) {
        if (!parallel) {
            return generate(status, chunk, radius);
        }
        return CompletableFuture.supplyAsync(() -> generate(status, chunk, radius), workerPool)
                .thenCompose(future -> future);
    }

    /**
     * Executes fully parallel independent layers asynchronously with real-time per-chunk callbacks.
     */
    public void executeLayer(ChunkLayer layer, ExecutorService workerPool, Runnable onChunkDone) {
        if (layer == ChunkLayer.STRUCTURES) {
            List<CompletableFuture<?>> futures = new ArrayList<>(gridChunksX * gridChunksZ);
            for (int gx = 0; gx < gridChunksX; gx++) {
                for (int gz = 0; gz < gridChunksZ; gz++) {
                    ProtoChunk chunk = chunks[gx][gz];
                    CompletableFuture<?> f = generateOnPool(ChunkStatus.STRUCTURE_STARTS, workerPool, chunk, 0);
                    if (onChunkDone != null) f = f.thenRun(onChunkDone);
                    futures.add(f);
                }
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Vanilla's STRUCTURE_REFERENCES step, which structure placement (features) and
            // terrain adaptation (beardifier) read. Each chunk writes only its own references
            // and reads starts up to 8 chunks away, all of which are inside the grid.
            futures.clear();
            for (int cz = terrainMinZ(); cz <= terrainMaxZ(); cz++) {
                for (int cx = terrainMinX(); cx <= terrainMaxX(); cx++) {
                    CompletableFuture<?> f = generateOnPool(ChunkStatus.STRUCTURE_REFERENCES, workerPool,
                            getChunk(cx, cz), STRUCTURE_REFERENCE_RANGE);
                    if (onChunkDone != null) f = f.thenRun(onChunkDone);
                    futures.add(f);
                }
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Outer-ring chunks only supply starts; give them the status later region lookups expect.
            for (int gx = 0; gx < gridChunksX; gx++) {
                for (int gz = 0; gz < gridChunksZ; gz++) {
                    if (!chunks[gx][gz].getPersistedStatus().isOrAfter(ChunkStatus.STRUCTURE_REFERENCES)) {
                        chunks[gx][gz].setPersistedStatus(ChunkStatus.STRUCTURE_REFERENCES);
                    }
                }
            }
            return;
        }

        // Core chunks plus the halo, each with one neighbor ring available in all directions
        int minX = terrainMinX();
        int maxX = terrainMaxX();
        int minZ = terrainMinZ();
        int maxZ = terrainMaxZ();

        if (layer == ChunkLayer.TERRAIN_PIPELINE) {
            List<CompletableFuture<?>> futures = new ArrayList<>((maxX - minX + 1) * (maxZ - minZ + 1));

            if (fusedTerrain && generator instanceof NoiseBasedChunkGenerator noiseGen) {
                NoiseBasedChunkGeneratorInvoker invoker = (NoiseBasedChunkGeneratorInvoker) (Object) noiseGen;
                RandomState randomState = level.getChunkSource().randomState();
                StructureManager structureManager = level.structureManager();
                NoiseSettings noiseSettings = noiseGen.generatorSettings().value().noiseSettings();

                // Minecraft 1.21 builds noise and surface only once the eight neighbors have
                // biomes (surface reads their stored biomes), so every chunk first computes its
                // biomes, including a ring just outside the terrain area, and its noise and
                // surface wait for the 3x3 neighbors' biomes. Each part reads the grid through a
                // region of its own step, as vanilla's tasks do; the noise chunk, blender and
                // structure view made for biomes serve all three, as in vanilla.
                int sizeX = maxX - minX + 3;
                int sizeZ = maxZ - minZ + 3;
                TerrainRegion[][] regions = new TerrainRegion[sizeX][sizeZ];
                CompletableFuture<?>[][] biomesDone = new CompletableFuture<?>[sizeX][sizeZ];
                for (int cz = minZ - 1; cz <= maxZ + 1; cz++) {
                    for (int cx = minX - 1; cx <= maxX + 1; cx++) {
                        final int x = cx;
                        final int z = cz;
                        ProtoChunk chunk = getChunk(x, z);
                        if (chunk == null) continue;
                        int ix = x - minX + 1;
                        int iz = z - minZ + 1;
                        biomesDone[ix][iz] = CompletableFuture.runAsync(() -> {
                            try (TerrainGenerationContext ignored = TerrainGenerationContext.enter()) {
                                long partStart = System.nanoTime();
                                WorldGenRegion region = new WorldGenRegion(level, getNeighborhoodCache(x, z, 1),
                                        ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.BIOMES), chunk);
                                TerrainRegion terrain = new TerrainRegion(Blender.of(region),
                                        structureManager.forWorldGenRegion(region));
                                regions[ix][iz] = terrain;
                                terrainSetupNanos.add(System.nanoTime() - partStart);

                                // 1. Biomes (also creates the chunk-local optimized aquifer)
                                partStart = System.nanoTime();
                                invoker.callDoCreateBiomes(terrain.blender(), randomState, terrain.structureManager(), chunk);
                                chunk.setPersistedStatus(ChunkStatus.BIOMES);
                                biomeNanos.add(System.nanoTime() - partStart);
                            }
                        }, workerPool);
                    }
                }
                for (int cz = minZ; cz <= maxZ; cz++) {
                    for (int cx = minX; cx <= maxX; cx++) {
                        ProtoChunk chunk = getChunk(cx, cz);
                        if (chunk == null) continue;
                        int ix = cx - minX + 1;
                        int iz = cz - minZ + 1;
                        List<CompletableFuture<?>> neighbors = new ArrayList<>(9);
                        for (int dz = -1; dz <= 1; dz++) {
                            for (int dx = -1; dx <= 1; dx++) {
                                if (biomesDone[ix + dx][iz + dz] != null) neighbors.add(biomesDone[ix + dx][iz + dz]);
                            }
                        }
                        CompletableFuture<?> f = CompletableFuture.allOf(neighbors.toArray(new CompletableFuture[0])).thenRunAsync(() -> {
                            try (TerrainGenerationContext ignored = TerrainGenerationContext.enter()) {
                                TerrainRegion terrain = regions[ix][iz];
                                NoiseSettings clamped = noiseSettings.clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
                                int minCellY = Mth.floorDiv(clamped.minY(), clamped.getCellHeight());
                                int cellCountY = Mth.floorDiv(clamped.height(), clamped.getCellHeight());

                                // 2. 3D Terrain Density Noise
                                long partStart = System.nanoTime();
                                invoker.callDoFill(terrain.blender(), terrain.structureManager(), randomState, chunk, minCellY, cellCountY);
                                chunk.setPersistedStatus(ChunkStatus.NOISE);
                                noiseNanos.add(System.nanoTime() - partStart);

                                // 3. Surface Rules
                                partStart = System.nanoTime();
                                WorldGenRegion surfaceRegion = new WorldGenRegion(level, getNeighborhoodCache(chunk.getPos().x,
                                        chunk.getPos().z, 1), ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.SURFACE), chunk);
                                noiseGen.buildSurface(surfaceRegion, terrain.structureManager(), randomState, chunk);
                                chunk.setPersistedStatus(ChunkStatus.SURFACE);
                                surfaceNanos.add(System.nanoTime() - partStart);
                                terrainChunks.increment();
                            }
                        }, workerPool);
                        futures.add(onChunkDone != null ? f.thenRun(onChunkDone) : f);
                    }
                }
            } else {
                // Minecraft's own status tasks, including every hook other mods place on them,
                // in the same order: biomes first (with the ring outside the terrain area), then
                // each chunk's noise and surface once its 3x3 neighbors have biomes.
                int sizeX = maxX - minX + 3;
                int sizeZ = maxZ - minZ + 3;
                CompletableFuture<?>[][] biomesDone = new CompletableFuture<?>[sizeX][sizeZ];
                for (int cz = minZ - 1; cz <= maxZ + 1; cz++) {
                    for (int cx = minX - 1; cx <= maxX + 1; cx++) {
                        biomesDone[cx - minX + 1][cz - minZ + 1] = generateOnPool(ChunkStatus.BIOMES, workerPool, getChunk(cx, cz), 1);
                    }
                }
                for (int cz = minZ; cz <= maxZ; cz++) {
                    for (int cx = minX; cx <= maxX; cx++) {
                        ProtoChunk chunk = getChunk(cx, cz);
                        int ix = cx - minX + 1;
                        int iz = cz - minZ + 1;
                        List<CompletableFuture<?>> neighbors = new ArrayList<>(9);
                        for (int dz = -1; dz <= 1; dz++) {
                            for (int dx = -1; dx <= 1; dx++) neighbors.add(biomesDone[ix + dx][iz + dz]);
                        }
                        CompletableFuture<?> f = CompletableFuture.allOf(neighbors.toArray(new CompletableFuture[0]))
                            .thenCompose(ignored -> generateOnPool(ChunkStatus.NOISE, workerPool, chunk, 1))
                            .thenCompose(ignored -> generateOnPool(ChunkStatus.SURFACE, workerPool, chunk, 1));
                        if (onChunkDone != null) f = f.thenRun(onChunkDone);
                        futures.add(f);
                    }
                }
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            return;
        }

        if (layer == ChunkLayer.CARVERS) {
            List<CompletableFuture<?>> futures = new ArrayList<>((maxX - minX + 1) * (maxZ - minZ + 1));
            for (int cz = minZ; cz <= maxZ; cz++) {
                for (int cx = minX; cx <= maxX; cx++) {
                    // Carvers write only the center chunk, so all chunks can run at once.
                    CompletableFuture<?> f = generateOnPool(ChunkStatus.CARVERS, workerPool, getChunk(cx, cz), 1);
                    if (onChunkDone != null) f = f.thenRun(onChunkDone);
                    futures.add(f);
                }
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            return;
        }

        throw new IllegalArgumentException("Layer " + layer + " must be executed with executePhased()");
    }

    /** Section block states of the terrain halo before features (see SeamWrites). */
    private Map<Long, PalettedContainer<BlockState>[]> haloSnapshot;

    private boolean isCore(int chunkX, int chunkZ) {
        return chunkX >= coreMinChunkX && chunkX < coreMinChunkX + coreChunksX
                && chunkZ >= coreMinChunkZ && chunkZ < coreMinChunkZ + coreChunksZ;
    }

    /**
     * Gives a chunk generated before (by this run or earlier, see SeamWrites) its final blocks
     * and heightmaps, so this batch's features find it as Minecraft finds a neighbor whose
     * features ran earlier. Heightmaps it was stored without are computed from its blocks.
     */
    public void adoptSaved(long packed, SeamWrites.Final saved) {
        ProtoChunk chunk = getChunk(ChunkPos.getX(packed), ChunkPos.getZ(packed));
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            sections[i] = new LevelChunkSection(SeamWrites.writableCopy(saved.states()[i]), sections[i].getBiomes());
        }
        // A neighbor whose features ran is still part-generated to Minecraft (it becomes full only
        // after its own neighbors' features), with the worldgen heightmaps it had then; this mod
        // stores those with the chunks it writes. A chunk stored without them (a full chunk of
        // the world) computes them from its blocks, as Minecraft does for a full chunk.
        java.util.EnumSet<Heightmap.Types> missing = java.util.EnumSet.noneOf(Heightmap.Types.class);
        for (Heightmap.Types type : Heightmap.Types.values()) {
            long[] data = saved.heightmaps().get(type);
            if (data != null) {
                chunk.setHeightmap(type, data.clone());
            } else {
                chunk.setHeightmap(type, new Heightmap(chunk, type).getRawData());
                missing.add(type);
            }
        }
        if (!missing.isEmpty()) Heightmap.primeHeightmaps(chunk, missing);
        // Writes merged in later reached the chunk after its features: Minecraft then updates
        // only the heightmaps a featured chunk keeps.
        for (SeamWrites.Write write : saved.merged()) {
            for (Heightmap.Types type : ChunkStatus.FEATURES.heightmapsAfter()) {
                chunk.getOrCreateHeightmapUnprimed(type).update(write.x() & 15, write.y(), write.z() & 15, write.after());
            }
        }
        // Its features ran: this batch's writes update the heightmaps a featured chunk keeps.
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
    }


    boolean isCore(long packed) {
        return isCore(ChunkPos.getX(packed), ChunkPos.getZ(packed));
    }

    /** Packed positions of the core chunks and the terrain halo. */
    public long[] getTerrainPositions() {
        long[] positions = new long[(terrainMaxX() - terrainMinX() + 1) * (terrainMaxZ() - terrainMinZ() + 1)];
        int index = 0;
        for (int cz = terrainMinZ(); cz <= terrainMaxZ(); cz++) {
            for (int cx = terrainMinX(); cx <= terrainMaxX(); cx++) positions[index++] = ChunkPos.asLong(cx, cz);
        }
        return positions;
    }

    /** Core chunks generated before: kept as they are, their features already ran. */
    private LongSet featureSkip = it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET;

    public void setFeatureSkip(LongSet chunks) {
        featureSkip = chunks;
    }

    /**
     * Saves halo chunks as Minecraft leaves a generated chunk's neighbors: part-generated
     * (status carvers) with the blocks other chunks' features placed in them.
     */
    public List<WorldChunkIO.EncodedChunk> encodePartGenerated(it.unimi.dsi.fastutil.longs.LongCollection positions,
                                                               ExecutorService workerPool) {
        RegionFileVersion version = RegionFileVersion.VERSION_DEFLATE;
        List<CompletableFuture<WorldChunkIO.EncodedChunk>> futures = new ArrayList<>(positions.size());
        for (long packed : positions) {
            ProtoChunk chunk = getChunk(ChunkPos.getX(packed), ChunkPos.getZ(packed));
            futures.add(CompletableFuture.supplyAsync(() -> {
                CompoundTag tag = ChunkSerializer.write(level, chunk);
                FramedChunkBuffer framed = new FramedChunkBuffer(version.getId());
                try (DataOutputStream stream = new DataOutputStream(version.wrap(framed))) {
                    NbtIo.write(tag, stream);
                } catch (IOException e) {
                    throw new UncheckedIOException(String.format("Failed to encode chunk [%d, %d]",
                            chunk.getPos().x, chunk.getPos().z), e);
                }
                return new WorldChunkIO.EncodedChunk(chunk.getPos(), framed.frame());
            }, workerPool));
        }
        List<WorldChunkIO.EncodedChunk> encoded = new ArrayList<>(futures.size());
        for (CompletableFuture<WorldChunkIO.EncodedChunk> future : futures) encoded.add(future.join());
        return encoded;
    }

    /**
     * Copies the block states of the halo and of the given core chunks (kept ones), so writes
     * from this batch's features can be found afterwards.
     */
    @SuppressWarnings("unchecked")
    public void snapshotHalo(LongSet keptCore) {
        haloSnapshot = new HashMap<>();
        for (int cz = terrainMinZ(); cz <= terrainMaxZ(); cz++) {
            for (int cx = terrainMinX(); cx <= terrainMaxX(); cx++) {
                if (isCore(cx, cz) && !keptCore.contains(ChunkPos.asLong(cx, cz))) continue;
                ProtoChunk chunk = getChunk(cx, cz);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                PalettedContainer<BlockState>[] copy = new PalettedContainer[sections.length];
                for (int i = 0; i < sections.length; i++) copy[i] = sections[i].getStates().copy();
                haloSnapshot.put(ChunkPos.asLong(cx, cz), copy);
            }
        }
    }

    /** The blocks the core's features changed in each halo chunk since snapshotHalo(). */
    public Map<Long, List<SeamWrites.Write>> collectHaloWrites(ExecutorService workerPool) {
        Map<Long, List<SeamWrites.Write>> writes = new ConcurrentHashMap<>();
        if (haloSnapshot == null) return writes;
        List<CompletableFuture<?>> futures = new ArrayList<>(haloSnapshot.size());
        for (Map.Entry<Long, PalettedContainer<BlockState>[]> entry : haloSnapshot.entrySet()) {
            futures.add(CompletableFuture.runAsync(() -> {
                List<SeamWrites.Write> changed = diffHalo(entry.getKey(), entry.getValue());
                if (!changed.isEmpty()) writes.put(entry.getKey(), changed);
            }, workerPool));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        haloSnapshot = null;
        return writes;
    }

    private List<SeamWrites.Write> diffHalo(long packed, PalettedContainer<BlockState>[] before) {
        ProtoChunk chunk = getChunk(ChunkPos.getX(packed), ChunkPos.getZ(packed));
        int minX = ChunkPos.getX(packed) << 4;
        int minZ = ChunkPos.getZ(packed) << 4;
        LevelChunkSection[] sections = chunk.getSections();
        List<SeamWrites.Write> writes = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < sections.length; i++) {
            PalettedContainer<BlockState> after = sections[i].getStates();
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState now = after.get(x, y, z);
                        BlockState was = before[i].get(x, y, z);
                        if (now == was) continue;
                        pos.set(minX + x, baseY + y, minZ + z);
                        CompoundTag blockEntity = now.hasBlockEntity() ? chunk.getBlockEntityNbtForSaving(pos, level.registryAccess()) : null;
                        writes.add(new SeamWrites.Write(minX + x, baseY + y, minZ + z, was, now, blockEntity));
                    }
                }
            }
        }
        return writes;
    }

    private int terrainMinX() {
        return coreMinChunkX - TERRAIN_HALO;
    }

    private int terrainMaxX() {
        return coreMinChunkX + coreChunksX - 1 + TERRAIN_HALO;
    }

    private int terrainMinZ() {
        return coreMinChunkZ - TERRAIN_HALO;
    }

    private int terrainMaxZ() {
        return coreMinChunkZ + coreChunksZ - 1 + TERRAIN_HALO;
    }

    /**
     * Executes inter-chunk dependent layers in checkerboard phases. Features write into every
     * neighbor (WorldGenRegion write radius 1), so chunks running together must be 3 apart for
     * their 3x3 write areas to be disjoint: 9 phases. Lighting runs on the light engine's own
     * thread, so its 4 phases only bound how much work is queued at once.
     */
    public void executePhased(ChunkLayer layer, ExecutorService workerPool, Runnable onChunkDone) {
        // Core chunk bounds
        int minX = coreMinChunkX;
        int maxX = coreMinChunkX + coreChunksX - 1;
        int minZ = coreMinChunkZ;
        int maxZ = coreMinChunkZ + coreChunksZ - 1;

        if (layer == ChunkLayer.LIGHTING) {
            executeLighting(workerPool, onChunkDone);
            return;
        }

        int spacing = layer == ChunkLayer.FEATURES ? 3 : 2;
        java.util.function.IntBinaryOperator groups = featureGroups;
        if (layer == ChunkLayer.FEATURES && groups != null) {
            executeFeaturesInGroups(groups, workerPool, onChunkDone);
            return;
        }
        if (layer == ChunkLayer.FEATURES && parallelFeatures && featuresByNeighbors) {
            executeFeaturesByNeighbors(workerPool, onChunkDone);
            return;
        }
        for (int phase = 0; phase < spacing * spacing; phase++) {
            List<CompletableFuture<?>> phaseFutures = new ArrayList<>();

            for (int cz = minZ; cz <= maxZ; cz++) {
                for (int cx = minX; cx <= maxX; cx++) {
                    if (phaseOf(cx, cz, spacing) != phase) continue;

                    ProtoChunk chunk = getChunk(cx, cz);
                    if (chunk == null) continue;

                    if (layer == ChunkLayer.FEATURES) {
                        if (featureSkip.contains(ChunkPos.asLong(cx, cz))) continue;
                        CompletableFuture<?> f = generateOnPool(ChunkStatus.FEATURES, workerPool, chunk, 1, parallelFeatures);
                        if (onChunkDone != null) f = f.thenRun(onChunkDone);
                        phaseFutures.add(f);
                    }
                }
            }

            CompletableFuture.allOf(phaseFutures.toArray(new CompletableFuture[0])).join();
        }
    }

    /**
     * Which server-held chunks inside the grid stay the server's while it lights (it lights those
     * itself) and which this batch replaces with its own (lit from the grid, so their light fits
     * the blocks they get; see LoadedChunkRegeneration).
     */
    public void setLightOwnership(LongSet heldByServer, LongSet replaced) {
        lightHeld = heldByServer;
        lightReplaced = replaced;
    }

    private boolean isTerrain(int chunkX, int chunkZ) {
        return chunkX >= terrainMinX() && chunkX <= terrainMaxX() && chunkZ >= terrainMinZ() && chunkZ <= terrainMaxZ();
    }

    /**
     * Minecraft's INITIALIZE_LIGHT and LIGHT steps on every terrain chunk, core and halo, as the
     * server lights a chunk among lit neighbors; the light engine reads the grid's blocks through
     * chunkForLighting meanwhile. The engine runs on its own thread, so the four phases only bound
     * how much work is queued at once. Chunks the server keeps are left to it.
     */
    private void executeLighting(ExecutorService workerPool, Runnable onChunkDone) {
        List<ProtoChunk> lit = new ArrayList<>();
        for (int cz = terrainMinZ(); cz <= terrainMaxZ(); cz++) {
            for (int cx = terrainMinX(); cx <= terrainMaxX(); cx++) {
                long pos = ChunkPos.asLong(cx, cz);
                if (lightHeld.contains(pos) && !lightReplaced.contains(pos)) continue;
                ProtoChunk chunk = getChunk(cx, cz);
                if (chunk != null) lit.add(chunk);
            }
        }
        lighting = true;
        try {
            List<CompletableFuture<?>> initFutures = new ArrayList<>(lit.size());
            for (ProtoChunk chunk : lit) initFutures.add(generate(ChunkStatus.INITIALIZE_LIGHT, chunk, 0));
            CompletableFuture.allOf(initFutures.toArray(new CompletableFuture[0])).join();
            for (int phase = 0; phase < 4; phase++) {
                List<CompletableFuture<?>> phaseFutures = new ArrayList<>();
                for (ProtoChunk chunk : lit) {
                    ChunkPos pos = chunk.getPos();
                    if (phaseOf(pos.x, pos.z, 2) != phase) continue;
                    CompletableFuture<?> f = generate(ChunkStatus.LIGHT, chunk, 1);
                    if (onChunkDone != null && isCore(pos.x, pos.z)) f = f.thenRun(onChunkDone);
                    phaseFutures.add(f);
                }
                CompletableFuture.allOf(phaseFutures.toArray(new CompletableFuture[0])).join();
            }
        } finally {
            lighting = false;
        }
    }

    /**
     * Regression only: features of the chunks in group 0 first (nine phases), then group 1, and
     * so on. With one group per batch tile, one grid reproduces the order of a batched run.
     */
    static volatile java.util.function.IntBinaryOperator featureGroups;

    private void executeFeaturesInGroups(java.util.function.IntBinaryOperator groups, ExecutorService workerPool,
                                         Runnable onChunkDone) {
        java.util.TreeSet<Integer> ids = new java.util.TreeSet<>();
        for (long packed : getCorePositions()) ids.add(groups.applyAsInt(ChunkPos.getX(packed), ChunkPos.getZ(packed)));
        for (int id : ids) {
            if (id < 0) continue;  // regression only: these chunks' features do not run
            for (int phase = 0; phase < 9; phase++) {
                List<CompletableFuture<?>> futures = new ArrayList<>();
                for (long packed : getCorePositions()) {
                    int cx = ChunkPos.getX(packed);
                    int cz = ChunkPos.getZ(packed);
                    if (groups.applyAsInt(cx, cz) != id || phaseOf(cx, cz, 3) != phase) continue;
                    if (featureSkip.contains(packed)) continue;
                    CompletableFuture<?> f = generateOnPool(ChunkStatus.FEATURES, workerPool, getChunk(cx, cz), 1,
                            parallelFeatures);
                    futures.add(onChunkDone != null ? f.thenRun(onChunkDone) : f);
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }
        }
    }

    /** Off only in the parity regression, to run the nine phases with full barriers. */
    static volatile boolean featuresByNeighbors = true;

    /**
     * The same feature order as the nine checkerboard phases, without waiting for whole phases:
     * a chunk's features read and write its 3x3 neighborhood, so two chunks interact only when
     * they are at most 2 apart on both axes, and each chunk starts once every such chunk of an
     * earlier phase has finished. Every interacting pair keeps its phase order and the others
     * commute, so the result equals the phased run while slow chunks no longer stall a phase.
     */
    private void executeFeaturesByNeighbors(ExecutorService workerPool, Runnable onChunkDone) {
        int spacing = 3;
        long stageStart = System.nanoTime();
        long[][] took = new long[coreChunksX][coreChunksZ];
        CompletableFuture<?>[][] done = new CompletableFuture<?>[coreChunksX][coreChunksZ];
        List<CompletableFuture<?>> all = new ArrayList<>(coreChunksX * coreChunksZ);
        for (int phase = 0; phase < spacing * spacing; phase++) {
            for (int z = 0; z < coreChunksZ; z++) {
                for (int x = 0; x < coreChunksX; x++) {
                    int cx = coreMinChunkX + x;
                    int cz = coreMinChunkZ + z;
                    if (phaseOf(cx, cz, spacing) != phase) continue;
                    ProtoChunk chunk = getChunk(cx, cz);
                    if (chunk == null) continue;
                    if (featureSkip.contains(ChunkPos.asLong(cx, cz))) {
                        done[x][z] = CompletableFuture.completedFuture(null);
                        continue;
                    }
                    List<CompletableFuture<?>> before = new ArrayList<>(16);
                    for (int dz = -2; dz <= 2; dz++) {
                        for (int dx = -2; dx <= 2; dx++) {
                            int nx = x + dx;
                            int nz = z + dz;
                            if (nx < 0 || nz < 0 || nx >= coreChunksX || nz >= coreChunksZ) continue;
                            if (phaseOf(cx + dx, cz + dz, spacing) < phase && done[nx][nz] != null) before.add(done[nx][nz]);
                        }
                    }
                    int ix = x;
                    int iz = z;
                    CompletableFuture<?> f = CompletableFuture.allOf(before.toArray(new CompletableFuture[0]))
                            .thenApplyAsync(ignored -> {
                                long started = System.nanoTime();
                                try {
                                    return generate(ChunkStatus.FEATURES, chunk, 1);
                                } finally {
                                    took[ix][iz] = System.nanoTime() - started;
                                }
                            }, workerPool)
                            .thenCompose(Function.identity());
                    done[x][z] = f;
                    all.add(onChunkDone != null ? f.thenRun(onChunkDone) : f);
                }
            }
        }
        CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).join();
        featureDiagnostics = featureStats(took, stageStart, spacing);
    }

    private volatile String featureDiagnostics = "";

    public String getFeatureDiagnostics() {
        return featureDiagnostics;
    }

    /**
     * How the features stage used the workers: the share of worker time spent placing features,
     * the shortest possible stage given the neighbor order (the longest chain of chunks that must
     * run one after another), and the slowest chunk.
     */
    private String featureStats(long[][] took, long stageStart, int spacing) {
        double wall = (System.nanoTime() - stageStart) / 1e9;
        long busy = 0;
        long slowest = 0;
        ChunkPos slowestPos = null;
        long[][] finish = new long[coreChunksX][coreChunksZ];
        long longest = 0;
        for (int phase = 0; phase < spacing * spacing; phase++) {
            for (int z = 0; z < coreChunksZ; z++) {
                for (int x = 0; x < coreChunksX; x++) {
                    int cx = coreMinChunkX + x;
                    int cz = coreMinChunkZ + z;
                    if (phaseOf(cx, cz, spacing) != phase) continue;
                    long start = 0;
                    for (int dz = -2; dz <= 2; dz++) {
                        for (int dx = -2; dx <= 2; dx++) {
                            int nx = x + dx;
                            int nz = z + dz;
                            if (nx < 0 || nz < 0 || nx >= coreChunksX || nz >= coreChunksZ) continue;
                            if (phaseOf(cx + dx, cz + dz, spacing) < phase) start = Math.max(start, finish[nx][nz]);
                        }
                    }
                    finish[x][z] = start + took[x][z];
                    longest = Math.max(longest, finish[x][z]);
                    busy += took[x][z];
                    if (took[x][z] > slowest) {
                        slowest = took[x][z];
                        slowestPos = new ChunkPos(cx, cz);
                    }
                }
            }
        }
        int threads = Math.max(1, workerThreads);
        return String.format(java.util.Locale.ROOT, "features: busy %.0f%% of %d workers, neighbor-order minimum %.1fs, slowest chunk %.2fs %s",
                busy / 1e9 / (wall * threads) * 100, threads, longest / 1e9, slowest / 1e9, slowestPos);
    }

    /** Worker count, for the stage diagnostics. */
    private int workerThreads = Runtime.getRuntime().availableProcessors();

    public void setWorkerThreads(int threads) {
        workerThreads = threads;
    }

    /** One chunk's blender and structure view, shared by its biome, noise and surface steps. */
    private record TerrainRegion(Blender blender, StructureManager structureManager) {}

    /** Checkerboard phase in [0, spacing^2): chunks sharing a phase are at least spacing apart on some axis. */
    static int phaseOf(int chunkX, int chunkZ, int spacing) {
        return Math.floorMod(chunkX, spacing) + spacing * Math.floorMod(chunkZ, spacing);
    }

    /**
     * Vanilla's SPAWN step: the passive mobs placed once at generation (spawn settings come from
     * the biome, so datapack and mod biomes apply). Each spawn stays inside its own chunk.
     * Deliberately one chunk at a time, as in vanilla: it is cheap, and mod entity constructors
     * may touch shared world state (such as the level's thread-checked random).
     */
    public void executeSpawn(ExecutorService workerPool, LongSet skip, Runnable onChunkDone) {
        List<CompletableFuture<?>> futures = new ArrayList<>(getCoreTotalChunks());
        for (long packed : getCorePositions()) {
            if (skip.contains(packed)) continue;
            ProtoChunk chunk = getChunk(ChunkPos.getX(packed), ChunkPos.getZ(packed));
            CompletableFuture<?> f = generate(ChunkStatus.SPAWN, chunk, 1)
                .thenRun(() -> spawnedEntities.add(chunk.getEntities().size()));
            if (onChunkDone != null) f = f.thenRun(onChunkDone);
            futures.add(f);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    public long getSpawnedEntities() {
        return spawnedEntities.sum();
    }

    /**
     * Serializes and compresses core chunks not in {@code skip} on the worker threads, in the
     * exact sector framing a RegionFile stores. Writing happens elsewhere (see WorldChunkIO).
     */
    public List<WorldChunkIO.EncodedChunk> encodeForSaving(ExecutorService workerPool, LongSet skip,
                                                           Runnable onChunkDone) {
        // Same compression RegionFile's 3-argument constructor selects; the id travels with each chunk.
        RegionFileVersion version = RegionFileVersion.VERSION_DEFLATE;
        List<CompletableFuture<WorldChunkIO.EncodedChunk>> futures = new ArrayList<>(getCoreTotalChunks());

        for (long packed : getCorePositions()) {
            if (skip.contains(packed)) continue;
            ProtoChunk chunk = getChunk(ChunkPos.getX(packed), ChunkPos.getZ(packed));
            chunk.setPersistedStatus(ChunkStatus.FULL);

            futures.add(CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                promoteDummyBlockEntities(chunk);
                CompoundTag tag = ChunkSerializer.write(level, chunk);
                // The worldgen heightmaps too (Minecraft ignores them in a full chunk): a later
                // batch or run reads them back for this chunk as a neighbor (see adoptSaved).
                CompoundTag heightmaps = tag.getCompound("Heightmaps");
                for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
                    if (!entry.getKey().keepAfterWorldgen()) {
                        heightmaps.put(entry.getKey().getSerializationKey(), new net.minecraft.nbt.LongArrayTag(entry.getValue().getRawData()));
                    }
                }
                tag.put("Heightmaps", heightmaps);
                // ChunkSerializer keeps entities only for proto chunks; a full chunk's load path
                // reads this same list (as for upgraded worlds) and adds the spawned mobs.
                if (!chunk.getEntities().isEmpty()) {
                    ListTag entities = new ListTag();
                    entities.addAll(chunk.getEntities());
                    tag.put("entities", entities);
                }
                FramedChunkBuffer framed = new FramedChunkBuffer(version.getId());
                try (DataOutputStream stream = new DataOutputStream(version.wrap(framed))) {
                    NbtIo.write(tag, stream);
                } catch (IOException e) {
                    throw new UncheckedIOException(String.format("Failed to encode chunk [%d, %d]",
                            chunk.getPos().x, chunk.getPos().z), e);
                }
                encodeNanos.add(System.nanoTime() - started);
                encodedChunks.increment();
                if (onChunkDone != null) onChunkDone.run();
                return new WorldChunkIO.EncodedChunk(chunk.getPos(), framed.frame());
            }, workerPool));
        }

        List<WorldChunkIO.EncodedChunk> encoded = new ArrayList<>(futures.size());
        for (CompletableFuture<WorldChunkIO.EncodedChunk> future : futures) encoded.add(future.join());
        return encoded;
    }

    /**
     * WorldGenRegion#setBlock records block entities placed into proto chunks as {"id": "DUMMY"}
     * placeholders, which vanilla turns into real block entities when the chunk becomes a
     * LevelChunk (LevelChunk#promotePendingBlockEntity). This grid saves proto chunks as full
     * chunks directly, and a full chunk's loader cannot read DUMMY ("Block entity has invalid
     * type: DUMMY"), so the same promotion runs here. Other pending tags are real data and stay.
     */
    static int promoteDummyBlockEntities(ProtoChunk chunk) {
        int promoted = 0;
        for (Map.Entry<BlockPos, CompoundTag> pending : List.copyOf(chunk.getBlockEntityNbts().entrySet())) {
            if (!"DUMMY".equals(pending.getValue().getString("id"))) continue;
            BlockPos pos = pending.getKey();
            BlockState state = chunk.getBlockState(pos);
            BlockEntity blockEntity = state.hasBlockEntity() && state.getBlock() instanceof EntityBlock block
                    ? block.newBlockEntity(pos, state) : null;
            if (blockEntity != null) {
                chunk.setBlockEntity(blockEntity);
                promoted++;
            } else {
                // Vanilla drops a placeholder whose block no longer has a block entity.
                chunk.removeBlockEntity(pos);
            }
        }
        return promoted;
    }

    public String getEncodeDiagnostics() {
        long count = encodedChunks.sum();
        if (count == 0) return "Saving: no new chunks to encode.";
        return String.format(java.util.Locale.ROOT, "Saving worker time/chunk: serialize+deflate=%.2fms",
                encodeNanos.sum() / (count * 1_000_000.0));
    }

    /**
     * Vanilla's ChunkMap calls ThreadedLevelLightEngine#updateChunkStatus when a chunk unloads,
     * which drops that chunk's block/sky light layers from the shared light engine. This grid
     * never goes through ChunkMap, so without the same call every lit chunk's light data stays
     * in the server's light engine for the life of the process. Chunks were serialized in
     * encodeForSaving before this runs. Positions the server holds keep their light at any
     * status: the server lit its partly generated chunks already, and replaced ones were lit
     * from this grid, so dropping it would leave them dark.
     */
    private void releaseLightData() {
        long[] positions = new long[gridChunksX * gridChunksZ];
        int index = 0;
        for (int gx = 0; gx < gridChunksX; gx++) {
            for (int gz = 0; gz < gridChunksZ; gz++) {
                positions[index++] = ChunkPos.asLong(gridMinChunkX + gx, gridMinChunkZ + gz);
            }
        }
        ServerLevel target = level;
        ThreadedLevelLightEngine engine = lightEngine;
        ThreadedLevelLightEngineInvoker invoker = (ThreadedLevelLightEngineInvoker) (Object) engine;
        target.getServer().execute(() -> {
            var map = (com.example.pregen.mixin.ChunkMapAccessor) target.getChunkSource().chunkMap;
            for (long packed : positions) {
                if (map.pregen$getVisibleChunk(packed) != null || map.pregen$getUpdatingChunk(packed) != null) continue;
                invoker.callUpdateChunkStatus(new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed)));
            }
            engine.tryScheduleUpdate();
        });
    }

    /**
     * Clears internal references to allow JVM garbage collector to reclaim heap immediately.
     */
    public void dispose() {
        activeGrids.remove(this);
        if (currentGrid == this) {
            currentGrid = null;
        }
        if (chunks != null) {
            releaseLightData();
        }
        fallbackChunks.clear();
        holders = null;
        if (chunks != null) {
            for (int gx = 0; gx < gridChunksX; gx++) {
                for (int gz = 0; gz < gridChunksZ; gz++) {
                    chunks[gx][gz] = null;
                }
            }
            chunks = null;
        }
    }
}
