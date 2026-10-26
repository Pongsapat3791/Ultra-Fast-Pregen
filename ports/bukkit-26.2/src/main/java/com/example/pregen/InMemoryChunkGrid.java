package com.example.pregen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
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
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
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
 * WorldGenRegion read the grid exactly as they read the server's chunk holders. A step gets every
 * chunk it may read (as the server passes it), not only its close neighbors as in the mod builds,
 * whose mixins serve the farther ones.
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

    /** A thread of this plugin's worker pool. */
    public static final class WorkerThread extends Thread {
        public WorkerThread(Runnable task, String name) {
            super(task, name);
        }
    }

    private final ServerLevel level;
    /**
     * The level's heights as constants, for the grid's chunks: a chunk asks for its minimum Y on
     * every block it reads or writes, and the level answers through its dimension type (about 1%
     * of generation time). Proto chunks use their accessor for nothing else.
     */
    private final LevelHeightAccessor heights;
    private final ChunkGenerator generator;
    private final StructureTemplateManager templateManager;
    private final ThreadedLevelLightEngine lightEngine;
    private final WorldGenContext context;
    private final boolean fusedTerrain;
    private final boolean parallelSteps;
    /** Off when plugins add to the world's generation (block populators), which Minecraft runs one chunk at a time. */
    private boolean parallelFeatures;

    private final int coreMinChunkX;
    private final int coreMinChunkZ;
    private final int coreChunksX;
    private final int coreChunksZ;
    /**
     * Which chunks of the core rectangle belong to the run's area (all of them for a square; see
     * setArea): the others are treated like chunks outside the area, as halo.
     */
    private ChunkAreaFilter area = (chunkX, chunkZ) -> true;
    private long[] corePositions;

    /** Which chunks belong to the run's area. */
    @FunctionalInterface
    interface ChunkAreaFilter {
        boolean contains(int chunkX, int chunkZ);
    }

    private final int gridMinChunkX;
    private final int gridMinChunkZ;
    private final int gridChunksX;
    private final int gridChunksZ;

    private ProtoChunk[][] chunks;
    /** Set once this grid's lighting started; before that the light engine holds none of its light (see dispose). */
    private volatile boolean lightStarted;
    /** Terrain positions the server held when lighting started: the server lights and keeps those. */
    private volatile LongSet lightHeld = LongSets.EMPTY_SET;
    private GridHolder[][] holders;
    private final Map<Long, ProtoChunk> fallbackChunks = new ConcurrentHashMap<>();
    private final Map<Long, GridHolder> fallbackHolders = new ConcurrentHashMap<>();
    /** Chunks where a stronghold can start (see WorldgenSafety#strongholdChunks). */
    private LongSet strongholdChunks = LongSets.EMPTY_SET;
    /** StrongholdPieces lays a stronghold out through static fields: one layout at a time, in any world. */
    private static final Object STRONGHOLD_LAYOUT = new Object();
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
        protected void addSaveDependency(CompletableFuture<?> sync) {
            // A grid chunk is saved by this mod, not through a server holder.
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

    public String getTerrainDiagnostics() {
        long count = terrainChunks.sum();
        if (count == 0) return "Stage 2 used Minecraft's per-status pipeline";
        double divisor = count * 1_000_000.0;
        return String.format(java.util.Locale.ROOT,
                "Stage 2 worker time/chunk: setup=%.2fms, biomes=%.2fms, noise=%.2fms, surface=%.2fms",
                terrainSetupNanos.sum() / divisor, biomeNanos.sum() / divisor,
                noiseNanos.sum() / divisor, surfaceNanos.sum() / divisor);
    }

    /**
     * The holder of a grid chunk, or of its fallback past the grid's edge: the ring of chunks
     * given biomes just outside the terrain may read structure starts beyond the grid, which has
     * none there (see getNeighborhoodCache).
     */
    public GenerationChunkHolder holderOrFallback(int worldChunkX, int worldChunkZ) {
        GridHolder[][] all = holders;
        int gx = worldChunkX - gridMinChunkX;
        int gz = worldChunkZ - gridMinChunkZ;
        if (all != null && gx >= 0 && gx < gridChunksX && gz >= 0 && gz < gridChunksZ) {
            return all[gx][gz];
        }
        return fallbackHolders.computeIfAbsent(ChunkPos.pack(worldChunkX, worldChunkZ),
                k -> new GridHolder(this, (ProtoChunk) getChunkOrFallback(worldChunkX, worldChunkZ)));
    }

    public ChunkAccess getChunkOrFallback(int worldChunkX, int worldChunkZ) {
        ProtoChunk c = getChunk(worldChunkX, worldChunkZ);
        if (c != null) {
            return c;
        }
        long key = ChunkPos.pack(worldChunkX, worldChunkZ);
        return fallbackChunks.computeIfAbsent(key, k -> {
            ProtoChunk fallback = new ProtoChunk(
                new ChunkPos(worldChunkX, worldChunkZ),
                UpgradeData.EMPTY,
                heights,
                level.palettedContainerFactory(),
                null
            );
            fallback.setPersistedStatus(ChunkStatus.FULL);
            countAsLit(fallback);
            return fallback;
        });
    }

    public InMemoryChunkGrid(ServerLevel level, int minBlockX, int minBlockZ, int coreChunksX, int coreChunksZ,
                             boolean fusedTerrain, boolean parallelSteps) {
        this.level = level;
        this.heights = LevelHeightAccessor.create(level.getMinY(), level.getHeight());
        this.generator = level.getChunkSource().getGenerator();
        this.templateManager = level.getStructureManager();
        this.lightEngine = level.getChunkSource().getLightEngine();
        // The main-thread mailbox serves only the FULL step, which this grid never runs.
        this.context = new WorldGenContext(level, generator, templateManager, lightEngine, null, pos -> {});
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

        var biomeRegistry = level.palettedContainerFactory();

        // Pre-allocate all ProtoChunks in RAM
        for (int gx = 0; gx < gridChunksX; gx++) {
            for (int gz = 0; gz < gridChunksZ; gz++) {
                int worldChunkX = gridMinChunkX + gx;
                int worldChunkZ = gridMinChunkZ + gz;
                chunks[gx][gz] = new ProtoChunk(
                    new ChunkPos(worldChunkX, worldChunkZ),
                    UpgradeData.EMPTY,
                    heights,
                    biomeRegistry,
                    null
                );
                countAsLit(chunks[gx][gz]);
                holders[gx][gz] = new GridHolder(this, chunks[gx][gz]);
            }
        }
    }

    /**
     * Paper: its world generation gives chunks that are not lit no light at all (brightness 0, see
     * its WorldGenRegion). On the other servers, Minecraft's light engine knows nothing of the
     * grid's chunks and answers full daylight and no block light. Counted as lit, the grid's chunks
     * read the same on Paper (its light engine finds no loaded chunk there and answers so too), so
     * features that look at the light (brown mushrooms) are placed as on the other servers.
     */
    private static void countAsLit(ProtoChunk chunk) {
        if (Platform.MOONRISE) chunk.setLightCorrect(true);
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
     * server passes a generation step: every chunk the step may read (see stepRadius). Past the
     * grid's edge, which only the ring given biomes outside the terrain reaches, empty chunks
     * stand in (see holderOrFallback).
     */
    public StaticCache2D<GenerationChunkHolder> getNeighborhoodCache(int worldChunkX, int worldChunkZ, int radius) {
        if (holders == null) throw new IllegalStateException("The grid was disposed");
        return StaticCache2D.create(worldChunkX, worldChunkZ, radius, this::holderOrFallback);
    }

    /** How far a step reads around its chunk: Minecraft's WorldGenRegion serves exactly these. */
    static int stepRadius(ChunkStatus status) {
        return ChunkPyramid.GENERATION_PYRAMID.getStepTo(status).directDependencies().size() - 1;
    }

    /** Minecraft's own step for {@code status} on one grid chunk, as the server applies it. */
    private CompletableFuture<ChunkAccess> generate(ChunkStatus status, ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        ChunkStep step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
        return step.apply(context, getNeighborhoodCache(pos.x(), pos.z(), step.directDependencies().size() - 1), chunk);
    }

    /**
     * Minecraft's STRUCTURE_STARTS step (ChunkStatusTasks#generateStructureStarts) with one change:
     * the starts reach the level's structure cache through a task queued on the server thread,
     * which Minecraft's step hands them to with MinecraftServer#execute. A stopping server runs
     * such tasks on the calling thread, and the cache is not safe for many workers at once.
     * Strongholds lay out one at a time (see STRONGHOLD_LAYOUT).
     */
    private CompletableFuture<ChunkAccess> generateStructureStarts(ProtoChunk chunk) {
        if (Platform.generatesStructures(level)) {
            Runnable create = () -> generator.createStructures(level.registryAccess(), level.getChunkSource().getGeneratorState(),
                    level.structureManager(), chunk, templateManager, level.dimension());
            if (strongholdChunks.contains(chunk.getPos().pack())) {
                synchronized (STRONGHOLD_LAYOUT) {
                    create.run();
                }
            } else {
                create.run();
            }
        }
        if (Platform.MOONRISE) {
            // Paper's structure cache is thread-safe (Moonrise); Folia, without a server thread, fills it right away.
            level.onStructureStartsAvailable(chunk);
        } else {
            var server = level.getServer();
            server.schedule(server.wrapRunnable(() -> level.onStructureStartsAvailable(chunk)));
        }
        if (chunk.getPersistedStatus().isBefore(ChunkStatus.STRUCTURE_STARTS)) chunk.setPersistedStatus(ChunkStatus.STRUCTURE_STARTS);
        return CompletableFuture.completedFuture(chunk);
    }

    /** Chunks where a stronghold can start; call before the first layer runs. */
    void setStrongholdChunks(LongSet chunks) {
        strongholdChunks = chunks;
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
        long[] positions = corePositions;
        return positions != null ? positions.length : getCorePositions().length;
    }

    /**
     * Limits the core to the chunks of the tile that lie in the run's area (a circle's); call
     * before the first layer runs.
     */
    void setArea(ChunkAreaFilter filter) {
        area = filter;
        corePositions = null;
    }

    /** Packed positions of the core chunks, i.e. the chunks this tile may save. */
    public long[] getCorePositions() {
        long[] positions = corePositions;
        if (positions == null) {
            it.unimi.dsi.fastutil.longs.LongArrayList inArea = new it.unimi.dsi.fastutil.longs.LongArrayList(coreChunksX * coreChunksZ);
            for (int cx = coreMinChunkX; cx < coreMinChunkX + coreChunksX; cx++) {
                for (int cz = coreMinChunkZ; cz < coreMinChunkZ + coreChunksZ; cz++) {
                    if (area.contains(cx, cz)) inArea.add(ChunkPos.pack(cx, cz));
                }
            }
            positions = inArea.toLongArray();
            corePositions = positions;
        }
        return positions.clone();
    }

    /**
     * STRUCTURE_STARTS and every "simple" status task (references, carvers, features, spawn) do
     * their work inside generate() and return a finished future, ignoring the executor. Called
     * in a loop they all run on the calling thread, so the call itself is moved onto the pool.
     */
    public void setParallelFeatures(boolean parallel) {
        parallelFeatures = parallel && parallelSteps;
    }

    private CompletableFuture<?> generateOnPool(ChunkStatus status, ExecutorService workerPool, ChunkAccess chunk) {
        return generateOnPool(status, workerPool, chunk, parallelSteps);
    }

    private CompletableFuture<?> generateOnPool(ChunkStatus status, ExecutorService workerPool, ChunkAccess chunk,
                                                boolean parallel) {
        if (!parallel) {
            return generate(status, chunk);
        }
        return CompletableFuture.supplyAsync(() -> generate(status, chunk), workerPool)
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
                    CompletableFuture<?> f = parallelSteps
                            ? CompletableFuture.supplyAsync(() -> generateStructureStarts(chunk), workerPool).thenCompose(Function.identity())
                            : generateStructureStarts(chunk);
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
                    CompletableFuture<?> f = generateOnPool(ChunkStatus.STRUCTURE_REFERENCES, workerPool, getChunk(cx, cz));
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
                RandomState randomState = level.getChunkSource().randomState();
                StructureManager structureManager = level.structureManager();
                NoiseSettings noiseSettings = noiseGen.generatorSettings().value().noiseSettings();
                int biomeRadius = stepRadius(ChunkStatus.BIOMES);

                // Minecraft's BIOMES and NOISE steps hand their work to Minecraft's own thread
                // pool; here it runs on this plugin's workers instead, through the same methods.
                // Minecraft 1.21 builds noise and surface only once the eight neighbors have
                // biomes (surface reads their stored biomes), so every chunk first computes its
                // biomes, including a ring just outside the terrain area, and its noise and
                // surface wait for the 3x3 neighbors' biomes. Each part reads the grid through a
                // region of its own step, as vanilla's tasks do; the noise chunk, blender and
                // structure view made for biomes serve biomes and noise, as in vanilla, and the
                // surface is Minecraft's own step.
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
                            long partStart = System.nanoTime();
                            WorldGenRegion region = new WorldGenRegion(level, getNeighborhoodCache(x, z, biomeRadius),
                                    ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.BIOMES), chunk);
                            TerrainRegion terrain = new TerrainRegion(Blender.of(region),
                                    structureManager.forWorldGenRegion(region));
                            regions[ix][iz] = terrain;
                            terrainSetupNanos.add(System.nanoTime() - partStart);

                            // 1. Biomes (also creates the chunk's noise chunk and aquifer)
                            partStart = System.nanoTime();
                            ServerAccess.createBiomes(noiseGen, terrain.blender(), randomState, terrain.structureManager(), chunk);
                            chunk.setPersistedStatus(ChunkStatus.BIOMES);
                            biomeNanos.add(System.nanoTime() - partStart);
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
                            TerrainRegion terrain = regions[ix][iz];
                            NoiseSettings clamped = noiseSettings.clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
                            int minCellY = Mth.floorDiv(clamped.minY(), clamped.getCellHeight());
                            int cellCountY = Mth.floorDiv(clamped.height(), clamped.getCellHeight());

                            // 2. 3D Terrain Density Noise
                            long partStart = System.nanoTime();
                            if (cellCountY > 0) {
                                ServerAccess.fill(noiseGen, terrain.blender(), terrain.structureManager(), randomState, chunk,
                                        minCellY, cellCountY);
                            }
                            chunk.setPersistedStatus(ChunkStatus.NOISE);
                            noiseNanos.add(System.nanoTime() - partStart);

                            // 3. Surface Rules
                            partStart = System.nanoTime();
                            // Minecraft's own step (it sets the status).
                            generate(ChunkStatus.SURFACE, chunk).join();
                            surfaceNanos.add(System.nanoTime() - partStart);
                            terrainChunks.increment();
                        }, workerPool);
                        futures.add(onChunkDone != null ? f.thenRun(onChunkDone) : f);
                    }
                }
            } else {
                // Minecraft's own status tasks, in the same order: biomes first (with the ring
                // outside the terrain area), then each chunk's noise and surface once its 3x3
                // neighbors have biomes. Minecraft runs these for many chunks at once, so they
                // go to the workers also one chunk at a time otherwise (parallel_steps=false or
                // after a failure): Minecraft's biome and noise steps hand their work to its
                // thread pool, but Paper's run it where they are called, which was one thread.
                int sizeX = maxX - minX + 3;
                int sizeZ = maxZ - minZ + 3;
                CompletableFuture<?>[][] biomesDone = new CompletableFuture<?>[sizeX][sizeZ];
                for (int cz = minZ - 1; cz <= maxZ + 1; cz++) {
                    for (int cx = minX - 1; cx <= maxX + 1; cx++) {
                        biomesDone[cx - minX + 1][cz - minZ + 1] = generateOnPool(ChunkStatus.BIOMES, workerPool, getChunk(cx, cz), true);
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
                            .thenCompose(ignored -> generateOnPool(ChunkStatus.NOISE, workerPool, chunk, true))
                            .thenCompose(ignored -> generateOnPool(ChunkStatus.SURFACE, workerPool, chunk, true));
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
                    CompletableFuture<?> f = generateOnPool(ChunkStatus.CARVERS, workerPool, getChunk(cx, cz));
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
                && chunkZ >= coreMinChunkZ && chunkZ < coreMinChunkZ + coreChunksZ && area.contains(chunkX, chunkZ);
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
            // CraftBukkit keeps a section's biomes writable (its constructor takes them so).
            @SuppressWarnings("unchecked")
            PalettedContainer<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomes =
                    (PalettedContainer<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>) sections[i].getBiomes();
            sections[i] = new LevelChunkSection(SeamWrites.writableCopy(saved.states()[i]), biomes);
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
            for (int cx = terrainMinX(); cx <= terrainMaxX(); cx++) positions[index++] = ChunkPos.pack(cx, cz);
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
                CompoundTag tag = savedData(chunk);
                return encoded(chunk, tag, version);
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
                if (isCore(cx, cz) && !keptCore.contains(ChunkPos.pack(cx, cz))) continue;
                ProtoChunk chunk = getChunk(cx, cz);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                PalettedContainer<BlockState>[] copy = new PalettedContainer[sections.length];
                for (int i = 0; i < sections.length; i++) copy[i] = sections[i].getStates().copy();
                haloSnapshot.put(ChunkPos.pack(cx, cz), copy);
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
                        if (featureSkip.contains(ChunkPos.pack(cx, cz)) || !area.contains(cx, cz)) continue;
                        CompletableFuture<?> f = generateOnPool(ChunkStatus.FEATURES, workerPool, chunk, parallelFeatures);
                        if (onChunkDone != null) f = f.thenRun(onChunkDone);
                        phaseFutures.add(f);
                    }
                }
            }

            CompletableFuture.allOf(phaseFutures.toArray(new CompletableFuture[0])).join();
        }
    }

    /**
     * Server-held chunks inside the grid when lighting starts: the server lights and keeps those
     * (this batch saves none of them; see DynamicBatchPreGen#finishBatch).
     */
    public void setLightHeld(LongSet heldByServer) {
        lightHeld = heldByServer;
    }

    /**
     * Every terrain chunk the server does not hold, core and halo, lit on the workers (see
     * GridLight). The mod builds can also light through the server's light engine; a plugin
     * cannot, because that engine reads blocks only from chunks the server holds (it would light
     * the grid as empty, caves at sky light 15). Chunks the server holds are read with the grid's
     * blocks for their neighbors' light and left to the server.
     */
    private void executeLighting(ExecutorService workerPool, Runnable onChunkDone) {
        Long2ObjectOpenHashMap<ProtoChunk> terrain = new Long2ObjectOpenHashMap<>();
        for (int cz = terrainMinZ(); cz <= terrainMaxZ(); cz++) {
            for (int cx = terrainMinX(); cx <= terrainMaxX(); cx++) {
                ProtoChunk chunk = getChunk(cx, cz);
                if (chunk != null) terrain.put(ChunkPos.pack(cx, cz), chunk);
            }
        }
        lightStarted = true;
        LongSet held = lightHeld;
        lightInParallel(terrain, pos -> !held.contains(pos), workerPool, onChunkDone);
    }

    /**
     * The grid's light worked out on the workers (see GridLight), then given to the server's light
     * engine as it takes a lit chunk loaded from disk (SerializableChunkData#read, then Minecraft's
     * light steps for a lit chunk), so that engine propagates nothing; spawning, saving and the
     * release of the grid's light (dispose) read it there as before.
     */
    private void lightInParallel(Long2ObjectOpenHashMap<ProtoChunk> terrain, java.util.function.LongPredicate lit,
                                 ExecutorService workerPool, Runnable onChunkDone) {
        List<GridLight.ChunkLight> computed = GridLight.compute(level, terrain, lit,
                terrainMinX(), terrainMinZ(), terrainMaxX(), terrainMaxZ(), workerPool);
        if (Platform.MOONRISE) {
            keepLight(computed, onChunkDone);
            return;
        }
        int bottom = lightEngine.getMinLightSection();
        List<CompletableFuture<?>> futures = new ArrayList<>(computed.size());
        for (GridLight.ChunkLight light : computed) {
            ProtoChunk chunk = light.chunk();
            ChunkPos pos = chunk.getPos();
            lightEngine.retainData(pos, true);
            for (int i = 0; i < light.sky().length; i++) {
                SectionPos section = SectionPos.of(pos, bottom + i);
                // An empty block layer is what the engine starts any section with.
                DataLayer block = light.block()[i];
                if (block != null && !block.isEmpty()) lightEngine.queueSectionData(LightLayer.BLOCK, section, block);
                if (light.sky()[i] != null) lightEngine.queueSectionData(LightLayer.SKY, section, light.sky()[i]);
            }
            chunk.setLightEngine(lightEngine);
            CompletableFuture<?> f = CompletableFuture.allOf(lightEngine.initializeLight(chunk, true), lightEngine.lightChunk(chunk, true))
                    .thenRun(() -> chunk.setPersistedStatus(ChunkStatus.LIGHT));
            if (onChunkDone != null && isCore(pos.x(), pos.z())) f = f.thenRun(onChunkDone);
            futures.add(f);
        }
        awaitLight(futures);
    }

    /**
     * Paper: the grid's light stays with the batch until its chunks are saved (see withLight), as
     * Paper's light engine keeps light in the chunks and has no place for a batch's. The chunks
     * count as lit all along (see countAsLit).
     */
    private void keepLight(List<GridLight.ChunkLight> computed, Runnable onChunkDone) {
        for (GridLight.ChunkLight light : computed) {
            ProtoChunk chunk = light.chunk();
            ChunkPos pos = chunk.getPos();
            keptLight.put(pos.pack(), light);
            chunk.setPersistedStatus(ChunkStatus.LIGHT);
            if (onChunkDone != null && isCore(pos.x(), pos.z())) onChunkDone.run();
        }
    }

    /** Paper: each lit chunk's light until it is saved (see keepLight). */
    private final Map<Long, GridLight.ChunkLight> keptLight = new ConcurrentHashMap<>();

    /** No chunk's light finishing for this long means the light engine stopped (see awaitLight). */
    private static final long LIGHT_STALL_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(3);

    /**
     * Waits for light steps, which complete on the light engine's own thread. When that thread
     * fails (another mod's light engine meeting data it does not expect, say) their futures never
     * complete, so a batch in which no chunk's light finished for three minutes fails with a
     * message instead of waiting forever.
     */
    private static void awaitLight(List<CompletableFuture<?>> futures) {
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        long done = -1;
        long progressAt = System.nanoTime();
        while (true) {
            try {
                all.get(5, java.util.concurrent.TimeUnit.SECONDS);
                return;
            } catch (java.util.concurrent.TimeoutException timeout) {
                long now = 0;
                for (CompletableFuture<?> future : futures) if (future.isDone()) now++;
                if (now != done) {
                    done = now;
                    progressAt = System.nanoTime();
                } else if (System.nanoTime() - progressAt > LIGHT_STALL_NANOS) {
                    throw new IllegalStateException("Lighting stalled: no chunk's light finished for 3 minutes (" + done + " of "
                            + futures.size() + " done). The light engine may have failed; see the errors earlier in the log.");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while lighting", interrupted);
            } catch (java.util.concurrent.ExecutionException failure) {
                throw new java.util.concurrent.CompletionException(failure.getCause());
            }
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
                    CompletableFuture<?> f = generateOnPool(ChunkStatus.FEATURES, workerPool, getChunk(cx, cz),
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
        // The last chunk scheduled to place each wide template piece (see widePieces).
        Map<StructurePiece, CompletableFuture<?>> lastPlacing = new java.util.IdentityHashMap<>();
        for (int phase = 0; phase < spacing * spacing; phase++) {
            for (int z = 0; z < coreChunksZ; z++) {
                for (int x = 0; x < coreChunksX; x++) {
                    int cx = coreMinChunkX + x;
                    int cz = coreMinChunkZ + z;
                    if (phaseOf(cx, cz, spacing) != phase) continue;
                    ProtoChunk chunk = getChunk(cx, cz);
                    if (chunk == null) continue;
                    // Chunks generated before, and chunks outside the area (halo), get no features here.
                    if (featureSkip.contains(ChunkPos.pack(cx, cz)) || !area.contains(cx, cz)) {
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
                    List<StructurePiece> wide = widePieces(chunk);
                    for (StructurePiece piece : wide) {
                        CompletableFuture<?> previous = lastPlacing.get(piece);
                        if (previous != null) before.add(previous);
                    }
                    int ix = x;
                    int iz = z;
                    CompletableFuture<?> f = CompletableFuture.allOf(before.toArray(new CompletableFuture[0]))
                            .thenApplyAsync(ignored -> {
                                long started = System.nanoTime();
                                try {
                                    return generate(ChunkStatus.FEATURES, chunk);
                                } finally {
                                    took[ix][iz] = System.nanoTime() - started;
                                }
                            }, workerPool)
                            .thenCompose(Function.identity());
                    done[x][z] = f;
                    for (StructurePiece piece : wide) lastPlacing.put(piece, f);
                    all.add(onChunkDone != null ? f.thenRun(onChunkDone) : f);
                }
            }
        }
        CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).join();
        featureDiagnostics = featureStats(took, stageStart, spacing);
    }

    /** Template pieces this wide can reach two chunks 3 apart, which the neighbor order runs at once. */
    private static final int WIDE_PIECE_BLOCKS = 34;

    /**
     * Template pieces the chunk's features place that are wide enough to reach chunks the neighbor
     * order runs at the same time. Such a piece sets its shared place settings to the calling
     * chunk's box before placing (TemplateStructurePiece#postProcess), so two chunks placing it at
     * once would clip to each other's box; the chunks placing one such piece run one after another.
     * The mod builds lock the piece instead.
     */
    private List<StructurePiece> widePieces(ProtoChunk chunk) {
        Map<Structure, LongSet> references = chunk.getAllReferences();
        if (references.isEmpty()) return List.of();
        ChunkPos pos = chunk.getPos();
        BoundingBox column = new BoundingBox(pos.getMinBlockX(), level.getMinY(), pos.getMinBlockZ(),
                pos.getMaxBlockX(), level.getMaxY(), pos.getMaxBlockZ());
        List<StructurePiece> wide = new ArrayList<>(0);
        for (Map.Entry<Structure, LongSet> entry : references.entrySet()) {
            for (long startPos : entry.getValue()) {
                ProtoChunk startChunk = getChunk(ChunkPos.getX(startPos), ChunkPos.getZ(startPos));
                StructureStart start = startChunk != null ? startChunk.getStartForStructure(entry.getKey()) : null;
                if (start == null || !start.isValid()) continue;
                for (StructurePiece piece : start.getPieces()) {
                    BoundingBox box = piece.getBoundingBox();
                    if (piece instanceof TemplateStructurePiece && box.intersects(column)
                            && (box.getXSpan() >= WIDE_PIECE_BLOCKS || box.getZSpan() >= WIDE_PIECE_BLOCKS)) {
                        wide.add(piece);
                    }
                }
            }
        }
        return wide;
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

    /** One chunk's blender and structure view, shared by its biome and noise steps. */
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
            CompletableFuture<?> f = generate(ChunkStatus.SPAWN, chunk)
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
                GridLight.ChunkLight light = Platform.MOONRISE ? keptLight.get(packed) : null;
                // Paper saves the light its own engine keeps in the chunk, and that engine has none
                // for a batch's chunks: saved as not lit, Paper lights them itself when it loads them,
                // and the light worked out here is stored as Minecraft stores it (see withLight).
                if (light != null) chunk.setLightCorrect(false);
                CompoundTag tag = savedData(chunk);
                if (light != null) withLight(tag, light);
                // The worldgen heightmaps too (Minecraft ignores them in a full chunk): a later
                // batch or run reads them back for this chunk as a neighbor (see adoptSaved).
                CompoundTag heightmaps = tag.getCompoundOrEmpty("Heightmaps");
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
                WorldChunkIO.EncodedChunk result = encoded(chunk, tag, version);
                encodeNanos.add(System.nanoTime() - started);
                encodedChunks.increment();
                if (onChunkDone != null) onChunkDone.run();
                return result;
            }, workerPool));
        }

        List<WorldChunkIO.EncodedChunk> encoded = new ArrayList<>(futures.size());
        for (CompletableFuture<WorldChunkIO.EncodedChunk> future : futures) encoded.add(future.join());
        return encoded;
    }

    /** The chunk's saved data as Minecraft writes it (on Folia without its region's game time, see FoliaChunkData). */
    private CompoundTag savedData(ProtoChunk chunk) {
        return Platform.FOLIA ? FoliaChunkData.write(level, chunk)
                : net.minecraft.world.level.chunk.storage.SerializableChunkData.copyOf(level, chunk).write();
    }

    /**
     * The chunk as its storage takes it: framed as a region sector (Minecraft's storage, see
     * WorldChunkIO), or the NBT itself for Paper's, which compresses on its own threads.
     */
    private static WorldChunkIO.EncodedChunk encoded(ProtoChunk chunk, CompoundTag tag, RegionFileVersion version) {
        if (Platform.MOONRISE) return new WorldChunkIO.EncodedChunk(chunk.getPos(), null, tag);
        FramedChunkBuffer framed = new FramedChunkBuffer(version.getId());
        try (DataOutputStream stream = new DataOutputStream(version.wrap(framed))) {
            NbtIo.write(tag, stream);
        } catch (IOException e) {
            throw new UncheckedIOException(String.format("Failed to encode chunk [%d, %d]",
                    chunk.getPos().x(), chunk.getPos().z()), e);
        }
        return new WorldChunkIO.EncodedChunk(chunk.getPos(), framed.frame(), null);
    }

    /**
     * Adds the chunk's light to its saved data as Minecraft's SerializableChunkData stores it (the
     * sections' BlockLight and SkyLight, isLightOn): for Minecraft itself and tools that read region
     * files. Paper, which keeps its light engine's own format, lights such a chunk again when it loads it.
     */
    private void withLight(CompoundTag tag, GridLight.ChunkLight light) {
        ListTag sections = tag.getListOrEmpty("sections");
        Map<Integer, CompoundTag> byY = new HashMap<>();
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompoundOrEmpty(i);
            byY.put((int) section.getByteOr("Y", (byte) 0), section);
        }
        int bottom = level.getMinSectionY() - 1;
        for (int i = 0; i < light.sky().length; i++) {
            DataLayer block = light.block()[i];
            DataLayer sky = light.sky()[i];
            boolean hasBlock = block != null && !block.isEmpty();
            boolean hasSky = sky != null && !sky.isEmpty();
            if (!hasBlock && !hasSky) continue;
            CompoundTag section = byY.get(bottom + i);
            if (section == null) {
                section = new CompoundTag();
                section.putByte("Y", (byte) (bottom + i));
                sections.add(section);
                byY.put(bottom + i, section);
            }
            if (hasBlock) section.putByteArray("BlockLight", block.getData().clone());
            if (hasSky) section.putByteArray("SkyLight", sky.getData().clone());
        }
        tag.put("sections", sections);
        tag.putBoolean("isLightOn", true);
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
            if (!"DUMMY".equals(pending.getValue().getStringOr("id", ""))) continue;
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
     *
     * Completes once the light engine has dropped the data: the next batch's features read light
     * (snow and ice need it dark) next to this grid, and must not see this grid's.
     */
    private CompletableFuture<Void> releaseLightData() {
        long[] positions = new long[gridChunksX * gridChunksZ];
        int index = 0;
        for (int gx = 0; gx < gridChunksX; gx++) {
            for (int gz = 0; gz < gridChunksZ; gz++) {
                positions[index++] = ChunkPos.pack(gridMinChunkX + gx, gridMinChunkZ + gz);
            }
        }
        ServerLevel target = level;
        ThreadedLevelLightEngine engine = lightEngine;
        CompletableFuture<Void> released = new CompletableFuture<>();
        target.getServer().execute(() -> {
            try {
                var map = target.getChunkSource().chunkMap;
                for (long packed : positions) {
                    if (ServerAccess.visibleChunk(map, packed) != null || ServerAccess.updatingChunk(map, packed) != null) continue;
                    ServerAccess.updateChunkStatus(engine, new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed)));
                }
                // Runs after the removals above (they are queued at the highest priority).
                engine.waitForPendingTasks(gridMinChunkX, gridMinChunkZ)
                        .whenComplete((ignored, failure) -> released.complete(null));
                engine.tryScheduleUpdate();
            } catch (Throwable failure) {
                released.completeExceptionally(failure);
                throw failure;
            }
        });
        return released;
    }

    /**
     * Clears internal references to allow JVM garbage collector to reclaim heap immediately.
     * Returns when the grid's light is out of the light engine (see releaseLightData).
     */
    public CompletableFuture<Void> dispose() {
        activeGrids.remove(this);
        if (currentGrid == this) {
            currentGrid = null;
        }
        CompletableFuture<Void> lightReleased = CompletableFuture.completedFuture(null);
        // A grid dropped before its lighting holds no light of its own, and the light engine may be
        // lighting the previous batch meanwhile, which shares edge chunks with it.
        if (chunks != null && lightStarted && !Platform.MOONRISE) {
            lightReleased = releaseLightData();
        }
        keptLight.clear();
        fallbackChunks.clear();
        fallbackHolders.clear();
        holders = null;
        if (chunks != null) {
            for (int gx = 0; gx < gridChunksX; gx++) {
                for (int gz = 0; gz < gridChunksZ; gz++) {
                    chunks[gx][gz] = null;
                }
            }
            chunks = null;
        }
        return lightReleased;
    }
}
