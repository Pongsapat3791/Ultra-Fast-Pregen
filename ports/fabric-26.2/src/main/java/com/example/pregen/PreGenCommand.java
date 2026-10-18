package com.example.pregen;

import com.mojang.brigadier.CommandDispatcher;
import java.io.IOException;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * In-game command registration for /pregen operations:
 * - /pregen start <world_k> [profile]
 *   Example: /pregen start 20
 *   Batch size (default 1000 x 1000 blocks) and threads (default: every core) come from
 *   config/ultrafastpregen.conf. "profile" saves a JFR recording of the run to the server directory.
 * - /pregen set [batch_size|threads|parallel_features|skip_existing_chunks|lod_export] [value]
 *   writes one setting into that file (without a value: shows the settings)
 * - /pregen world [dimension]   select the world for the next start, e.g. minecraft:the_nether
 * - /pregen center [x z]         center of the square for the next start (accepts ~ ~)
 * - /pregen resume             continue the selected world's last run (size and center) from its save file
 * - /pregen reset              forget which chunks Ultra Fast Pregen generated in the selected world
 * - /pregen stop
 * - /pregen status
 */
public class PreGenCommand {
    /** The vanilla world border's largest position; generated chunks must stay inside it. */
    private static final long MAX_WORLD_COORDINATE = 29_999_984L;

    private static volatile DynamicBatchPreGen activeTask = null;
    // Settings for the next /pregen start; a running task keeps the values it started with.
    private static volatile ResourceKey<Level> selectedWorld = null; // null: the world of the command source
    private static volatile int centerX = 0;
    private static volatile int centerZ = 0;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext, Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("pregen")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(Commands.literal("start")
                .then(Commands.argument("world_k", IntegerArgumentType.integer(1, 100))
                    .executes(ctx -> startPreGen(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "world_k"), false))
                    .then(Commands.literal("profile")
                        .executes(ctx -> startPreGen(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "world_k"), true))
                    )
                )
            )
            .then(Commands.literal("world")
                .executes(ctx -> showSettings(ctx.getSource()))
                .then(Commands.argument("dimension", DimensionArgument.dimension())
                    .executes(ctx -> setWorld(ctx.getSource(), DimensionArgument.getDimension(ctx, "dimension")))
                )
            )
            .then(Commands.literal("center")
                .executes(ctx -> showSettings(ctx.getSource()))
                .then(Commands.argument("position", ColumnPosArgument.columnPos())
                    .executes(ctx -> setCenter(ctx.getSource(), ColumnPosArgument.getColumnPos(ctx, "position")))
                )
            )
            .then(Commands.literal("resume")
                .executes(ctx -> resumePreGen(ctx.getSource()))
            )
            .then(Commands.literal("reset")
                .executes(ctx -> resetSaveFile(ctx.getSource()))
            )
            .then(Commands.literal("stop")
                .executes(ctx -> stopPreGen(ctx.getSource()))
            )
            .then(Commands.literal("status")
                .executes(ctx -> statusPreGen(ctx.getSource()))
            )
            .then(Commands.literal("set")
                .executes(ctx -> showConfig(ctx.getSource()))
                .then(Commands.literal("batch_size")
                    .executes(ctx -> showConfig(ctx.getSource()))
                    .then(Commands.argument("blocks", IntegerArgumentType.integer(PreGenConfig.MIN_BATCH_SIZE, PreGenConfig.MAX_BATCH_SIZE))
                        .executes(ctx -> setConfig(ctx.getSource(), "batch_size",
                                Integer.toString(IntegerArgumentType.getInteger(ctx, "blocks"))))
                    )
                )
                .then(Commands.literal("threads")
                    .executes(ctx -> showConfig(ctx.getSource()))
                    .then(Commands.literal("max")
                        .executes(ctx -> setConfig(ctx.getSource(), "threads", "max"))
                    )
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, PreGenConfig.MAX_THREADS))
                        .executes(ctx -> setConfig(ctx.getSource(), "threads",
                                Integer.toString(IntegerArgumentType.getInteger(ctx, "count"))))
                    )
                )
                .then(Commands.literal("parallel_features")
                    .executes(ctx -> showConfig(ctx.getSource()))
                    .then(Commands.literal("auto")
                        .executes(ctx -> setConfig(ctx.getSource(), "parallel_features", "auto"))
                    )
                    .then(Commands.literal("always")
                        .executes(ctx -> setConfig(ctx.getSource(), "parallel_features", "always"))
                    )
                )
                .then(Commands.literal("skip_existing_chunks")
                    .executes(ctx -> showConfig(ctx.getSource()))
                    .then(Commands.argument("keep", BoolArgumentType.bool())
                        .executes(ctx -> setConfig(ctx.getSource(), "skip_existing_chunks",
                                Boolean.toString(BoolArgumentType.getBool(ctx, "keep"))))
                    )
                )
                .then(Commands.literal("lod_export")
                    .executes(ctx -> showConfig(ctx.getSource()))
                    .then(Commands.argument("send", BoolArgumentType.bool())
                        .executes(ctx -> setConfig(ctx.getSource(), "lod_export",
                                Boolean.toString(BoolArgumentType.getBool(ctx, "send"))))
                    )
                )
            )
        );
    }

    /** Writes one setting into the config file (the command arguments validate the value). */
    private static int setConfig(CommandSourceStack source, String key, String value) {
        try {
            PreGenConfig.set(PreGenMod.configDirectory, key, value);
        } catch (IOException failure) {
            source.sendFailure(Component.literal("§c[PreGen] Could not write config/" + PreGenConfig.FILE_NAME + ": " + failure.getMessage()));
            return 0;
        }
        DynamicBatchPreGen running = activeTask;
        String when = running != null && running.isRunning()
                ? "the running task keeps its settings; the next /pregen start uses it"
                : "used from the next /pregen start";
        source.sendSuccess(() -> Component.literal("§a[PreGen] " + key + "=" + value + " saved in config/"
                + PreGenConfig.FILE_NAME + " §7(" + when + ")"), true);
        return 1;
    }

    private static int showConfig(CommandSourceStack source) {
        PreGenConfig config = PreGenConfig.load();
        String threads = config.threadsFromCpuCount ? "max (" + config.threads + ")" : Integer.toString(config.threads);
        source.sendSuccess(() -> Component.literal(String.format(
                "§a[PreGen] batch_size=%d | threads=%s | parallel_features=%s | skip_existing_chunks=%b | lod_export=%b §7(change with /pregen set <key> <value>; config/%s)",
                config.batchSizeBlocks, threads, config.alwaysParallelFeatures ? "always" : "auto", config.skipExistingChunks, config.lodExport, PreGenConfig.FILE_NAME)), false);
        return 1;
    }

    private static int startPreGen(CommandSourceStack source, int worldK, boolean profile) {
        synchronized (PreGenCommand.class) {
            ServerLevel level = selectedLevel(source);
            return level == null ? 0 : startPreGen(source, level, worldK, centerX, centerZ, profile);
        }
    }

    /** The world chosen with /pregen world, or the command source's world; null after reporting. */
    private static ServerLevel selectedLevel(CommandSourceStack source) {
        ResourceKey<Level> worldKey = selectedWorld;
        ServerLevel level = worldKey != null ? source.getServer().getLevel(worldKey) : source.getLevel();
        if (level == null) {
            source.sendFailure(Component.literal("§c[PreGen] World " + worldKey.identifier()
                    + " is not loaded. Choose another with /pregen world <dimension>."));
        }
        return level;
    }

    private static int resumePreGen(CommandSourceStack source) {
        synchronized (PreGenCommand.class) {
            ServerLevel level = selectedLevel(source);
            if (level == null) return 0;
            PreGenSaveFile record;
            try {
                record = PreGenSaveFile.load(level);
            } catch (IOException | RuntimeException failure) {
                source.sendFailure(Component.literal("§c[PreGen] Could not read the save file: " + failure.getMessage()));
                return 0;
            }
            if (record.seedChanged() || !record.hasLastRun()) {
                source.sendFailure(Component.literal("§e[PreGen] No earlier Ultra Fast Pregen run is recorded in "
                        + level.dimension().identifier() + ". Use /pregen start <world_k>."));
                return 0;
            }
            if (record.lastCompleted()) {
                source.sendSuccess(() -> Component.literal(String.format(
                        "§7[PreGen] The last run (%dk centered on [%d, %d]) already completed; every finished tile is skipped.",
                        record.lastWorldK(), record.lastCenterX(), record.lastCenterZ())), false);
            }
            return startPreGen(source, level, record.lastWorldK(), record.lastCenterX(), record.lastCenterZ(), false);
        }
    }

    private static int resetSaveFile(CommandSourceStack source) {
        synchronized (PreGenCommand.class) {
            ServerLevel level = selectedLevel(source);
            if (level == null) return 0;
            DynamicBatchPreGen task = activeTask;
            if (task != null && task.isRunning()) {
                source.sendFailure(Component.literal("§c[PreGen] Stop the running task before resetting its save file."));
                return 0;
            }
            try {
                boolean deleted = PreGenSaveFile.delete(level);
                source.sendSuccess(() -> Component.literal(deleted
                        ? "§a[PreGen] Forgot Ultra Fast Pregen's progress in " + level.dimension().identifier()
                            + "; chunks that exist on disk are still kept while skip_existing_chunks=true."
                        : "§7[PreGen] " + level.dimension().identifier() + " has no Ultra Fast Pregen save file."), true);
                return 1;
            } catch (IOException failure) {
                source.sendFailure(Component.literal("§c[PreGen] Could not delete the save file: " + failure.getMessage()));
                return 0;
            }
        }
    }

    private static int startPreGen(CommandSourceStack source, ServerLevel level, int worldK, int x, int z, boolean profile) {
        synchronized (PreGenCommand.class) {
            if (activeTask != null && activeTask.isRunning()) {
                source.sendFailure(Component.literal("§c[PreGen] A pre-generation task is already currently running!"));
                return 0;
            }
            String replacedBy = WorldgenCompat.chunkSystemReplacement();
            if (replacedBy != null) {
                String reason = replacedBy + " replaces Minecraft's chunk system (chunk storage, chunk holders and the light engine),"
                        + " which Ultra Fast Pregen generates and saves through, and Ultra Fast Pregen does not support it yet."
                        + " Nothing was started. Pre-generate without " + replacedBy
                        + ", or with a pregenerator that asks the server for chunks (such as Chunky).";
                PreGenMod.LOGGER.warn("[PreGen] " + reason);
                source.sendFailure(Component.literal("§c[PreGen] " + reason));
                return 0;
            }
            // Tiles also generate a structure ring of InMemoryChunkGrid.PADDING chunks around the area.
            long reach = worldK * 500L + (InMemoryChunkGrid.PADDING + 1) * 16L;
            if (Math.abs((long) x) + reach > MAX_WORLD_COORDINATE || Math.abs((long) z) + reach > MAX_WORLD_COORDINATE) {
                source.sendFailure(Component.literal(String.format(
                        "§c[PreGen] A %dk area centered on [%d, %d] reaches past the world limit of ±%d.",
                        worldK, x, z, MAX_WORLD_COORDINATE)));
                return 0;
            }
            // Batch size and threads come only from config/ultrafastpregen.conf, re-read on every start.
            PreGenConfig config = PreGenConfig.load();
            long freeHeap = PreGenConfig.freeHeapBytes();
            int batch = PreGenConfig.fitBatchToHeap(config.batchSizeBlocks, freeHeap);
            if (batch != config.batchSizeBlocks) {
                String warning = String.format(
                        "§e[PreGen] Only %.1f GB of heap is free, too little for batch_size=%d; using %d (a little slower). Give Minecraft more memory (-Xmx on a server, the launcher's memory setting in singleplayer) to use the configured size.",
                        freeHeap / 1073741824.0, config.batchSizeBlocks, batch);
                PreGenMod.LOGGER.warn(warning.replaceAll("§.", ""));
            }
            // Players see the boss bar; the settings go to the server log and console.
            PreGenMod.LOGGER.info(String.format(
                "[PreGen] Starting generation for %dk x %dk in %s centered on [%d, %d] | Batch: %d x %d blocks | Threads: %d%s | Existing chunks: %s §7(change in config/%s)",
                worldK, worldK, level.dimension().identifier(), x, z, batch, batch,
                config.threads, config.threadsFromCpuCount ? " (max)" : "",
                config.skipExistingChunks ? "kept" : "overwritten", PreGenConfig.FILE_NAME
            ).replaceAll("§.", ""));

            // The engine logs each event; players follow the run on the boss bar, not in chat.
            DynamicBatchPreGen task = new DynamicBatchPreGen(level, worldK, batch, config.threads, x, z, msg -> {});
            task.setTerrainPipeline(config.terrainPipeline);
            task.setParallelSteps(config.parallelSteps);
            task.setAlwaysParallelFeatures(config.alwaysParallelFeatures);
            task.setReleaseMemoryEachBatch(config.releaseMemoryEachBatch);
            task.setSkipExistingChunks(config.skipExistingChunks);
            task.setLodExport(config.lodExport);
            if (!PreGenConfig.overlapFits(batch, freeHeap)) task.setOverlapBatches(false);
            if (profile) task.enableProfiling();
            activeTask = task;
            Thread coordinator = new Thread(task::start, "PreGen-Worker");
            // Never keep the JVM alive after the server stops (for example while waiting on disk writes).
            coordinator.setDaemon(true);
            coordinator.start();

            return 1;
        }
    }

    private static int setWorld(CommandSourceStack source, ServerLevel level) {
        selectedWorld = level.dimension();
        source.sendSuccess(() -> Component.literal("§a[PreGen] Next /pregen start generates in §f"
                + level.dimension().identifier() + "§a" + runningNote()), true);
        return 1;
    }

    private static int setCenter(CommandSourceStack source, ColumnPos position) {
        centerX = position.x();
        centerZ = position.z();
        source.sendSuccess(() -> Component.literal(String.format(
                "§a[PreGen] Next /pregen start is centered on block §f[%d, %d]§a%s",
                position.x(), position.z(), runningNote())), true);
        return 1;
    }

    private static String runningNote() {
        DynamicBatchPreGen task = activeTask;
        return task != null && task.isRunning() ? " §7(the running task keeps its current settings)" : ".";
    }

    private static int showSettings(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(settingsLine()), false);
        return 1;
    }

    private static String settingsLine() {
        ResourceKey<Level> worldKey = selectedWorld;
        return String.format("§7[PreGen] Next start: world §f%s§7, center §f[%d, %d]§7. "
                        + "Change with /pregen world <dimension> and /pregen center <x> <z>.",
                worldKey != null ? worldKey.identifier().toString() : "your current world", centerX, centerZ);
    }

    private static int stopPreGen(CommandSourceStack source) {
        synchronized (PreGenCommand.class) {
            if (activeTask == null || !activeTask.isRunning()) {
                source.sendFailure(Component.literal("§e[PreGen] No active pre-generation task is running."));
                return 0;
            }

            activeTask.cancel();
            source.sendSuccess(() -> Component.literal("§6[PreGen] Cancellation request sent. Task will stop after finishing current batch."), true);
            return 1;
        }
    }

    private static int statusPreGen(CommandSourceStack source) {
        DynamicBatchPreGen task = activeTask;
        if (task == null) {
            source.sendSuccess(() -> Component.literal(
                    "§7[PreGen] Status: §eIdle §7(No active pre-generation task).\n" + settingsLine()), false);
            return 1;
        }

        int current = task.getCurrentBatchIndex();
        int completedBatches = task.getCompletedBatchesCount();
        int total = task.getTotalBatchesCount();
        int completedChunks = task.getCompletedChunksCount();
        int totalChunks = task.getTotalWorldChunks();
        double overallSpeed = task.getRealtimeChunksPerSecond();

        long totalMemoryMB = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        long usedMemoryMB = totalMemoryMB - (Runtime.getRuntime().freeMemory() / (1024 * 1024));
        long maxMemoryMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);

        String msg = String.format(
            "§6========== [PreGen Status] ==========\n" +
            "§aOverall Progress: §e%.1f%%\n" +
            "§aCompleted Tiles: §e%d / %d\n" +
            "§aProcessed Chunks: §f%d / %d §7(%d skipped: already generated)\n" +
            "§aWorld: §f%s §7| §aCenter: §f[%d, %d]\n" +
            "§aCurrent Tile: §f%d §7Block [%d, %d]\n" +
            "§aOverall Speed: §f%.1f chunks/s\n" +
            "§aElapsed Time: §f%s §7| §aRemaining ETA: §e%s\n" +
            "§aJVM RAM Usage: §f%dMB / %dMB §7(Max: %dMB)\n" +
            "§6=====================================",
            task.getProgressPercent(),
            completedBatches, total,
            completedChunks, totalChunks, task.getResumedChunksCount(),
            task.getWorldName(), task.getCenterX(), task.getCenterZ(),
            current, task.getCurrentMinX(), task.getCurrentMinZ(),
            overallSpeed,
            DynamicBatchPreGen.formatDuration(task.getElapsedMillis()),
            task.getEstimatedRemainingTime(),
            usedMemoryMB, totalMemoryMB, maxMemoryMB
        );

        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }
}
