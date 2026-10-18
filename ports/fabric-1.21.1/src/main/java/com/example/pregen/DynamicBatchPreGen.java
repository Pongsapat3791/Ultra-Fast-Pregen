package com.example.pregen;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.BossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.IOException;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.List;
import java.util.HashMap;
import net.minecraft.nbt.CompoundTag;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Manages chunk-aligned tiles and keeps stage work separate from completed world chunks. */
public class DynamicBatchPreGen {
    private static final Logger LOGGER = LoggerFactory.getLogger("PreGenEngine");

    private final ServerLevel level;
    private final int worldSizeBlocks;
    private final int batchSizeBlocks;
    private final int parallelThreads;
    private final PriorityWorkerPool workerPool;
    /** The same workers for a batch tail, whose tasks go first (see PriorityWorkerPool). */
    private final ExecutorService tailPool;
    private final Consumer<String> progressCallback;
    private final ChunkBatchPlan batchPlan;
    private final int centerX;
    private final int centerZ;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean bossBarPending = new AtomicBoolean();
    private final AtomicLong lastBossBarAt = new AtomicLong();
    /** Overall progress for ops online (server thread only, see updateBossBar). */
    private ServerBossEvent bossBar;
    private volatile boolean profiling;
    private volatile PreGenConfig.TerrainPipeline terrainPipeline = PreGenConfig.TerrainPipeline.AUTO;
    private volatile boolean parallelSteps = true;
    private boolean parallelFeatures = true;
    /** parallel_features=always: features on all threads even when other mods keep state in feature placement. */
    private volatile boolean alwaysParallelFeatures;
    private volatile boolean releaseMemoryEachBatch = true;
    /** Keep chunks that exist on disk or in the server's memory (skip_existing_chunks); off for direct API use. */
    private volatile boolean skipExistingChunks;
    /** With skipExistingChunks: per region, the chunks stored when the run started. */
    private WorldChunkIO.StoredChunks storedAtStart = WorldChunkIO.StoredChunks.NONE;
    /** Feature writes across batch boundaries (see SeamWrites). */
    private final SeamWrites seams = new SeamWrites();
    private volatile int existingBatches;
    private final AtomicInteger writtenChunks = new AtomicInteger();
    private final AtomicInteger keptExistingChunks = new AtomicInteger();
    private WorldChunkIO chunkIO;
    private boolean fusedTerrain;
    // At most one tile's encoded chunks are in flight to the server's IO thread at a time.
    private volatile CompletableFuture<WorldChunkIO.WriteResult> pendingWrite;
    /** Lights and saves each batch on its own thread while the next one generates (see BatchTail). */
    private volatile boolean overlapBatches = Boolean.parseBoolean(System.getProperty("fastpregen.overlapBatches", "true"));
    private ExecutorService tailExecutor;
    private volatile CompletableFuture<Void> pendingTail = CompletableFuture.completedFuture(null);
    private volatile BatchTail runningTail;
    /** Batch completion and the progress base of a starting stage change together. */
    private final Object progressLock = new Object();
    private volatile WorldChunkIO.WriteResult pendingWriteResult;

    // Includes the brief queued interval so two start commands cannot race before
    // the coordinator thread enters start(). A task owns its pool for one run only.
    private volatile boolean isRunning = true;
    private volatile boolean isCancelled;
    /** The server is stopping (see stopForServer): the batch being generated is dropped. */
    private volatile boolean serverStopping;
    /** The stopping server no longer waits for this run (see detach): it writes nothing more. */
    private volatile boolean detached;
    private final CountDownLatch ended = new CountDownLatch(1);
    /**
     * After a batch failed on the fast paths: the rest of the run generates like Minecraft
     * (per-status terrain, one chunk at a time), see generateWithFallback.
     */
    private volatile boolean safeMode;
    /** Writes taken from the edge store for the batch being generated, put back if it is generated again. */
    private Map<Long, List<SeamWrites.Write>> takenThisBatch = new HashMap<>();
    /** Set once the batch's edge writes are passed on: from there generating it again would apply them twice. */
    private boolean seamsCommitted;
    /** Test hook (PartialExistingBatchCheck): runs on a worker before a fast-path batch's features, with the batch index. */
    static volatile java.util.function.IntConsumer testFailureBeforeFeatures;
    private volatile int currentBatchIndex;
    private volatile int completedBatchesCount;
    private volatile String currentStageName = "Queued";
    private volatile int currentMinX;
    private volatile int currentMinZ;
    private volatile long totalStartTime = System.currentTimeMillis();
    private volatile long totalEndTime;
    private volatile int completedChunksCount;
    private volatile StageProgress activeStage = new StageProgress("Queued", 0);
    private static final int PROGRESS_PHASES = 7;
    private volatile int currentTileChunks;
    private int currentPhase;
    private volatile boolean generationCompleted;
    /** Shown on the boss bar when a run stops before completing (cancelled or failed). */
    private volatile String stoppedMessage;
    private volatile boolean failed;
    /** Progress, speed and ETA are logged on a fixed 3-second cadence, not per percent. */
    private static final long PROGRESS_LOG_INTERVAL_MS = 3_000;
    private final AtomicLong lastProgressLogAt = new AtomicLong();
    private ScheduledExecutorService progressTicker;
    /** Chunks earlier runs already generated; held in RAM and written back after every tile. */
    private volatile PreGenSaveFile saveFile;
    /** Target chunks skipped because the save file has them; excluded from speed and ETA. */
    private volatile int resumedChunks;
    private volatile int resumedBatches;
    /** Per-step durations of the running batch, logged with its completion line. */
    private final StringBuilder stageTimes = new StringBuilder();
    private String featureDiagnostics = "";
    private String terrainDiagnostics = "";

    /** Each callback captures its stage, so a delayed callback cannot update a later stage. */
    private static final class StageProgress {
        final String name;
        final int total;
        final long startedAt = System.currentTimeMillis();
        final AtomicInteger done = new AtomicInteger();
        volatile long endedAt;
        int baseChunks;
        int tileChunks;
        int phase;
        /** A batch still finishing when this stage started; baseChunks counts it as done. */
        BatchTail overlappedTail;

        StageProgress(String name, int total) {
            this.name = name;
            this.total = total;
        }

        long elapsedMillis() {
            return Math.max(1, (endedAt == 0 ? System.currentTimeMillis() : endedAt) - startedAt);
        }

        double percent() {
            return total > 0 ? Math.min(100.0, done.get() * 100.0 / total) : 0.0;
        }

        double speed() {
            return done.get() * 1000.0 / elapsedMillis();
        }
    }

    /**
     * A batch after its features: lighting, mobs, encoding and saving remain. With overlapBatches
     * these run on the tail thread while the next batch generates structures, terrain and caves;
     * the next batch waits for them before it touches any chunk around it (prepareNeighbors), so
     * reads and writes stay in the order of one batch at a time.
     */
    private static final class BatchTail {
        final int index;
        final int total;
        final ChunkBatchPlan.Tile tile;
        final InMemoryChunkGrid grid;
        final LongSet done;
        final long startedAt;
        final List<WorldChunkIO.EncodedChunk> partGenerated;
        final Map<Long, List<SeamWrites.Write>> intoSaved;
        final String headTimes;
        final String terrainDiagnostics;
        final String featureDiagnostics;
        /** Progress phases the batch finished before its tail. */
        final int firstPhase;
        volatile StageProgress stage;
        volatile boolean finished;

        BatchTail(int index, int total, ChunkBatchPlan.Tile tile, InMemoryChunkGrid grid, LongSet done, long startedAt,
                  List<WorldChunkIO.EncodedChunk> partGenerated, Map<Long, List<SeamWrites.Write>> intoSaved,
                  String headTimes, String terrainDiagnostics, String featureDiagnostics, int firstPhase) {
            this.index = index;
            this.total = total;
            this.tile = tile;
            this.grid = grid;
            this.done = done;
            this.startedAt = startedAt;
            this.partGenerated = partGenerated;
            this.intoSaved = intoSaved;
            this.headTimes = headTimes;
            this.terrainDiagnostics = terrainDiagnostics;
            this.featureDiagnostics = featureDiagnostics;
            this.firstPhase = firstPhase;
        }

        /** Share of the batch's work done. */
        double fraction() {
            StageProgress current = stage;
            if (current == null) return (double) firstPhase / PROGRESS_PHASES;
            return (current.phase + (current.endedAt != 0 ? 1.0 : current.percent() / 100.0)) / PROGRESS_PHASES;
        }
    }

    public DynamicBatchPreGen(ServerLevel level, int worldK, int batchK, int parallelThreads,
                              Consumer<String> progressCallback) {
        this(level, worldK, Math.multiplyExact(batchK, 1000), parallelThreads, 0, 0, progressCallback);
    }

    /**
     * Generates the worldK x worldK square centered on block (centerX, centerZ) of the given world,
     * one batchSizeBlocks x batchSizeBlocks tile at a time.
     */
    public DynamicBatchPreGen(ServerLevel level, int worldK, int batchSizeBlocks, int parallelThreads,
                              int centerX, int centerZ, Consumer<String> progressCallback) {
        this(level, new Area(Math.multiplyExact(worldK, 1000), centerX, centerZ, ChunkBatchPlan.Shape.SQUARE),
                batchSizeBlocks, parallelThreads, progressCallback);
    }

    /** As above, for a square of any side in blocks. */
    static DynamicBatchPreGen square(ServerLevel level, int sizeBlocks, int batchSizeBlocks, int parallelThreads,
                                     int centerX, int centerZ, Consumer<String> progressCallback) {
        return area(level, sizeBlocks, ChunkBatchPlan.Shape.SQUARE, batchSizeBlocks, parallelThreads, centerX, centerZ,
                progressCallback);
    }

    /** The square of that side, or the circle of that diameter, centered on block (centerX, centerZ). */
    static DynamicBatchPreGen area(ServerLevel level, int sizeBlocks, ChunkBatchPlan.Shape shape, int batchSizeBlocks,
                                   int parallelThreads, int centerX, int centerZ, Consumer<String> progressCallback) {
        return new DynamicBatchPreGen(level, new Area(sizeBlocks, centerX, centerZ, shape), batchSizeBlocks,
                parallelThreads, progressCallback);
    }

    /** The area to generate: the side (or diameter) in blocks, the center block and the shape. */
    private record Area(int sizeBlocks, int centerX, int centerZ, ChunkBatchPlan.Shape shape) {}

    private DynamicBatchPreGen(ServerLevel level, Area area, int batchSizeBlocks, int parallelThreads,
                               Consumer<String> progressCallback) {
        this.level = level;
        this.centerX = area.centerX();
        this.centerZ = area.centerZ();
        this.worldSizeBlocks = area.sizeBlocks();
        this.batchSizeBlocks = batchSizeBlocks;
        this.parallelThreads = parallelThreads;
        this.batchPlan = new ChunkBatchPlan(worldSizeBlocks, batchSizeBlocks, this.centerX, this.centerZ, area.shape());
        this.workerPool = new PriorityWorkerPool(parallelThreads, new ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new InMemoryChunkGrid.WorkerThread(r, "PreGen-Worker-" + count.getAndIncrement());
                thread.setDaemon(true);
                // Below the light engine and the server thread (workers would otherwise inherit
                // the server thread's priority 8): the previous batch's lighting, which the next
                // batch's features wait for, then gets the cores it needs while stages 1-3 run.
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            }
        });
        this.tailPool = workerPool.urgent();
        this.progressCallback = progressCallback != null ? progressCallback : message -> {};
    }

    public void cancel() {
        isCancelled = true;
    }

    /**
     * The server is stopping (PregenRuns#stopForServer): the batch being generated is dropped at
     * its next step and the batch being lit and saved finishes. The run is not marked as stopped,
     * so it continues from its last saved batch after the restart; the save file already holds
     * every batch saved so far.
     */
    void stopForServer() {
        serverStopping = true;
        isCancelled = true;
    }

    /** The stopping server waits no longer: the batch still being saved is dropped and nothing more is written. */
    void detach() {
        detached = true;
    }

    /** Whether the run has ended (save file written, listeners told) within the time. */
    boolean awaitEnd(long millis) throws InterruptedException {
        return ended.await(millis, TimeUnit.MILLISECONDS);
    }

    /** Off when the heap cannot hold a batch and the previous one's tail at once (see PreGenConfig#overlapFits). */
    public void setOverlapBatches(boolean overlap) {
        overlapBatches = overlap;
    }

    /** Records a JFR profile of the whole run into the server directory. Call before start(). */
    /** Stage 2 path selection from config; call before start(). */
    void setTerrainPipeline(PreGenConfig.TerrainPipeline pipeline) {
        terrainPipeline = pipeline;
    }

    /** Whether vanilla's one-chunk-at-a-time steps run on every worker; call before start(). */
    /** Whether to collect garbage after every batch, not only at the end; call before start(). */
    void setReleaseMemoryEachBatch(boolean release) {
        releaseMemoryEachBatch = release;
    }

    /** Keep every chunk that already exists instead of overwriting it; call before start(). */
    void setSkipExistingChunks(boolean skip) {
        skipExistingChunks = skip;
    }

    void setParallelSteps(boolean parallel) {
        parallelSteps = parallel;
    }

    void setAlwaysParallelFeatures(boolean always) {
        alwaysParallelFeatures = always;
    }

    public void enableProfiling() {
        profiling = true;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public int getCurrentBatchIndex() {
        return currentBatchIndex;
    }

    public int getCompletedBatchesCount() {
        return completedBatchesCount;
    }

    public int getTotalBatchesCount() {
        return batchPlan.totalBatches();
    }

    public String getCurrentStageName() {
        return currentStageName;
    }

    public String getWorldName() {
        return level != null ? level.dimension().location().toString() : "-";
    }

    public int getCenterX() {
        return centerX;
    }

    /** Side of the square (or diameter of the circle) in blocks. */
    public int getSizeBlocks() {
        return worldSizeBlocks;
    }

    ChunkBatchPlan.Shape getShape() {
        return batchPlan.shape();
    }

    ServerLevel getLevel() {
        return level;
    }

    /** Chunks kept because they already existed or an earlier run generated them. */
    public int getKeptChunksCount() {
        return resumedChunks + keptExistingChunks.get();
    }

    /** Whether the run generated (or kept) every chunk of the area. */
    public boolean isCompleted() {
        return generationCompleted;
    }

    /** Whether the run stopped because of an error. */
    public boolean hasFailed() {
        return failed;
    }

    public int getCenterZ() {
        return centerZ;
    }

    public int getCurrentMinX() {
        return currentMinX;
    }

    public int getCurrentMinZ() {
        return currentMinZ;
    }

    public int getCompletedChunksCount() {
        return completedChunksCount;
    }

    public int getTotalWorldChunks() {
        return batchPlan.totalChunks();
    }

    public double getOverallPercent() {
        return completedChunksCount * 100.0 / getTotalWorldChunks();
    }

    /** Estimated work progress; repeated halo work never counts as completed world chunks. */
    public double getProgressPercent() {
        if (generationCompleted) return 100.0;
        StageProgress stage = activeStage;
        double inFlight = 0.0;
        if (stage.tileChunks > 0) {
            inFlight = stage.baseChunks + stage.tileChunks *
                    (stage.phase + (stage.endedAt != 0 ? 1.0 : stage.percent() / 100.0)) / PROGRESS_PHASES;
            BatchTail tail = stage.overlappedTail;
            // Counted as done in baseChunks; take off what it still has to do.
            if (tail != null && !tail.finished) inFlight -= batchPlan.areaChunks(tail.tile) * (1.0 - tail.fraction());
        }
        return Math.min(99.9, Math.max(completedChunksCount, inFlight) * 100.0 / getTotalWorldChunks());
    }

    private String progressMessage() {
        return String.format("[Ultra Fast Pregen] Progress: %.1f%% | Speed: %.1f chunks/s | ETA: %s",
                getProgressPercent(), getChunksPerSecond(), getEstimatedRemainingTime());
    }

    /** Logs at most once per interval; the first call and forced calls (completion) always log. */
    private void reportProgress() {
        reportProgress(false);
    }

    private void reportProgress(boolean force) {
        long now = System.currentTimeMillis();
        if (force) {
            lastProgressLogAt.set(now);
        } else {
            long last = lastProgressLogAt.get();
            // The compare-and-set lets exactly one of several racing callers log.
            if (last != 0 && now - last < PROGRESS_LOG_INTERVAL_MS) return;
            if (!lastProgressLogAt.compareAndSet(last, now)) return;
        }
        log(progressMessage());
        PregenApiImpl.fireProgress(this);
    }

    /** Keeps the 3-second cadence while no chunk finishes, e.g. during lighting or saving. */
    private void startProgressTicker() {
        progressTicker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "PreGen-Progress");
            thread.setDaemon(true);
            return thread;
        });
        progressTicker.scheduleAtFixedRate(() -> {
            try {
                if (isRunning && !generationCompleted) {
                    reportProgress();
                    long now = System.currentTimeMillis();
                    long last = lastBossBarAt.get();
                    if (now - last >= 1000 && lastBossBarAt.compareAndSet(last, now)) updateBossBar();
                }
            } catch (RuntimeException failure) {
                LOGGER.warn("[PreGen] Progress report failed", failure);
            }
        }, 250, 250, TimeUnit.MILLISECONDS);
    }

    public int getStageDoneChunks() {
        return activeStage.done.get();
    }

    public int getStageTotalChunks() {
        return activeStage.total;
    }

    public double getStagePercent() {
        return activeStage.percent();
    }

    public double getStageChunksPerSecond() {
        return activeStage.speed();
    }

    /**
     * Target chunks the run has effectively finished: completed tiles, or the running tile's
     * estimated share when larger. Halo work and repeated stage callbacks are never counted, but
     * a single-tile run no longer reports 0 chunks/s until its very end.
     */
    private double progressedChunks() {
        return Math.max(completedChunksCount, getProgressPercent() / 100.0 * getTotalWorldChunks());
    }

    /** Chunks this run generated itself; resumed tiles finish instantly and would inflate speed. */
    private double generatedChunks() {
        return Math.max(0.0, progressedChunks() - resumedChunks);
    }

    public int getResumedChunksCount() {
        return resumedChunks;
    }

    public int getResumedBatchesCount() {
        return resumedBatches;
    }

    public double getRealtimeChunksPerSecond() {
        long elapsed = getElapsedMillis();
        return elapsed > 0 ? generatedChunks() * 1000.0 / elapsed : 0.0;
    }

    public long getElapsedMillis() {
        long endedAt = totalEndTime;
        return Math.max(0, (endedAt == 0 ? System.currentTimeMillis() : endedAt) - totalStartTime);
    }

    public String getEstimatedRemainingTime() {
        if (completedChunksCount == getTotalWorldChunks()) return "0s";
        if (!isRunning) return "--";
        long millis = getEstimatedRemainingMillis();
        return millis < 0 ? "Calculating..." : formatDuration(millis);
    }

    /** Estimated time left; -1 while unknown (the first seconds) or after the run ended early. */
    public long getEstimatedRemainingMillis() {
        if (completedChunksCount == getTotalWorldChunks()) return 0;
        if (!isRunning) return -1;
        long elapsed = getElapsedMillis();
        double generated = generatedChunks();
        if (generated <= 0 || elapsed < 3000) return -1;
        return (long) ((getTotalWorldChunks() - progressedChunks()) * elapsed / generated);
    }

    public double getChunksPerSecond() {
        return getRealtimeChunksPerSecond();
    }

    public static String formatDuration(long millis) {
        if (millis < 0) return "0s";
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        long secs = seconds % 60;
        if (hours > 0) return String.format("%dh %02dm %02ds", hours, minutes, secs);
        if (minutes > 0) return String.format("%dm %02ds", minutes, secs);
        return String.format("%ds", secs);
    }

    /** The cell-skipping fill's counters when this run started. */
    private long[] cellsAtStart;

    /** How much of this run's noise fill the cell-skipping fill decided without per-block work. */
    private void logCellSkipping() {
        long[] now = com.example.pregen.terrain.FastNoiseFill.cellCounts();
        long air = now[0] - cellsAtStart[0];
        long solid = now[1] - cellsAtStart[1];
        long block = now[2] - cellsAtStart[2];
        long total = air + solid + block;
        if (total == 0) return;
        log(String.format("[PreGen] Cell-skipping fill: %.0f%% of noise cells decided without per-block work"
                + " (air %d, solid %d, per block %d)", 100.0 * (air + solid) / total, air, solid, block));
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            log("[PreGen] This generation task has already started!");
            return;
        }
        totalStartTime = System.currentTimeMillis();
        cellsAtStart = com.example.pregen.terrain.FastNoiseFill.cellCounts();
        String finalStatus = "Failed";
        startProgressTicker();
        PreGenProfiler profiler = profiling
                ? PreGenProfiler.start(level.getServer().getServerDirectory(), this::log) : null;
        try {
            int tilesAxis = batchPlan.tilesPerAxis();
            int totalBatches = getTotalBatchesCount();
            log(String.format("[PreGen] Target: %s (%d Chunks) in %s centered on [%d, %d] (blocks %d..%d, %d..%d) | Batch: %dx%d blocks | Total Batches: %d | Worker Threads: %d",
                    batchPlan.shape() == ChunkBatchPlan.Shape.CIRCLE
                            ? String.format("circle of radius %d blocks", worldSizeBlocks / 2)
                            : String.format("%dx%d blocks", worldSizeBlocks, worldSizeBlocks),
                    getTotalWorldChunks(), level.dimension().location(),
                    centerX, centerZ, batchPlan.minBlockX(), batchPlan.minBlockX() + worldSizeBlocks - 1,
                    batchPlan.minBlockZ(), batchPlan.minBlockZ() + worldSizeBlocks - 1, batchSizeBlocks,
                    batchSizeBlocks, totalBatches, parallelThreads));
            PregenApiImpl.fireStart(this);
            chunkIO = WorldChunkIO.forLevel(level);
            if (EXPLICIT_GC_DISABLED) {
                log("[PreGen] The JVM runs with -XX:+DisableExplicitGC: memory is returned by its own collections, not after each batch.");
            }
            WorldgenCompat.TerrainChoice terrain = WorldgenCompat.chooseTerrainPath(
                    level.getChunkSource().getGenerator(), terrainPipeline);
            fusedTerrain = terrain.fused();
            List<String> featureState = WorldgenCompat.featurePlacementMixins();
            parallelFeatures = parallelSteps && (featureState.isEmpty() || alwaysParallelFeatures);
            log("[PreGen] Terrain pipeline: " + (terrain.fused() ? "fused fast path" : "Minecraft per-status path")
                    + " (" + terrain.reason() + ") | Cell-skipping noise fill: "
                    + (terrain.fused() && WorldgenCompat.fastNoiseFillAllowed() ? "on" : "off")
                    + " | Structures, caves and features: "
                    + (parallelSteps ? parallelThreads + " threads" : "one chunk at a time (parallel_steps=false)")
                    + (parallelSteps && !parallelFeatures
                        ? ", except features one chunk at a time (other mods keep state in feature placement " + featureState
                            + "; parallel_features=always runs them on all threads too)"
                        : parallelSteps && !featureState.isEmpty()
                            ? ", features too although other mods keep state in feature placement " + featureState
                                + " (parallel_features=always: their features land in other places than chunk by chunk)"
                            : "")
                    + (skipExistingChunks
                        ? ". Chunks that already exist (on disk or loaded) are kept; only missing chunks are generated."
                        : ". skip_existing_chunks=false: every chunk in the area is regenerated and overwritten.")
                    + (overlapBatches
                        ? " Each batch is lit and saved while the next one generates."
                        : " Batches run one at a time (too little free heap to hold two)."));
            openSaveFile();
            findStoredChunks();

            for (int tx = 0; tx < tilesAxis && !isCancelled; tx++) {
                for (int tz = 0; tz < tilesAxis && !isCancelled; tz++) {
                    ChunkBatchPlan.Tile tile = batchPlan.tile(tx, tz);
                    // A circle's corner tiles may hold none of its chunks.
                    if (batchPlan.areaChunks(tile) == 0) continue;
                    currentBatchIndex++;
                    currentMinX = tile.minBlockX();
                    currentMinZ = tile.minBlockZ();
                    long[] core = corePositions(tile);
                    LongSet done = saveFile != null ? saveFile.doneAmong(core) : new LongOpenHashSet();
                    boolean allByFastpregen = done.size() == core.length;
                    if (!allByFastpregen && skipExistingChunks) done.addAll(existingChunks(core));
                    if (done.size() == core.length) {
                        awaitTail();
                        skipGeneratedBatch(currentBatchIndex, totalBatches, tile, allByFastpregen);
                    } else if (overlapBatches) {
                        startTail(generateWithFallback(currentBatchIndex, totalBatches, tile, done));
                    } else {
                        processBatch(currentBatchIndex, totalBatches, tile, done);
                    }
                }
            }

            awaitTail();
            awaitPendingWrites();
            totalEndTime = System.currentTimeMillis();
            log(String.format("[Ultra Fast Pregen] Generated and saved %d chunks; kept %d chunks that already existed (%d whole batches skipped).",
                    writtenChunks.get(), resumedChunks + keptExistingChunks.get(), resumedBatches));
            if (seams.carriedForward() + seams.mergedBack() + seams.borderChunksSaved() > 0) {
                log(String.format("[Ultra Fast Pregen] Edges: %d feature blocks carried into later batches, %d merged into stored chunks"
                                + " (%d existing chunks joined), %d read back from part-generated chunks, %d border chunks saved part-generated.",
                        seams.carriedForward(), seams.mergedBack(), seams.joinedExisting(), seams.importedWrites(),
                        seams.borderChunksSaved()));
            }
            boolean completedAll = completedChunksCount == getTotalWorldChunks()
                    && completedBatchesCount == totalBatches
                    && (pendingWrite == null || (pendingWrite.isDone()
                        && !pendingWrite.isCompletedExceptionally() && !pendingWrite.isCancelled()));
            if (completedAll) {
                generationCompleted = true;
                reportProgress(true);
                updateBossBar();
                finalStatus = "Completed";
                logCellSkipping();
                log(String.format("[PreGen] All %d batches (%d Chunks) completed successfully in %s! Average Speed: %.1f chunks/s",
                        completedBatchesCount, completedChunksCount, formatDuration(getElapsedMillis()), getChunksPerSecond()));
            } else if (!serverStopping) {
                finalStatus = "Cancelled";
                stoppedMessage = String.format("%s: stopped at %.1f%%", PreGenMod.NAME, getProgressPercent());
                log(String.format("[PreGen] Pre-generation was cancelled after %d/%d completed batches (%d/%d chunks, %s).",
                        completedBatchesCount, totalBatches, completedChunksCount,
                        getTotalWorldChunks(), formatDuration(getElapsedMillis())));
            }
        } catch (Throwable failure) {
            if (serverStopping) {
                // The batch being generated was dropped (see stopForServer), or failed as the server closed.
                LOGGER.debug("[PreGen] The batch being generated ended as the server stopped", failure);
            } else {
                LOGGER.error("[PreGen] Error during world pre-generation", failure);
                log("[PreGen] Error during pre-generation: " + failure.getMessage());
                failed = true;
                stoppedMessage = String.format("%s: stopped by an error at %.1f%% (see the server log)",
                        PreGenMod.NAME, getProgressPercent());
            }
        } finally {
            try {
                awaitTail();
            } catch (Throwable failure) {
                if (serverStopping) {
                    LOGGER.info("[PreGen] The batch being saved was dropped as the server stopped: {}", failure.getMessage());
                } else {
                    LOGGER.error("[PreGen] Finishing the last batch failed", failure);
                }
            }
            try {
                awaitPendingWrites();
            } catch (Throwable failure) {
                LOGGER.error("[PreGen] Saving the last tile failed", failure);
            }
            if (totalEndTime == 0) totalEndTime = System.currentTimeMillis();
            if (serverStopping && !generationCompleted) {
                finalStatus = "Stopped by the server stop";
                log(String.format("[PreGen] Stopped by the server stop after %d/%d batches (%d/%d chunks, %s); the run"
                                + " continues from there with resume_after_restart or /pregen resume.",
                        completedBatchesCount, getTotalBatchesCount(), completedChunksCount, getTotalWorldChunks(),
                        formatDuration(getElapsedMillis())));
            }
            if (saveFile != null) {
                saveFile.setLastRun(worldSizeBlocks, centerX, centerZ, batchPlan.shape(), generationCompleted);
                // Not continued after a restart: stopped on purpose, or by an error that would likely recur.
                // A server stop is neither (see stopForServer).
                saveFile.setLastStopped(!generationCompleted && !serverStopping && (isCancelled || failed));
                writeSaveFile();
            }
            if (profiler != null) profiler.finish(this::log);
            // A stopping server waits for this run, and its world or JVM closes anyway.
            if (!serverStopping) {
                currentStageName = "Releasing memory";
                releaseMemory(true, currentBatchIndex);
            }
            currentStageName = finalStatus;
            isRunning = false;
            try {
                PregenApiImpl.fireFinish(this);
                if (progressTicker != null) progressTicker.shutdownNow();
                if (stoppedMessage != null) updateBossBar();
                hideBossBar();
                if (tailExecutor != null) tailExecutor.shutdown();
                workerPool.shutdown();
            } finally {
                ended.countDown();
            }
        }
    }

    /**
     * Tile garbage is only reclaimed when the JVM next collects, and an idle server allocates
     * too little to trigger that, so the heap would otherwise stay inflated after the run.
     * Elapsed time is already frozen, so this does not count against generation speed.
     */
    /** -XX:+DisableExplicitGC (set by some server packs): System.gc() does nothing there. */
    private static final boolean EXPLICIT_GC_DISABLED = java.lang.management.ManagementFactory.getRuntimeMXBean()
            .getInputArguments().contains("-XX:+DisableExplicitGC");

    private void releaseMemory(boolean finalCleanup, int batchIndex) {
        // The JVM's own collections return the memory then; a log line claiming a release would mislead.
        if (EXPLICIT_GC_DISABLED) return;
        try {
            long before = usedHeapMegabytes();
            // Light-data unloads queued by the last tile are applied on another thread. Between
            // batches they are collected one batch later instead of delaying generation.
            if (finalCleanup) Thread.sleep(2000);
            long started = System.nanoTime();
            System.gc();
            Runtime runtime = Runtime.getRuntime();
            log(String.format("%s heap in use %dMB -> %dMB (committed %dMB, max %dMB, GC %.1fs)",
                    finalCleanup ? "[PreGen] Memory cleanup:" : String.format("  [%d/%d] Batch memory release:",
                            batchIndex, getTotalBatchesCount()),
                    before, usedHeapMegabytes(), runtime.totalMemory() / (1024 * 1024),
                    runtime.maxMemory() / (1024 * 1024), (System.nanoTime() - started) / 1e9));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            LOGGER.warn("[PreGen] Memory cleanup failed", failure);
        }
    }

    private static long usedHeapMegabytes() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }

    /** Loads the dimension's record into RAM; without it every chunk is generated as before. */
    private void openSaveFile() {
        try {
            PreGenSaveFile record = PreGenSaveFile.load(level);
            if (record.seedChanged()) {
                log("[Ultra Fast Pregen] Save file " + record.file() + " belongs to another world seed; starting a new record.");
            } else {
                log(String.format("[Ultra Fast Pregen] Save file loaded into RAM: %d chunks already generated in this world (%s).",
                        record.doneChunks(), record.file()));
            }
            record.setLastRun(worldSizeBlocks, centerX, centerZ, batchPlan.shape(), false);
            record.setLastStopped(false);
            saveFile = record;
            writeSaveFile();
        } catch (IOException | RuntimeException failure) {
            LOGGER.error("[PreGen] Could not read the save file", failure);
            log("[Ultra Fast Pregen] Could not read the save file (" + failure.getMessage()
                    + "); every chunk is generated and progress is not recorded.");
        }
    }

    private void writeSaveFile() {
        PreGenSaveFile record = saveFile;
        // Detached: the world is closing, and a singleplayer world opened next may own the file.
        if (record == null || detached) return;
        try {
            record.save();
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("[PreGen] Could not write the save file {}", record.file(), failure);
        }
    }

    /** A batch whose every core chunk already exists; counted as done without generating. */
    private void skipGeneratedBatch(int index, int total, ChunkBatchPlan.Tile tile, boolean allByFastpregen) {
        synchronized (progressLock) {
            completedChunksCount += batchPlan.areaChunks(tile);
            completedBatchesCount++;
        }
        resumedChunks += batchPlan.areaChunks(tile);
        resumedBatches++;
        if (!allByFastpregen) existingBatches++;
        for (long pos : corePositions(tile)) {
            seams.markHandled(pos, false);
            seams.take(pos);
        }
        log(String.format("[%d/%d] Skipped Tile at Block [%d, %d]: all %d chunks %s.",
                index, total, tile.minBlockX(), tile.minBlockZ(), batchPlan.areaChunks(tile),
                allByFastpregen ? "were already generated by Ultra Fast Pregen" : "already exist"));
        reportProgress();
    }

    /**
     * Reads, once, which chunks of the area and the ring around it exist (region headers and
     * status only), so batches do not queue a disk check behind the previous batch's writes.
     * Chunks the server saves later are still never overwritten: writes skip chunks present
     * when they are written.
     */
    private void findStoredChunks() {
        LongOpenHashSet regions = new LongOpenHashSet();
        int minChunkX = Math.floorDiv(batchPlan.minBlockX(), 16) - 1;
        int minChunkZ = Math.floorDiv(batchPlan.minBlockZ(), 16) - 1;
        int maxChunkX = minChunkX + batchPlan.chunksX() + 1;
        int maxChunkZ = minChunkZ + batchPlan.chunksZ() + 1;
        for (int rx = minChunkX >> 5; rx <= maxChunkX >> 5; rx++) {
            for (int rz = minChunkZ >> 5; rz <= maxChunkZ >> 5; rz++) {
                regions.add(ChunkPos.asLong(rx, rz));
            }
        }
        long started = System.nanoTime();
        storedAtStart = await(chunkIO.findStoredInRegions(regions), "reading the area's region headers");
        long stored = 0;
        for (int x = minChunkX + 1; x < maxChunkX; x++) {
            for (int z = minChunkZ + 1; z < maxChunkZ; z++) {
                if (WorldChunkIO.isStored(storedAtStart.full(), ChunkPos.asLong(x, z))) stored++;
            }
        }
        log(String.format("[Ultra Fast Pregen] Existing chunks: %d fully generated in the area (checked %d region files in %.1fs).",
                stored, regions.size(), (System.nanoTime() - started) / 1e9));
    }

    /**
     * Prepares the chunks around and among this batch's new chunks (see SeamWrites). Chunks
     * generated before (kept core chunks, earlier batches, the stored world) get their final
     * blocks and take writes; halo chunks not generated yet are generated here and get the
     * writes earlier batches or stored part-generated chunks hold for them; new core chunks get
     * theirs before their features. Chunks the server holds are left to the server. Returns
     * those held chunks.
     */
    private LongSet prepareNeighbors(InMemoryChunkGrid grid, LongSet done, LongSet keptCore, LongSet freshHalo) {
        long[] terrain = grid.getTerrainPositions();
        LongSet held = await(WorldChunkIO.findHeldByServer(level, LongArrayList.wrap(terrain)), "the server's loaded chunk list");
        LongArrayList readFinal = new LongArrayList();
        LongArrayList readPart = new LongArrayList();
        LongArrayList newCore = new LongArrayList();
        for (long pos : terrain) {
            boolean isCore = grid.isCore(pos);
            if (isCore && !done.contains(pos)) {
                newCore.add(pos);
                if (SeamWrites.joinStoredChunks && storedPartGenerated(pos)) readPart.add(pos);
                continue;
            }
            if (isCore) seams.take(pos);
            if (held.contains(pos)) continue;
            SeamWrites.Final known = seams.finalOf(pos);
            if (known != null) {
                grid.adoptSaved(pos, known);
                if (isCore) keptCore.add(pos);
            } else if (!SeamWrites.joinStoredChunks) {
                if (!isCore) freshHalo.add(pos);
            } else if (isCore || generatedBefore(pos)) {
                readFinal.add(pos);
            } else {
                freshHalo.add(pos);
                if (storedPartGenerated(pos)) readPart.add(pos);
            }
        }
        Map<Long, CompletableFuture<java.util.Optional<CompoundTag>>> reads = new HashMap<>();
        for (long pos : readFinal) reads.put(pos, chunkIO.load(pos));
        for (long pos : readPart) reads.put(pos, chunkIO.load(pos));
        int minSection = level.getMinSection();
        int sections = level.getSectionsCount();
        for (long pos : readFinal) {
            CompoundTag tag = await(reads.get(pos), "reading a stored chunk").orElse(null);
            SeamWrites.Final stored = tag == null ? null : SeamWrites.finalFromStored(tag, minSection, sections);
            // Otherwise not a fully generated chunk of this version: generated again here and
            // never written.
            if (stored == null) continue;
            seams.keepFinal(pos, stored, minSection);
            seams.markExisting(pos);
            grid.adoptSaved(pos, stored);
            if (grid.isCore(pos)) keptCore.add(pos);
        }
        for (long pos : readPart) {
            seams.markStoredRead(pos);
            CompoundTag tag = await(reads.get(pos), "reading a stored chunk").orElse(null);
            if (tag != null && SeamWrites.importStoredChunks) {
                seams.importStored(pos, SeamWrites.diffStored(tag, grid.getChunk(ChunkPos.getX(pos), ChunkPos.getZ(pos))));
            }
        }
        for (long pos : newCore) {
            List<SeamWrites.Write> writes = seams.take(pos);
            if (!writes.isEmpty()) {
                takenThisBatch.put(pos, writes);
                SeamWrites.applyToGenerating(grid.getChunk(ChunkPos.getX(pos), ChunkPos.getZ(pos)), writes);
            }
        }
        for (long pos : freshHalo) {
            List<SeamWrites.Write> writes = seams.peek(pos);
            if (!writes.isEmpty()) SeamWrites.applyToGenerating(grid.getChunk(ChunkPos.getX(pos), ChunkPos.getZ(pos)), writes);
        }
        return held;
    }

    /** Stored part-generated when the run started and not read or replaced since. */
    private boolean storedPartGenerated(long pos) {
        return !seams.storedWasRead(pos) && WorldChunkIO.isStored(storedAtStart.present(), pos)
                && !WorldChunkIO.isStored(storedAtStart.full(), pos);
    }

    /**
     * A halo chunk stored fully generated that this run keeps: outside the area, or in it and
     * recorded in the save file or kept by skip_existing_chunks.
     */
    private boolean generatedBefore(long pos) {
        boolean full = WorldChunkIO.isStored(storedAtStart.full(), pos);
        int x = ChunkPos.getX(pos);
        int z = ChunkPos.getZ(pos);
        if (!batchPlan.containsChunk(x, z)) return full;
        boolean recorded = saveFile != null && saveFile.isDone(x, z);
        if (seams.isHandled(pos)) return recorded || full;
        return recorded || (skipExistingChunks && full);
    }

    /** Core chunks fully generated when the run started (on disk), or held fully generated by the server now. */
    private LongSet existingChunks(long[] core) {
        LongSet existing = new LongOpenHashSet();
        LongArrayList notStored = new LongArrayList();
        for (long pos : core) {
            if (WorldChunkIO.isStored(storedAtStart.full(), pos)) existing.add(pos);
            else notStored.add(pos);
        }
        if (!notStored.isEmpty()) {
            existing.addAll(await(WorldChunkIO.findHeldFullByServer(level, notStored), "the server's loaded chunk list"));
        }
        return existing;
    }

    private void processBatch(int index, int total, ChunkBatchPlan.Tile tile) {
        processBatch(index, total, tile, new LongOpenHashSet());
    }

    /**
     * generateBatch, and when it fails on the fast paths (most likely another mod's code that
     * does not expect them), the same batch again the way Minecraft generates chunks: the
     * per-status terrain path and one chunk at a time for structures, carvers and features.
     * The rest of the run continues that way. The batch fails for good when that fails too, or
     * when it failed after its edge writes were passed on to other chunks.
     */
    private BatchTail generateWithFallback(int index, int total, ChunkBatchPlan.Tile tile, LongSet done) {
        try {
            return generateBatch(index, total, tile, done);
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            if (safeMode || isCancelled || seamsCommitted) throw failure;
            Throwable cause = failure;
            while ((cause instanceof java.util.concurrent.CompletionException || cause instanceof ExecutionException)
                    && cause.getCause() != null) {
                cause = cause.getCause();
            }
            String involved = involvedMods(cause);
            LOGGER.warn("[PreGen] [{}/{}] Batch failed on the fast paths{}", index, total,
                    involved.isEmpty() ? "" : " (involving " + involved + ")", failure);
            log(String.format("[PreGen] [%d/%d] Batch failed on the fast paths%s: %s. Generating it again the way Minecraft does"
                            + " (per-status terrain, one chunk at a time); the rest of this run continues that way."
                            + " terrain_pipeline=vanilla and parallel_steps=false do this from the start.",
                    index, total, involved.isEmpty() ? "" : " (involving " + involved + ")", cause));
            safeMode = true;
            for (Map.Entry<Long, List<SeamWrites.Write>> taken : takenThisBatch.entrySet()) {
                seams.putBack(taken.getKey(), taken.getValue());
            }
            takenThisBatch = new HashMap<>();
            return generateBatch(index, total, tile, done);
        }
    }

    /**
     * Other mods in a failure's stack trace: mod ids from their mixin handlers' names (Mixin
     * names them handler$...$modid$...) and the first classes outside Minecraft, its libraries
     * and this mod.
     */
    static String involvedMods(Throwable failure) {
        java.util.Set<String> mods = new java.util.LinkedHashSet<>();
        java.util.regex.Pattern handler = java.util.regex.Pattern.compile(
                "^(?:handler|redirect|modify[a-zA-Z]*|localvar|wrapOperation|wrapWithCondition|wrapMethod)\\$[a-z0-9]+\\$([a-z0-9_.-]+)\\$");
        List<String> ownPackages = List.of("net.minecraft.", "com.mojang.", "java.", "jdk.", "sun.", "com.example.pregen.",
                "it.unimi.", "com.google.", "org.spongepowered.", "com.llamalad7.", "net.neoforged.", "net.fabricmc.",
                "net.minecraftforge.", "cpw.mods.", "org.slf4j.", "org.apache.");
        String firstOther = null;
        for (Throwable t = failure; t != null && mods.size() < 4; t = t.getCause()) {
            for (StackTraceElement frame : t.getStackTrace()) {
                java.util.regex.Matcher matcher = handler.matcher(frame.getMethodName());
                if (matcher.find() && !matcher.group(1).equals(PreGenMod.MOD_ID)) mods.add(matcher.group(1));
                String type = frame.getClassName();
                if (firstOther == null && ownPackages.stream().noneMatch(type::startsWith)) firstOther = type;
            }
        }
        if (firstOther != null) mods.add(firstOther);
        return String.join(", ", mods);
    }

    /** Generates and saves the tile; chunks in {@code done} were saved by an earlier run and are kept. */
    private void processBatch(int index, int total, ChunkBatchPlan.Tile tile, LongSet done) {
        finishBatch(generateWithFallback(index, total, tile, done), false);
    }

    /** Runs the tile's tail on the tail thread; the previous tail has finished (see generateBatch). */
    private void startTail(BatchTail tail) {
        awaitTail();
        if (tailExecutor == null) {
            tailExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "PreGen-Tail");
                thread.setDaemon(true);
                return thread;
            });
        }
        runningTail = tail;
        pendingTail = CompletableFuture.runAsync(() -> finishBatch(tail, true), tailExecutor);
    }

    /** Waits for the batch finishing on the tail thread and rethrows its failure once. */
    private void awaitTail() {
        CompletableFuture<Void> tail = pendingTail;
        try {
            tail.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while finishing the previous batch", interrupted);
        } catch (ExecutionException failure) {
            pendingTail = CompletableFuture.completedFuture(null);
            throw new IllegalStateException("Finishing a batch failed", failure.getCause());
        }
    }

    /** The server is stopping (see stopForServer): the batch being generated is dropped. */
    private void dropBatchIfServerStopping() {
        if (serverStopping) throw new CancellationException("the server is stopping");
    }

    /** The stopping server waits no longer (see detach): the batch being lit and saved is dropped. */
    private void dropTailIfDetached() {
        if (detached) throw new CancellationException("the server stopped");
    }

    /**
     * Structures through features of the tile, and the edge bookkeeping that depends on them;
     * returns what remains (finishBatch). Waits for the previous batch's tail before preparing
     * this batch's neighbors.
     */
    private BatchTail generateBatch(int index, int total, ChunkBatchPlan.Tile tile, LongSet done) {
        long batchStartedAt = System.currentTimeMillis();
        takenThisBatch = new HashMap<>();
        seamsCommitted = false;
        currentTileChunks = batchPlan.areaChunks(tile);
        currentPhase = 0;
        stageTimes.setLength(0);
        currentStageName = "Preparing batch";
        reportProgress();
        updateBossBar();
        log(String.format("[%d/%d] (%.1f%%) Starting Tile at Block [%d, %d] (Core Chunks: %dx%d = %d%s)...",
                index, total, getProgressPercent(), tile.minBlockX(), tile.minBlockZ(),
                tile.chunksX(), tile.chunksZ(), currentTileChunks,
                done.isEmpty() ? "" : ", " + done.size() + " that already exist are kept"));

        long[] core = corePositions(tile);
        // Start generation immediately; every core chunk not generated before is overwritten.
        LongSet existing = done;

        InMemoryChunkGrid grid = new InMemoryChunkGrid(level, tile.minBlockX(), tile.minBlockZ(),
                tile.chunksX(), tile.chunksZ(), fusedTerrain && !safeMode, parallelSteps && !safeMode);
        grid.setArea(batchPlan::containsChunk);
        grid.setParallelFeatures(parallelFeatures && !safeMode);
        grid.setWorkerThreads(parallelThreads);
        InMemoryChunkGrid.setCurrentGrid(grid);
        boolean handedOver = false;
        try {
            dropBatchIfServerStopping();
            Runnable progress = startStage(index, total, "Stage 1/6: Structure Starts & References",
                    grid.getStructureStageChunks());
            grid.executeLayer(ChunkLayer.STRUCTURES, workerPool, progress);
            endStage();

            dropBatchIfServerStopping();
            progress = startStage(index, total, "Stage 2/6: Biomes, Noise & Surface", grid.getPaddedChunksCount());
            grid.executeLayer(ChunkLayer.TERRAIN_PIPELINE, workerPool, progress);
            endStage();
            terrainDiagnostics = grid.getTerrainDiagnostics();

            dropBatchIfServerStopping();
            progress = startStage(index, total, "Stage 3/6: Caves & Aquifers", grid.getPaddedChunksCount());
            grid.executeLayer(ChunkLayer.CARVERS, workerPool, progress);
            endStage();
            // The previous batch's saves and edge merges are queued before this batch reads any
            // stored chunk, as when batches run one at a time.
            awaitTail();

            LongSet heldByServer = LongSets.EMPTY_SET;
            LongSet keptCore = new LongOpenHashSet();
            LongSet freshHalo = new LongOpenHashSet();
            if (SeamWrites.enabled) {
                heldByServer = prepareNeighbors(grid, done, keptCore, freshHalo);
            } else {
                for (long pos : core) {
                    List<SeamWrites.Write> carried = seams.take(pos);
                    if (!carried.isEmpty() && !done.contains(pos)) {
                        takenThisBatch.put(pos, carried);
                        SeamWrites.applyToGenerating(grid.getChunk(ChunkPos.getX(pos), ChunkPos.getZ(pos)), carried);
                    }
                }
            }
            grid.setFeatureSkip(keptCore);
            grid.snapshotHalo(keptCore);

            java.util.function.IntConsumer failureHook = testFailureBeforeFeatures;
            if (failureHook != null && !safeMode) {
                CompletableFuture.runAsync(() -> failureHook.accept(index), workerPool).join();
            }
            dropBatchIfServerStopping();
            progress = startStage(index, total, "Stage 4/6: Features Decoration", grid.getCoreTotalChunks());
            grid.executePhased(ChunkLayer.FEATURES, workerPool, progress);
            endStage();
            featureDiagnostics = grid.getFeatureDiagnostics();
            dropBatchIfServerStopping();
            seamsCommitted = true;
            for (long pos : core) seams.markHandled(pos, !done.contains(pos));
            LongSet held = heldByServer;
            Map<Long, List<SeamWrites.Write>> intoSaved = SeamWrites.enabled
                    ? seams.route(grid.collectHaloWrites(workerPool), batchPlan, held::contains) : Map.of();
            List<WorldChunkIO.EncodedChunk> partGenerated = List.of();
            if (SeamWrites.enabled) {
                // Final blocks of this batch's edge chunks, for the halo of batches still to come.
                for (long pos : core) {
                    if (done.contains(pos) || !seams.hasUnhandledNeighbor(pos, batchPlan)) continue;
                    seams.keepFinal(pos, grid.getChunk(ChunkPos.getX(pos), ChunkPos.getZ(pos)));
                }
                // Halo chunks that do not exist yet but received blocks: saved part-generated.
                LongArrayList border = new LongArrayList();
                for (long pos : freshHalo) {
                    if (SeamWrites.joinStoredChunks && seams.hasPending(pos) && !seams.isMergeable(pos)
                            && !WorldChunkIO.isStored(storedAtStart.full(), pos)) {
                        border.add(pos);
                        seams.markStoredRead(pos);
                    }
                }
                partGenerated = grid.encodePartGenerated(border, workerPool);
                seams.countBorderSaved(border.size());
                seams.pruneFinals(batchPlan);
            }
            BatchTail tail = new BatchTail(index, total, tile, grid, existing, batchStartedAt, partGenerated, intoSaved,
                    stageTimes.toString(), terrainDiagnostics, featureDiagnostics, currentPhase);
            handedOver = true;
            return tail;
        } finally {
            if (!handedOver) grid.dispose();
        }
    }

    /** Lighting, mobs, encoding and saving of a generated tile (see BatchTail); disposes its grid. */
    private void finishBatch(BatchTail tail, boolean overlapped) {
        int index = tail.index;
        int total = tail.total;
        ChunkBatchPlan.Tile tile = tail.tile;
        InMemoryChunkGrid grid = tail.grid;
        LongSet skip = tail.done;
        List<WorldChunkIO.EncodedChunk> partGenerated = tail.partGenerated;
        Map<Long, List<SeamWrites.Write>> intoSaved = tail.intoSaved;
        // Running beside the next batch, this batch keeps its own step times off the shown stage.
        StringBuilder times = overlapped ? new StringBuilder(tail.headTimes) : stageTimes;
        CompletableFuture<Void> lightReleased;
        try {
            // The server's chunks inside the grid now: those this batch replaces are lit from the
            // grid like the rest, the others stay the server's (see InMemoryChunkGrid#chunkForLighting).
            LongSet heldAtLight = await(WorldChunkIO.findHeldByServer(level, LongArrayList.wrap(grid.getTerrainPositions())),
                    "the server's loaded chunk list");
            LongSet replaced = new LongOpenHashSet();
            // Core chunks left as they are: those that existed when the batch began, and those the
            // server finished meanwhile. The grid does not light those, so it adds no mobs to them
            // (Minecraft's spawn step needs the chunk lit) and does not save them.
            LongSet kept = skip;
            if (!heldAtLight.isEmpty()) {
                LongArrayList heldCore = new LongArrayList();
                for (long pos : heldAtLight) if (grid.isCore(pos) && !skip.contains(pos)) heldCore.add(pos);
                // When keeping existing chunks, one the server holds fully generated stays (a player's).
                LongSet heldFull = skipExistingChunks && !heldCore.isEmpty()
                        ? await(WorldChunkIO.findHeldFullByServer(level, heldCore), "the server's loaded chunk list")
                        : LongSets.EMPTY_SET;
                for (long pos : heldCore) if (!heldFull.contains(pos)) replaced.add(pos);
                if (!heldFull.isEmpty()) {
                    kept = new LongOpenHashSet(skip);
                    kept.addAll(heldFull);
                }
            }
            grid.setLightOwnership(heldAtLight, replaced);
            dropTailIfDetached();
            Runnable progress = startTailStage(tail, overlapped, "Stage 5/6: Lighting", grid.getCoreTotalChunks());
            grid.executePhased(ChunkLayer.LIGHTING, tailPool, progress);
            endTailStage(tail, overlapped, times);

            int areaChunks = batchPlan.areaChunks(tile);
            dropTailIfDetached();
            progress = startTailStage(tail, overlapped, "Stage 6/6: Mob Spawning", areaChunks - kept.size());
            grid.executeSpawn(tailPool, kept, progress);
            endTailStage(tail, overlapped, times);

            dropTailIfDetached();
            progress = startTailStage(tail, overlapped, "Saving: encoding chunks", areaChunks - kept.size());
            List<WorldChunkIO.EncodedChunk> encoded = grid.encodeForSaving(tailPool, kept, progress);
            endTailStage(tail, overlapped, times);
            // Chunks the server holds get the generated terrain too, or its later save would
            // overwrite ours; when keeping existing chunks, only part-way generated ones.
            dropTailIfDetached();
            LoadedChunkRegeneration.applyLit(level, grid, replaced);
            LOGGER.debug(grid.getEncodeDiagnostics() + String.format(" | Spawned mobs: %d", grid.getSpawnedEntities()));
            keptExistingChunks.addAndGet(kept.size());
            submitWrites(index, total, encoded, !overlapped);
            if (!partGenerated.isEmpty()) {
                // Never over a chunk stored fully generated meanwhile (a player's).
                WorldChunkIO.PendingWrite border = chunkIO.write(partGenerated, false);
                pendingWrite = pendingWrite.thenCombine(border.done(), (result, saved) -> result);
            }
            if (!intoSaved.isEmpty()) {
                // Ordered after this batch's writes on the IO thread; awaited with them.
                CompletableFuture<Integer> merge = chunkIO.mergeIntoSaved(intoSaved).exceptionally(failure -> {
                    LOGGER.warn("[PreGen] Could not merge feature edges into earlier batches", failure);
                    return 0;
                });
                pendingWrite = pendingWrite.thenCombine(merge, (result, merged) -> {
                    LOGGER.debug("  [{}/{}] Merged feature edges into {} chunks of earlier batches", index, total, merged);
                    return result;
                });
            }

            synchronized (progressLock) {
                completedChunksCount += areaChunks;
                completedBatchesCount++;
                tail.finished = true;
            }
            reportProgress();
        } finally {
            lightReleased = grid.dispose();
        }
        // The next batch's features read light next to this tile; its light leaves the engine first.
        awaitLightRelease(lightReleased, index, total);

        // The tile's chunks are released (dispose); only its encoded bytes wait for the IO thread.
        if (releaseMemoryEachBatch && !isCancelled) releaseMemory(false, index);
        long batchElapsed = System.currentTimeMillis() - tail.startedAt;
        long usedMemoryMB = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        log(String.format("[%d/%d] Completed Tile in %.1fs | Total: %.1f%% (%d/%d chunks) | ETA: %s | RAM: %dMB",
                index, total, batchElapsed / 1000.0, getProgressPercent(), completedChunksCount,
                getTotalWorldChunks(), getEstimatedRemainingTime(), usedMemoryMB));
        LOGGER.info("  [{}/{}] Step times: {} | {} | {}", index, total, times, tail.terrainDiagnostics, tail.featureDiagnostics);
        updateBossBar();
    }

    /** A tail step: the shown stage when batches run one at a time, otherwise tracked on the tail only. */
    private Runnable startTailStage(BatchTail tail, boolean overlapped, String name, int totalChunks) {
        if (!overlapped) return startStage(tail.index, tail.total, name, totalChunks);
        StageProgress stage = new StageProgress(name, totalChunks);
        StageProgress previous = tail.stage;
        stage.tileChunks = batchPlan.areaChunks(tail.tile);
        stage.phase = previous == null ? tail.firstPhase : previous.phase + 1;
        tail.stage = stage;
        return stage.done::incrementAndGet;
    }

    private void endTailStage(BatchTail tail, boolean overlapped, StringBuilder times) {
        if (!overlapped) {
            endStage();
            return;
        }
        StageProgress stage = tail.stage;
        stage.endedAt = System.currentTimeMillis();
        appendStageTime(times, stage);
        stage.done.set(stage.total);
    }

    /** The tile's chunks that lie in the area. */
    private long[] corePositions(ChunkBatchPlan.Tile tile) {
        int minX = Math.floorDiv(tile.minBlockX(), 16);
        int minZ = Math.floorDiv(tile.minBlockZ(), 16);
        long[] positions = new long[batchPlan.areaChunks(tile)];
        int index = 0;
        for (int cx = minX; cx < minX + tile.chunksX(); cx++) {
            for (int cz = minZ; cz < minZ + tile.chunksZ(); cz++) {
                if (batchPlan.containsChunk(cx, cz)) positions[index++] = ChunkPos.asLong(cx, cz);
            }
        }
        return positions;
    }

    private void submitWrites(int index, int total, List<WorldChunkIO.EncodedChunk> encoded, boolean showStage) {
        awaitPendingWrites(showStage);
        long submittedAt = System.nanoTime();
        // Positions only, so the callback does not keep the encoded bytes alive.
        List<ChunkPos> positions = encoded.stream().map(WorldChunkIO.EncodedChunk::pos).toList();
        WorldChunkIO.PendingWrite writes = chunkIO.write(encoded, !skipExistingChunks);
        pendingWriteResult = writes.progress();
        pendingWrite = writes.done().whenComplete((result, failure) -> {
            if (failure != null) {
                LOGGER.error("[PreGen] Saving tile {} failed", index, failure);
                return;
            }
            writtenChunks.addAndGet(result.written);
            keptExistingChunks.addAndGet(result.skippedExisting);
            // Recorded only once the tile is on disk, so a crash or stop regenerates it next time.
            PreGenSaveFile record = saveFile;
            if (record != null) {
                record.markDone(positions);
                record.clearAutoResumes();
                writeSaveFile();
            }
            PregenApiImpl.fireChunksSaved(level, positions);
            log(String.format("  [%d/%d] Saved %d chunks to %d region files in %.1fs (disk %.1fs)%s",
                    index, total, result.written, result.regions, (System.nanoTime() - submittedAt) / 1e9, result.ioNanos / 1e9,
                    result.skippedExisting > 0 ? ", kept " + result.skippedExisting + " the server saved meanwhile" : ""));
        });
    }

    /**
     * Rethrows a failed write, so a broken save stops the run instead of losing tiles silently.
     * Logs progress while waiting, and stops waiting if the server shuts down: its IO worker
     * then drops queued tasks, so the remaining chunks are simply generated later by the game.
     */
    private void awaitPendingWrites() {
        awaitPendingWrites(true);
    }

    /** {@code showStage}: shows the wait as the current stage (not while another batch runs). */
    private void awaitPendingWrites(boolean showStage) {
        CompletableFuture<WorldChunkIO.WriteResult> pending = pendingWrite;
        if (pending == null || pending.isDone()) {
            if (pending != null) pending.join();
            return;
        }
        String stage = currentStageName;
        long startedAt = System.nanoTime();
        try {
            while (true) {
                WorldChunkIO.WriteResult progress = pendingWriteResult;
                if (showStage) {
                    currentStageName = progress == null ? "Saving to disk"
                            : String.format("Saving to disk (%d/%d chunks)", progress.processed(), progress.total);
                }
                try {
                    pending.get(10, TimeUnit.SECONDS);
                    return;
                } catch (TimeoutException stillWriting) {
                    if (level != null && !level.getServer().isRunning()) {
                        log("[PreGen] Server is stopping; unsaved generated chunks will be generated normally later.");
                        return;
                    }
                    if (progress != null) {
                        LOGGER.debug(String.format("  Saving to disk: %d/%d chunks, %d/%d region files (%.0fs)",
                                progress.processed(), progress.total, progress.regionsDone, progress.regions,
                                (System.nanoTime() - startedAt) / 1e9));
                    }
                    reportProgress();
                    updateBossBar();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException failure) {
                    throw new IllegalStateException("Saving chunks failed", failure.getCause());
                }
            }
        } finally {
            if (showStage) currentStageName = stage;
        }
    }

    /**
     * Waits (bounded) for a disposed grid's light to leave the light engine. A server that stops
     * meanwhile no longer runs the release; nothing generates after that either.
     */
    private void awaitLightRelease(CompletableFuture<Void> released, int index, int total) {
        if (isCancelled || !level.getServer().isRunning()) return;
        try {
            released.get(2, TimeUnit.MINUTES);
        } catch (TimeoutException timeout) {
            LOGGER.warn("[PreGen] [{}/{}] The light engine did not release the batch's light within 2 minutes", index, total);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException failure) {
            LOGGER.warn("[PreGen] [{}/{}] Releasing the batch's light failed", index, total, failure.getCause());
        }
    }

    private static <T> T await(CompletableFuture<T> future, String what) {
        try {
            return future.get(2, TimeUnit.MINUTES);
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("Timed out waiting for " + what + " (is the server thread busy?)", timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + what, interrupted);
        } catch (ExecutionException failure) {
            throw new IllegalStateException(what + " failed", failure.getCause());
        }
    }

    private Runnable startStage(int batchIndex, int totalBatches, String name, int totalChunks) {
        StageProgress stage = new StageProgress(name, totalChunks);
        synchronized (progressLock) {
            BatchTail tail = runningTail;
            stage.overlappedTail = tail != null && !tail.finished ? tail : null;
            stage.baseChunks = completedChunksCount
                    + (stage.overlappedTail != null ? batchPlan.areaChunks(stage.overlappedTail.tile) : 0);
        }
        stage.tileChunks = currentTileChunks;
        stage.phase = currentPhase++;
        activeStage = stage;
        currentStageName = name;
        reportProgress();
        updateBossBar();
        return () -> onChunkDoneInStage(stage);
    }

    private void onChunkDoneInStage(StageProgress stage) {
        if (activeStage != stage) return;
        stage.done.incrementAndGet();
        if (stage.total <= 0 || activeStage != stage) return;

        reportProgress();
    }

    private void endStage() {
        StageProgress stage = activeStage;
        stage.endedAt = System.currentTimeMillis();
        appendStageTime(stageTimes, stage);
        // Includes steps with zero work (e.g. no new chunks to spawn or encode).
        stage.done.set(stage.total);
        reportProgress();
        updateBossBar();
    }

    private static void appendStageTime(StringBuilder times, StageProgress stage) {
        times.append(times.length() == 0 ? "" : " | ").append(shortStageName(stage.name))
                .append(' ').append(String.format("%.1fs", (stage.endedAt - stage.startedAt) / 1000.0));
    }

    /** "Stage 2/6: Biomes, Noise & Surface" -> "Biomes, Noise & Surface". */
    private static String shortStageName(String name) {
        int colon = name.indexOf(": ");
        return colon >= 0 ? name.substring(colon + 2) : name;
    }

    /**
     * The boss bar ops online see during the run: overall progress, speed and ETA, then how the
     * run ended. Ops who join meanwhile get it on the next update (every second); it goes a few
     * seconds after the end. Players get no chat messages; the server log has every event.
     */
    private void updateBossBar() {
        if (level == null || serverStopping) return;
        if (!bossBarPending.compareAndSet(false, true)) return;
        level.getServer().execute(() -> {
            try {
                String stopped = stoppedMessage;
                if (!isRunning && !generationCompleted && stopped == null) return;
                if (bossBar == null) {
                    bossBar = new ServerBossEvent(Component.literal(PreGenMod.NAME), BossEvent.BossBarColor.GREEN,
                            BossEvent.BossBarOverlay.PROGRESS);
                }
                double percent = getProgressPercent();
                String name;
                if (generationCompleted) {
                    name = String.format("%s: done in %s", PreGenMod.NAME, formatDuration(getElapsedMillis()));
                } else if (stopped != null) {
                    name = stopped;
                    bossBar.setColor(failed ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.YELLOW);
                } else {
                    name = String.format("%s %.1f%% | %.1f chunks/s | ETA %s", PreGenMod.NAME, percent,
                            getChunksPerSecond(), getEstimatedRemainingTime());
                }
                bossBar.setName(Component.literal(name));
                bossBar.setProgress((float) Math.max(0.0, Math.min(1.0, percent / 100.0)));
                for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                    if (player.hasPermissions(2)) {
                        bossBar.addPlayer(player);
                    } else {
                        bossBar.removePlayer(player);
                    }
                }
            } finally {
                bossBarPending.set(false);
            }
        });
    }

    /** Removes the boss bar after the run ends: soon when it completed, later when it stopped early. */
    private void hideBossBar() {
        if (level == null || serverStopping) return;
        var server = level.getServer();
        CompletableFuture.delayedExecutor(generationCompleted ? 5 : 15, TimeUnit.SECONDS).execute(() -> server.execute(() -> {
            if (bossBar != null) bossBar.removeAllPlayers();
        }));
    }

    private void log(String message) {
        LOGGER.info(message);
        progressCallback.accept(message);
    }
}
