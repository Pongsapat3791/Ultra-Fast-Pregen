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
 * - /pregen set [batch_size|threads|parallel_features|skip_existing_chunks|resume_after_restart|lod_export] [value]
 *   writes one setting into that file (without a value: shows the settings)
 * - /pregen start               the area chosen with /pregen radius (or /pregen worldborder)
 * - /pregen radius <blocks>      radius of the next start's area; /pregen shape square|circle its shape
 * - /pregen worldborder          center and radius from the selected world's border; /pregen spawn: center at the world spawn
 * - /pregen world [dimension]   select the world for the next start, e.g. minecraft:the_nether
 * - /pregen center [x z]         center of the area for the next start (accepts ~ ~)
 * - /pregen resume             continue the selected world's last run (size and center) from its save file
 * - /pregen reset              forget which chunks Ultra Fast Pregen generated in the selected world
 * - /pregen stop
 * - /pregen status
 */
public class PreGenCommand {
    // Settings for the next /pregen start; a running task keeps the values it started with.
    private static volatile ResourceKey<Level> selectedWorld = null; // null: the world of the command source
    private static volatile int centerX = 0;
    private static volatile int centerZ = 0;
    /** Radius in blocks for /pregen start without a size; 0 until chosen. /pregen start <world_k> gives the size itself. */
    private static volatile int radius = 0;
    private static volatile ChunkBatchPlan.Shape shape = ChunkBatchPlan.Shape.SQUARE;

    private static final java.util.function.Predicate<CommandSourceStack> GAMEMASTERS =
            Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

    /**
     * The singleplayer world's owner: there an op level exists only with cheats on, and without one
     * the command would look missing, so the owner may pregenerate anyway. Other players (a server,
     * LAN guests) need op level 2.
     */
    private static boolean isSingleplayerOwner(CommandSourceStack source) {
        net.minecraft.server.level.ServerPlayer player = source.getPlayer();
        return player != null && source.getServer().isSingleplayerOwner(player.nameAndId());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext, Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("pregen")
            .requires(source -> GAMEMASTERS.test(source) || isSingleplayerOwner(source))
            .then(Commands.literal("start")
                .executes(ctx -> startPreGen(ctx.getSource()))
                .then(Commands.argument("world_k", IntegerArgumentType.integer(1, 100))
                    .executes(ctx -> startPreGen(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "world_k"), false))
                    .then(Commands.literal("profile")
                        .executes(ctx -> startPreGen(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "world_k"), true))
                    )
                )
            )
            .then(Commands.literal("radius")
                .executes(ctx -> showSettings(ctx.getSource()))
                .then(Commands.argument("blocks", IntegerArgumentType.integer(1, io.github.pongsapat3791.ultrafastpregen.api.PregenRequest.MAX_RADIUS))
                    .executes(ctx -> setRadius(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "blocks")))
                )
            )
            .then(Commands.literal("shape")
                .executes(ctx -> showSettings(ctx.getSource()))
                .then(Commands.literal("square")
                    .executes(ctx -> setShape(ctx.getSource(), ChunkBatchPlan.Shape.SQUARE))
                )
                .then(Commands.literal("circle")
                    .executes(ctx -> setShape(ctx.getSource(), ChunkBatchPlan.Shape.CIRCLE))
                )
            )
            .then(Commands.literal("worldborder")
                .executes(ctx -> fromWorldBorder(ctx.getSource()))
            )
            .then(Commands.literal("spawn")
                .executes(ctx -> fromSpawn(ctx.getSource()))
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
                .then(Commands.literal("resume_after_restart")
                    .executes(ctx -> showConfig(ctx.getSource()))
                    .then(Commands.argument("resume", BoolArgumentType.bool())
                        .executes(ctx -> setConfig(ctx.getSource(), "resume_after_restart",
                                Boolean.toString(BoolArgumentType.getBool(ctx, "resume"))))
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
        String when = PregenRuns.isRunning()
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
                "§a[PreGen] batch_size=%d | threads=%s | parallel_features=%s | skip_existing_chunks=%b | resume_after_restart=%b | lod_export=%b §7(change with /pregen set <key> <value>; config/%s)",
                config.batchSizeBlocks, threads, config.alwaysParallelFeatures ? "always" : "auto",
                config.skipExistingChunks, config.resumeAfterRestart, config.lodExport, PreGenConfig.FILE_NAME)), false);
        return 1;
    }

    /** /pregen start without a size: the area chosen with /pregen radius or /pregen worldborder. */
    private static int startPreGen(CommandSourceStack source) {
        int chosen = radius;
        if (chosen <= 0) {
            source.sendFailure(Component.literal("§e[PreGen] Choose the size first with /pregen radius <blocks> (or /pregen worldborder),"
                    + " or give it: /pregen start <world_k>."));
            return 0;
        }
        ServerLevel level = selectedLevel(source);
        return level == null ? 0 : report(source, PregenRuns.start(level, chosen * 2, shape, centerX, centerZ, false));
    }

    /** /pregen start <world_k>: an area world_k thousand blocks across, in the chosen shape. */
    private static int startPreGen(CommandSourceStack source, int worldK, boolean profile) {
        ServerLevel level = selectedLevel(source);
        return level == null ? 0 : report(source, PregenRuns.start(level, worldK * 1000, shape, centerX, centerZ, profile));
    }

    private static int setRadius(CommandSourceStack source, int blocks) {
        radius = blocks;
        source.sendSuccess(() -> Component.literal("§a[PreGen] Next /pregen start generates §f"
                + PregenRuns.areaText(blocks * 2, shape) + "§a" + runningNote()), true);
        return 1;
    }

    private static int setShape(CommandSourceStack source, ChunkBatchPlan.Shape chosen) {
        shape = chosen;
        source.sendSuccess(() -> Component.literal("§a[PreGen] Next /pregen start generates a §f"
                + (chosen == ChunkBatchPlan.Shape.CIRCLE ? "circle" : "square") + "§a" + runningNote()), true);
        return 1;
    }

    /** Center and radius from the selected world's border (a square), up to /pregen's largest area. */
    private static int fromWorldBorder(CommandSourceStack source) {
        ServerLevel level = selectedLevel(source);
        if (level == null) return 0;
        net.minecraft.world.level.border.WorldBorder border = level.getWorldBorder();
        int maxRadius = io.github.pongsapat3791.ultrafastpregen.api.PregenRequest.MAX_RADIUS;
        double half = border.getSize() / 2;
        int chosen = (int) Math.max(1, Math.min(maxRadius, Math.floor(half)));
        centerX = (int) Math.floor(border.getCenterX());
        centerZ = (int) Math.floor(border.getCenterZ());
        radius = chosen;
        shape = ChunkBatchPlan.Shape.SQUARE;
        String limited = half > maxRadius ? " §7(the border is larger; this is /pregen's largest area)" : "";
        source.sendSuccess(() -> Component.literal(String.format(
                "§a[PreGen] From the world border: center §f[%d, %d]§a, %s%s§a. /pregen start generates it.",
                centerX, centerZ, PregenRuns.areaText(chosen * 2, ChunkBatchPlan.Shape.SQUARE), limited)), true);
        return 1;
    }

    /** The selected world's spawn as the center. */
    private static int fromSpawn(CommandSourceStack source) {
        ServerLevel level = selectedLevel(source);
        if (level == null) return 0;
        net.minecraft.core.BlockPos spawn = level.getRespawnData().pos();
        centerX = spawn.getX();
        centerZ = spawn.getZ();
        source.sendSuccess(() -> Component.literal(String.format(
                "§a[PreGen] Next /pregen start is centered on the world spawn §f[%d, %d]§a%s", centerX, centerZ, runningNote())), true);
        return 1;
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
        ServerLevel level = selectedLevel(source);
        return level == null ? 0 : report(source, PregenRuns.resume(level));
    }

    /** Sends why a start or resume did nothing, or its note; players follow a started run on the boss bar. */
    private static int report(CommandSourceStack source, PregenRuns.Result result) {
        if (!result.started()) {
            source.sendFailure(Component.literal((result.warning() ? "§e" : "§c") + "[PreGen] " + result.message()));
            return 0;
        }
        if (!result.message().isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7[PreGen] " + result.message()), false);
        }
        return 1;
    }

    private static int resetSaveFile(CommandSourceStack source) {
        synchronized (PregenRuns.class) {
            ServerLevel level = selectedLevel(source);
            if (level == null) return 0;
            if (PregenRuns.isRunning()) {
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
        return PregenRuns.isRunning() ? " §7(the running task keeps its current settings)" : ".";
    }

    private static int showSettings(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(settingsLine()), false);
        return 1;
    }

    private static String settingsLine() {
        ResourceKey<Level> worldKey = selectedWorld;
        int chosen = radius;
        String area = chosen > 0 ? PregenRuns.areaText(chosen * 2, shape)
                : "a " + (shape == ChunkBatchPlan.Shape.CIRCLE ? "circle" : "square") + " of the size given to /pregen start";
        return String.format("§7[PreGen] Next start: world §f%s§7, center §f[%d, %d]§7, §f%s§7. "
                        + "Change with /pregen world, center, radius, shape, worldborder or spawn.",
                worldKey != null ? worldKey.identifier().toString() : "your current world", centerX, centerZ, area);
    }

    private static int stopPreGen(CommandSourceStack source) {
        if (!PregenRuns.stop()) {
            source.sendFailure(Component.literal("§e[PreGen] No active pre-generation task is running."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§6[PreGen] Cancellation request sent. Task will stop after finishing current batch."), true);
        return 1;
    }

    private static int statusPreGen(CommandSourceStack source) {
        DynamicBatchPreGen task = PregenRuns.current();
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
