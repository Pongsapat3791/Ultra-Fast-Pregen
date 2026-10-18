package com.example.pregen;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Starts, resumes and stops runs, for the /pregen command and the API alike. One run at a time
 * (in any world); the last run stays available for status after it ends.
 */
public final class PregenRuns {
    /** The vanilla world border's largest position; generated chunks must stay inside it. */
    static final long MAX_WORLD_COORDINATE = 29_999_984L;

    private static volatile DynamicBatchPreGen activeTask;
    /** The running server, through which the API finds dimensions; null between servers. */
    static volatile MinecraftServer server;

    /**
     * What a start or resume did. message: why nothing started (warning: a yellow note rather
     * than an error), or a note about the run that started (may be empty).
     */
    record Result(boolean started, String message, boolean warning, DynamicBatchPreGen task) {
        static Result refused(String message) {
            return new Result(false, message, false, null);
        }

        static Result note(String message) {
            return new Result(false, message, true, null);
        }
    }

    private PregenRuns() {}

    /** From MinecraftServerMixin: a server (dedicated, or a singleplayer world's) was created. */
    public static void onServerCreated(MinecraftServer created) {
        server = created;
    }

    /** The server whose start was already checked for a run to continue (see onServerTick). */
    private static volatile MinecraftServer checkedForResume;
    /** Automatic continuations without progress after which resume_after_restart gives up. */
    static final int MAX_AUTO_RESUMES = 3;

    /** From MinecraftServerMixin, every tick: once per server, continues a run its last stop cut short. */
    public static void onServerTick(MinecraftServer ticking) {
        if (checkedForResume == ticking) return;
        try {
            if (PreGenConfig.load().resumeAfterRestart) resumeAfterRestart(ticking);
        } catch (RuntimeException failure) {
            PreGenMod.LOGGER.warn("[PreGen] Could not check for a run to continue after the restart", failure);
        } finally {
            // Only the server thread calls this; set once the check is done, which the tests wait for.
            checkedForResume = ticking;
        }
    }

    /** Whether the server's first tick has checked for a run to continue (see onServerTick). */
    static boolean checkedForResume(MinecraftServer candidate) {
        return checkedForResume == candidate;
    }

    /**
     * Continues the first run (overworld first) that a server stop or crash cut short, unless it
     * was continued MAX_AUTO_RESUMES times in a row without saving a batch. Returns what it did,
     * for the log and the tests; null when there was nothing to continue.
     */
    static synchronized String resumeAfterRestart(MinecraftServer server) {
        if (isRunning()) return null;
        for (ServerLevel level : server.getAllLevels()) {
            PreGenSaveFile record;
            try {
                record = PreGenSaveFile.load(level);
            } catch (java.io.IOException | RuntimeException failure) {
                continue;
            }
            if (record.seedChanged() || !record.hasLastRun() || record.lastCompleted() || record.lastStopped()) continue;
            String area = String.format("%s centered on [%d, %d] in %s", areaText(record.lastSizeBlocks(), record.lastShape()),
                    record.lastCenterX(), record.lastCenterZ(), level.dimension().location());
            if (record.autoResumes() >= MAX_AUTO_RESUMES) {
                String message = "Not continuing the run (" + area + ") after this restart: the last " + MAX_AUTO_RESUMES
                        + " restarts continued it without saving a batch. /pregen resume continues it by hand.";
                PreGenMod.LOGGER.warn("[PreGen] " + message);
                return message;
            }
            record.countAutoResume();
            try {
                record.save();
            } catch (java.io.IOException | RuntimeException failure) {
                PreGenMod.LOGGER.warn("[PreGen] Could not write the save file {}", record.file(), failure);
            }
            Result started = start(level, record.lastSizeBlocks(), record.lastShape(), record.lastCenterX(),
                    record.lastCenterZ(), false);
            String message = started.started()
                    ? "Continuing the run cut short by the last server stop (" + area + "); /pregen stop stops it."
                    : "Could not continue the run (" + area + "): " + started.message();
            PreGenMod.LOGGER.info("[PreGen] " + message);
            return message;
        }
        return null;
    }

    /** How long a stopping server waits for the batch a run is saving (see stopForServer). */
    static final int SERVER_STOP_WAIT_SECONDS = 20;

    /**
     * From MinecraftServerMixin, on the server thread as the server starts stopping: a run in it
     * stops (see stopForServer) and is forgotten like a finished one (its world closes; a
     * singleplayer world opened next starts without it); the API has no server until the next one.
     */
    public static void onServerStopping(MinecraftServer stopping) {
        if (server == stopping) server = null;
        DynamicBatchPreGen task = activeTask;
        if (task == null) return;
        if (task.isRunning() && task.getLevel().getServer() == stopping) stopForServer(stopping, task);
        synchronized (PregenRuns.class) {
            if (activeTask == task) activeTask = null;
        }
    }

    /**
     * Stops a run because its server is stopping: the batch being generated is dropped, and the
     * batch being saved gets up to SERVER_STOP_WAIT_SECONDS to finish. The run is not marked as
     * stopped, so resume_after_restart continues it from its last saved batch. On the server thread
     * (a real stop), the chunk system keeps answering the chunks the run asks the server for;
     * nothing else of the server runs meanwhile.
     */
    static void stopForServer(MinecraftServer stopping, DynamicBatchPreGen task) {
        PreGenMod.LOGGER.info("[PreGen] The server is stopping: the run stops once the batch being saved is on disk (up to {} s){}",
                SERVER_STOP_WAIT_SECONDS, PreGenConfig.load().resumeAfterRestart
                        ? " and continues from there when the server starts again."
                        : "; /pregen resume continues it from there (resume_after_restart=false).");
        task.stopForServer();
        boolean serverThread = stopping.isSameThread();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SERVER_STOP_WAIT_SECONDS);
        boolean ended = false;
        try {
            while (!(ended = task.awaitEnd(1)) && System.nanoTime() - deadline < 0) {
                if (serverThread) {
                    for (ServerLevel level : stopping.getAllLevels()) level.getChunkSource().pollTask();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!ended) {
            task.detach();
            PreGenMod.LOGGER.warn("[PreGen] The batch being saved did not finish within {} s; it is generated again when the run continues.",
                    SERVER_STOP_WAIT_SECONDS);
        }
    }

    /** The running run, or else the last one since the server started. */
    static DynamicBatchPreGen current() {
        return activeTask;
    }

    static boolean isRunning() {
        DynamicBatchPreGen task = activeTask;
        return task != null && task.isRunning();
    }

    /** The loaded dimension with that id ("minecraft:overworld"), or null. */
    static ServerLevel level(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension)) return level;
        }
        return null;
    }

    /** "4k" for whole thousands of blocks, else "4500 blocks". */
    static String sizeText(int sizeBlocks) {
        return sizeBlocks % 1000 == 0 ? sizeBlocks / 1000 + "k" : sizeBlocks + " blocks";
    }

    /** "4k x 4k" for a square, "a circle of radius 2000 blocks" for a circle. */
    static String areaText(int sizeBlocks, ChunkBatchPlan.Shape shape) {
        return shape == ChunkBatchPlan.Shape.CIRCLE ? "a circle of radius " + sizeBlocks / 2 + " blocks"
                : sizeText(sizeBlocks) + " x " + sizeText(sizeBlocks);
    }

    /**
     * Starts generating the sizeBlocks x sizeBlocks square centered on block (x, z), or the
     * circle of that diameter.
     */
    static synchronized Result start(ServerLevel level, int sizeBlocks, ChunkBatchPlan.Shape shape, int x, int z,
                                     boolean profile) {
        if (isRunning()) {
            return Result.refused("A pre-generation task is already currently running!");
        }
        String replacedBy = WorldgenCompat.chunkSystemReplacement();
        if (replacedBy != null) {
            String reason = replacedBy + " replaces Minecraft's chunk system (chunk storage, chunk holders and the light engine),"
                    + " which Ultra Fast Pregen generates and saves through, and Ultra Fast Pregen does not support it yet."
                    + " Nothing was started. Pre-generate without " + replacedBy
                    + ", or with a pregenerator that asks the server for chunks (such as Chunky).";
            PreGenMod.LOGGER.warn("[PreGen] " + reason);
            return Result.refused(reason);
        }
        // Tiles also generate a structure ring of InMemoryChunkGrid.PADDING chunks around the area.
        long reach = (sizeBlocks + 1) / 2L + (InMemoryChunkGrid.PADDING + 1) * 16L;
        if (Math.abs((long) x) + reach > MAX_WORLD_COORDINATE || Math.abs((long) z) + reach > MAX_WORLD_COORDINATE) {
            return Result.refused(String.format("%s centered on [%d, %d] reaches past the world limit of ±%d.",
                    capitalized(areaText(sizeBlocks, shape)), x, z, MAX_WORLD_COORDINATE));
        }
        // Batch size and threads come only from config/ultrafastpregen.conf, re-read on every start.
        PreGenConfig config = PreGenConfig.load();
        long freeHeap = PreGenConfig.freeHeapBytes();
        int batch = PreGenConfig.fitBatchToHeap(config.batchSizeBlocks, freeHeap);
        if (batch != config.batchSizeBlocks) {
            PreGenMod.LOGGER.warn(String.format(
                    "[PreGen] Only %.1f GB of heap is free, too little for batch_size=%d; using %d (a little slower). Give Minecraft more memory (-Xmx on a server, the launcher's memory setting in singleplayer) to use the configured size.",
                    freeHeap / 1073741824.0, config.batchSizeBlocks, batch));
        }
        // Players see the boss bar; the settings go to the server log and console.
        PreGenMod.LOGGER.info(String.format(
            "[PreGen] Starting generation for %s in %s centered on [%d, %d] | Batch: %d x %d blocks | Threads: %d%s | Existing chunks: %s (change in config/%s)",
            areaText(sizeBlocks, shape), level.dimension().location(), x, z, batch, batch,
            config.threads, config.threadsFromCpuCount ? " (max)" : "",
            config.skipExistingChunks ? "kept" : "overwritten", PreGenConfig.FILE_NAME));

        // The engine logs each event; players follow the run on the boss bar, not in chat.
        DynamicBatchPreGen task = DynamicBatchPreGen.area(level, sizeBlocks, shape, batch, config.threads, x, z, msg -> {});
        task.setTerrainPipeline(config.terrainPipeline);
        task.setParallelSteps(config.parallelSteps);
        task.setAlwaysParallelFeatures(config.alwaysParallelFeatures);
        task.setReleaseMemoryEachBatch(config.releaseMemoryEachBatch);
        task.setSkipExistingChunks(config.skipExistingChunks);
        if (!PreGenConfig.overlapFits(batch, freeHeap)) task.setOverlapBatches(false);
        if (profile) task.enableProfiling();
        activeTask = task;
        Thread coordinator = new Thread(task::start, "PreGen-Worker");
        // Never keep the JVM alive after the server stops (for example while waiting on disk writes).
        coordinator.setDaemon(true);
        coordinator.start();
        return new Result(true, "", false, task);
    }

    /** Continues the world's last run (size and center) from its save file. */
    static synchronized Result resume(ServerLevel level) {
        PreGenSaveFile record;
        try {
            record = PreGenSaveFile.load(level);
        } catch (IOException | RuntimeException failure) {
            return Result.refused("Could not read the save file: " + failure.getMessage());
        }
        if (record.seedChanged() || !record.hasLastRun()) {
            return Result.note("No earlier Ultra Fast Pregen run is recorded in " + level.dimension().location()
                    + ". Start one with /pregen start <world_k>.");
        }
        Result started = start(level, record.lastSizeBlocks(), record.lastShape(), record.lastCenterX(),
                record.lastCenterZ(), false);
        if (started.started() && record.lastCompleted()) {
            return new Result(true, String.format("The last run (%s centered on [%d, %d]) already completed; every finished tile is skipped.",
                    areaText(record.lastSizeBlocks(), record.lastShape()), record.lastCenterX(), record.lastCenterZ()),
                    false, started.task());
        }
        return started;
    }

    private static String capitalized(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Asks the running run to stop after its current batch; false when nothing runs. */
    static synchronized boolean stop() {
        DynamicBatchPreGen task = activeTask;
        if (task == null || !task.isRunning()) return false;
        task.cancel();
        return true;
    }
}
