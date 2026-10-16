package com.example.pregen;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Regressions for the reported double counting and concurrent progress callbacks. */
public final class DynamicBatchPreGenProgressCheck {
    private DynamicBatchPreGenProgressCheck() {}

    public static void run() {
        try {
            checkDistinctWorldMetrics();
            checkConcurrentAndStaleCallbacks();
            checkOverallWorkProgress();
            checkOverlappedProgress();
            System.out.println("PREGEN_PROGRESS_CHECK stage_double_count=false eta_distinct_chunks=true"
                    + " duration_frozen=true stale_callbacks_ignored=true milestone_duplicates=false progress_every_3s=true");
        } catch (ReflectiveOperationException | InterruptedException failure) {
            throw new AssertionError("Progress regression check failed", failure);
        }
    }

    private static void checkDistinctWorldMetrics() throws ReflectiveOperationException {
        DynamicBatchPreGen task = new DynamicBatchPreGen(null, 1, 1, 1, null);
        try {
            long start = System.currentTimeMillis() - 220_000;
            set(task, "totalStartTime", start);
            set(task, "totalEndTime", start + 220_000);
            Object stage = newStage("CHECK: flushing", 4096);
            stageDone(stage).set(4096);
            set(task, "activeStage", stage);

            require(task.getStagePercent() == 100.0, "Stage progress must count its callbacks");
            require(task.getCompletedChunksCount() == 0 && task.getOverallPercent() == 0.0,
                    "Stage completion must not imply that a core tile was persisted");
            require(task.getChunksPerSecond() == 0.0,
                    "Halos and revisited stage chunks must not inflate world speed");
            require(task.getEstimatedRemainingTime().equals("Calculating..."),
                    "First-tile stage progress must not produce a premature zero ETA");

            set(task, "completedChunksCount", 4096);
            require(task.getOverallPercent() == 100.0, "All persisted core chunks must produce 100%");
            require(Math.abs(task.getChunksPerSecond() - 4096.0 / 220.0) < 1.0e-9,
                    "Final average must count 4096 distinct chunks, not 8192 stage plus core chunks");
            require(task.getEstimatedRemainingTime().equals("0s"), "Completed target must have zero ETA");
            set(task, "isRunning", false);
            require(task.getElapsedMillis() == 220_000, "Finished elapsed time must be retained");
        } finally {
            shutdown(task);
        }

        DynamicBatchPreGen partial = new DynamicBatchPreGen(null, 3, 1, 1, null);
        try {
            long start = System.currentTimeMillis() - 220_000;
            set(partial, "totalStartTime", start);
            set(partial, "totalEndTime", start + 220_000);
            set(partial, "completedChunksCount", 3844);
            require(partial.getTotalWorldChunks() == 35344, "3k target should span 188 squared chunks");
            require(partial.getEstimatedRemainingTime().equals("30m 02s"),
                    "ETA must use the remaining 31500 distinct chunks after one completed tile");
        } finally {
            shutdown(partial);
        }

        DynamicBatchPreGen resumed = new DynamicBatchPreGen(null, 3, 1, 1, null);
        try {
            long start = System.currentTimeMillis() - 220_000;
            set(resumed, "totalStartTime", start);
            set(resumed, "totalEndTime", start + 220_000);
            // One tile came from the save file, one was generated in 220 seconds.
            set(resumed, "completedChunksCount", 7688);
            set(resumed, "resumedChunks", 3844);
            require(Math.abs(resumed.getChunksPerSecond() - 3844.0 / 220.0) < 1.0e-9,
                    "Tiles skipped from the save file must not count toward speed");
            require(resumed.getEstimatedRemainingTime().equals("26m 22s"),
                    "ETA must divide the remaining 27656 chunks by the generated speed only");
        } finally {
            shutdown(resumed);
        }
    }

    private static void checkConcurrentAndStaleCallbacks()
            throws ReflectiveOperationException, InterruptedException {
        List<String> messages = new CopyOnWriteArrayList<>();
        DynamicBatchPreGen task = new DynamicBatchPreGen(null, 1, 1, 1, messages::add);
        ExecutorService callbacks = Executors.newFixedThreadPool(8);
        try {
            Object oldStage = newStage("CHECK: old stage", 8192);
            Object currentStage = newStage("CHECK: concurrent stage", 8192);
            // Keep this world-independent test from enqueueing server action bars.
            ((AtomicLong) get(currentStage, "lastActionBarAt")).set(Long.MAX_VALUE);
            set(task, "activeStage", currentStage);
            Method onDone = DynamicBatchPreGen.class.getDeclaredMethod("onChunkDoneInStage", currentStage.getClass());
            onDone.setAccessible(true);
            onDone.invoke(task, oldStage);
            require(stageDone(currentStage).get() == 0 && stageDone(oldStage).get() == 0,
                    "A stale callback must be ignored rather than updating the current stage");
            List<java.util.concurrent.Future<?>> work = new java.util.ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                work.add(callbacks.submit(() -> {
                    for (int i = 0; i < 1024; i++) {
                        try {
                            onDone.invoke(task, currentStage);
                        } catch (ReflectiveOperationException failure) {
                            throw new AssertionError(failure);
                        }
                    }
                }));
            }
            for (java.util.concurrent.Future<?> future : work) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException failure) {
                    throw new AssertionError("Concurrent callback failed", failure.getCause());
                }
            }
            require(stageDone(currentStage).get() == 8192, "Concurrent callbacks must not lose increments");
            require(messages.size() == 1 && messages.get(0).contains("Progress: 0.0%"),
                    "Concurrent callbacks within one 3-second interval must log progress once");

            // Progress, speed and ETA follow a 3-second cadence, regardless of how many chunks finish.
            Method report = DynamicBatchPreGen.class.getDeclaredMethod("reportProgress");
            report.setAccessible(true);
            AtomicLong lastLog = (AtomicLong) get(task, "lastProgressLogAt");
            lastLog.set(System.currentTimeMillis() - 2_000);
            report.invoke(task);
            require(messages.size() == 1, "Progress must not be logged again within 3 seconds");
            lastLog.set(System.currentTimeMillis() - 3_001);
            report.invoke(task);
            require(messages.size() == 2 && messages.get(1).contains("Speed:") && messages.get(1).contains("ETA:"),
                    "Progress, speed and ETA must be logged again once 3 seconds have passed");
            require(messages.stream().noneMatch(message -> message.contains("stage")),
                    "Progress messages must not expose internal stage names");
            require(task.getCompletedChunksCount() == 0 && task.getOverallPercent() == 0.0,
                    "Concurrent stage callbacks must not advance distinct world completion");
        } finally {
            callbacks.shutdown();
            callbacks.awaitTermination(5, TimeUnit.SECONDS);
            shutdown(task);
        }
    }

    private static Object newStage(String name, int count) throws ReflectiveOperationException {
        Class<?> stageType = Class.forName("com.example.pregen.DynamicBatchPreGen$StageProgress");
        Constructor<?> constructor = stageType.getDeclaredConstructor(String.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(name, count);
    }

    private static void checkOverallWorkProgress() throws ReflectiveOperationException {
        DynamicBatchPreGen task = new DynamicBatchPreGen(null, 3, 1, 1, null);
        try {
            set(task, "completedChunksCount", 3844);
            Object stage = newStage("Stage 2/6: hidden", 8192);
            set(stage, "baseChunks", 3844);
            set(stage, "tileChunks", 3906);
            set(stage, "phase", 1);
            stageDone(stage).set(4096);
            set(task, "activeStage", stage);
            double expected = (3844 + 3906 * 1.5 / 7) * 100.0 / 35344;
            require(Math.abs(task.getProgressPercent() - expected) < 1e-9,
                    "Overall work must use core tile size rather than padded callback count");
            require(task.getCompletedChunksCount() == 3844,
                    "Estimated work must not inflate completed world chunks");
            double previous = task.getProgressPercent();
            stageDone(stage).set(8192);
            require(task.getProgressPercent() > previous, "Overall percentage must advance within a tile");
            Object empty = newStage("Stage 3/6: hidden", 0);
            set(empty, "baseChunks", 3844);
            set(empty, "tileChunks", 3906);
            set(empty, "phase", 2);
            set(empty, "endedAt", System.currentTimeMillis());
            set(task, "activeStage", empty);
            require(task.getProgressPercent() > previous, "Empty completed steps must still advance work");
            set(task, "completedChunksCount", task.getTotalWorldChunks());
            require(task.getProgressPercent() == 99.9, "100% must wait for the final disk write");
            set(task, "generationCompleted", true);
            require(task.getProgressPercent() == 100.0, "Successful saving must show 100%");
        } finally {
            shutdown(task);
        }
    }

    /** A batch lit and saved beside the next one: progress neither jumps back nor counts it twice. */
    private static void checkOverlappedProgress() throws ReflectiveOperationException {
        DynamicBatchPreGen task = new DynamicBatchPreGen(null, 3, 1, 1, null);
        try {
            ChunkBatchPlan.Tile tile = new ChunkBatchPlan(3000, 1000, 0, 0).tile(0, 0);
            int chunks = tile.totalChunks();
            Class<?> tailType = Class.forName("com.example.pregen.DynamicBatchPreGen$BatchTail");
            Constructor<?> constructor = tailType.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object tail = constructor.newInstance(1, 9, tile, null, null, 0L, List.of(), java.util.Map.of(), "", "", "", 4);
            Object features = newStage("Stage 4/6: hidden", 10);
            set(features, "tileChunks", chunks);
            set(features, "phase", 3);
            set(features, "endedAt", System.currentTimeMillis());
            set(task, "activeStage", features);
            double headDone = task.getProgressPercent();
            require(Math.abs(headDone - chunks * 4.0 / 7 * 100.0 / task.getTotalWorldChunks()) < 1e-9,
                    "A batch past its features has done 4 of 7 steps");

            set(task, "runningTail", tail);
            set(task, "currentTileChunks", chunks);
            Method startStage = DynamicBatchPreGen.class.getDeclaredMethod("startStage", int.class, int.class, String.class, int.class);
            startStage.setAccessible(true);
            startStage.invoke(task, 2, 9, "Stage 1/6: hidden", 100);
            require(Math.abs(task.getProgressPercent() - headDone) < 1e-9,
                    "The next batch starting beside a finishing one must not move progress");

            Method startTailStage = DynamicBatchPreGen.class.getDeclaredMethod("startTailStage", tailType, boolean.class, String.class, int.class);
            startTailStage.setAccessible(true);
            Runnable lit = (Runnable) startTailStage.invoke(task, tail, true, "Stage 5/6: Lighting", 10);
            for (int i = 0; i < 10; i++) lit.run();
            require(task.getProgressPercent() > headDone, "The finishing batch's lighting must advance progress");
            require(task.getStageTotalChunks() == 100, "The shown stage stays the next batch's");

            set(task, "completedChunksCount", chunks);
            set(tail, "finished", true);
            require(Math.abs(task.getProgressPercent() - chunks * 100.0 / task.getTotalWorldChunks()) < 1e-9,
                    "A finished batch must count once");
        } finally {
            shutdown(task);
        }
    }

    private static AtomicInteger stageDone(Object stage) throws ReflectiveOperationException {
        return (AtomicInteger) get(stage, "done");
    }

    private static Object get(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void shutdown(DynamicBatchPreGen task) throws ReflectiveOperationException {
        ((ExecutorService) get(task, "workerPool")).shutdown();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
