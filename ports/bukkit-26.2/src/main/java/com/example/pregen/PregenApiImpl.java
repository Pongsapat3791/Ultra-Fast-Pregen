package com.example.pregen;

import io.github.pongsapat3791.ultrafastpregen.api.PregenApi;
import io.github.pongsapat3791.ultrafastpregen.api.PregenListener;
import io.github.pongsapat3791.ultrafastpregen.api.PregenRequest;
import io.github.pongsapat3791.ultrafastpregen.api.PregenStatus;
import io.github.pongsapat3791.ultrafastpregen.api.StartResult;
import io.github.pongsapat3791.ultrafastpregen.api.UltraFastPregenApi;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Ultra Fast Pregen's side of its API (package io.github.pongsapat3791.ultrafastpregen.api). */
public final class PregenApiImpl implements PregenApi {
    static final PregenApiImpl INSTANCE = new PregenApiImpl();

    private final List<PregenListener> listeners = new CopyOnWriteArrayList<>();
    private volatile String modVersion = "unknown";

    private PregenApiImpl() {}

    /** Makes the API available; called once by PreGenMod.init. */
    static void install(String version) {
        INSTANCE.modVersion = version;
        UltraFastPregenApi.provide(INSTANCE);
    }

    @Override
    public int apiVersion() {
        return UltraFastPregenApi.VERSION;
    }

    @Override
    public String modVersion() {
        return modVersion;
    }

    @Override
    public StartResult start(PregenRequest request) {
        MinecraftServer server = PregenRuns.server;
        if (server == null) return StartResult.refused("No server is running.");
        ServerLevel level = PregenRuns.level(server, request.dimension());
        if (level == null) return StartResult.refused("Dimension " + request.dimension() + " is not loaded.");
        ChunkBatchPlan.Shape shape = request.shape() == PregenRequest.Shape.CIRCLE ? ChunkBatchPlan.Shape.CIRCLE
                : ChunkBatchPlan.Shape.SQUARE;
        return result(PregenRuns.start(level, Math.multiplyExact(request.radius(), 2), shape, request.centerX(),
                request.centerZ(), false));
    }

    @Override
    public StartResult resume(String dimension) {
        MinecraftServer server = PregenRuns.server;
        if (server == null) return StartResult.refused("No server is running.");
        ServerLevel level = PregenRuns.level(server, dimension);
        if (level == null) return StartResult.refused("Dimension " + dimension + " is not loaded.");
        return result(PregenRuns.resume(level));
    }

    private static StartResult result(PregenRuns.Result result) {
        if (!result.started()) return StartResult.refused(result.message());
        return StartResult.started(result.message().isEmpty() ? "Started." : result.message(), status(result.task()));
    }

    @Override
    public boolean stop() {
        return PregenRuns.stop();
    }

    @Override
    public boolean isRunning() {
        return PregenRuns.isRunning();
    }

    @Override
    public Optional<PregenStatus> status() {
        DynamicBatchPreGen task = PregenRuns.current();
        return task == null ? Optional.empty() : Optional.of(status(task));
    }

    @Override
    public void addListener(PregenListener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    @Override
    public void removeListener(PregenListener listener) {
        listeners.remove(listener);
    }

    /** The plugin uses no fast paths that replace Minecraft code, so no mixin can conflict with them. */
    @Override
    public void declareCompatibleMixin(String mixinClassName) {
    }

    /** Who asked for Minecraft's own terrain steps (see requireMinecraftTerrain), or null. */
    private static volatile String minecraftTerrain;

    /** Runs then take Minecraft's per-status terrain steps instead of calling its biome and noise steps directly. */
    @Override
    public void requireMinecraftTerrain(String modId, String reason) {
        minecraftTerrain = modId + ": " + reason;
        PreGenMod.LOGGER.info("[PreGen] {} asks for Minecraft's own terrain steps: {}", modId, reason);
    }

    static String minecraftTerrainReason() {
        return minecraftTerrain;
    }

    /** A snapshot of the task for the API. */
    static PregenStatus status(DynamicBatchPreGen task) {
        PregenStatus.State state = task.isRunning() ? PregenStatus.State.RUNNING
                : task.isCompleted() ? PregenStatus.State.COMPLETED
                : task.hasFailed() ? PregenStatus.State.FAILED
                : PregenStatus.State.STOPPED;
        int radius = Math.max(1, task.getSizeBlocks() / 2);
        PregenRequest request = task.getShape() == ChunkBatchPlan.Shape.CIRCLE
                ? PregenRequest.circle(task.getWorldName(), task.getCenterX(), task.getCenterZ(), radius)
                : PregenRequest.square(task.getWorldName(), task.getCenterX(), task.getCenterZ(), radius);
        return new PregenStatus(request, state, task.getTotalWorldChunks(), task.getCompletedChunksCount(),
                task.getKeptChunksCount(), task.getProgressPercent() / 100.0, task.getChunksPerSecond(),
                task.getElapsedMillis(), state.ended() ? -1 : task.getEstimatedRemainingMillis(), task.getCurrentStageName());
    }

    // ------------------------------------------------------------------ events from DynamicBatchPreGen

    static void fireStart(DynamicBatchPreGen task) {
        INSTANCE.each(listener -> listener.onStart(status(task)));
    }

    static void fireProgress(DynamicBatchPreGen task) {
        INSTANCE.each(listener -> listener.onProgress(status(task)));
    }

    static void fireFinish(DynamicBatchPreGen task) {
        INSTANCE.each(listener -> listener.onFinish(status(task)));
    }

    static void fireChunksSaved(ServerLevel level, List<ChunkPos> positions) {
        if (INSTANCE.listeners.isEmpty() || level == null || positions.isEmpty()) return;
        String dimension = level.dimension().identifier().toString();
        long[] chunks = new long[positions.size()];
        for (int i = 0; i < chunks.length; i++) chunks[i] = positions.get(i).pack();
        // Each listener gets its own array, so one changing it does not affect the next.
        INSTANCE.each(listener -> listener.onChunksSaved(dimension, chunks.clone()));
    }

    /** Calls every listener; one that throws is logged and the run goes on. */
    private void each(Consumer<PregenListener> call) {
        if (listeners.isEmpty()) return;
        for (PregenListener listener : listeners) {
            try {
                call.accept(listener);
            } catch (RuntimeException | LinkageError failure) {
                PreGenMod.LOGGER.warn("[PreGen] An API listener ({}) failed", listener.getClass().getName(), failure);
            }
        }
    }
}
