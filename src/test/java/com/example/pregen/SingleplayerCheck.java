package com.example.pregen;

import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;

/**
 * Opt-in check in a real client (runSingleplayerCheck): the integrated server, which shares the
 * client's heap and pauses while the game window is not focused, runs /pregen to the end; the
 * area's chunks are stored and the server keeps answering. Then the client quits.
 */
public final class SingleplayerCheck {
    /** The integrated server has started (see fabric.FabricChecks). */
    public static void onServerStarted(MinecraftServer server) {
        if (!Boolean.getBoolean("pregen.singleplayerCheck")) return;
        Thread check = new Thread(() -> run(server), "SingleplayerCheck");
        check.setDaemon(true);
        check.start();
    }

    private static void run(MinecraftServer server) {
        String result;
        try {
            Thread.sleep(5_000);
            // The test world has cheats off: its owner has no op level, and may still use /pregen.
            boolean ownerCanPregen = server.submit(() -> {
                var players = server.getPlayerList().getPlayers();
                if (players.isEmpty()) throw new AssertionError("The world's owner must have joined");
                var node = server.getCommands().getDispatcher().getRoot().getChild("pregen");
                return node != null && node.canUse(players.get(0).createCommandSourceStack());
            }).get(10, TimeUnit.SECONDS);
            if (!ownerCanPregen) throw new AssertionError("The singleplayer owner must be able to use /pregen without cheats");
            int centerBlock = Integer.getInteger("pregen.singleplayerCenter", 300000);
            command(server, "pregen center " + centerBlock + " " + centerBlock);
            command(server, "pregen start " + Integer.getInteger("pregen.singleplayerArea", 1));
            DynamicBatchPreGen task = null;
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(15);
            while (System.nanoTime() < deadline) {
                task = activeTask();
                if (task != null && !task.isRunning()) break;
                Thread.sleep(1_000);
            }
            if (task == null || task.isRunning()) throw new AssertionError("/pregen did not finish in singleplayer");
            if (!"Completed".equals(task.getCurrentStageName())) throw new AssertionError("Run ended as " + task.getCurrentStageName());
            var io = ((com.example.pregen.mixin.ChunkStorageAccessor) server.overworld().getChunkSource().chunkMap).pregen$getWorker();
            ChunkPos center = new ChunkPos(centerBlock >> 4, centerBlock >> 4);
            var stored = io.loadAsync(center).get(30, TimeUnit.SECONDS).orElseThrow(
                    () -> new AssertionError("The area's center chunk must be stored"));
            if (!"minecraft:full".equals(stored.getString("Status"))) throw new AssertionError("Center chunk not full");
            long answered = server.submit(System::nanoTime).get(10, TimeUnit.SECONDS);
            result = String.format(java.util.Locale.ROOT, "SINGLEPLAYER_CHECK PASS speed=%.1f chunks/s max_heap=%dMB server_answers=%b owner_can_pregen=true",
                    task.getChunksPerSecond(), Runtime.getRuntime().maxMemory() >> 20, answered > 0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            result = "SINGLEPLAYER_CHECK FAIL " + failure;
        }
        System.out.println(result);
        try {
            Object minecraft = Class.forName("net.minecraft.client.Minecraft").getMethod("getInstance").invoke(null);
            minecraft.getClass().getMethod("stop").invoke(minecraft);
        } catch (ReflectiveOperationException failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void command(MinecraftServer server, String command) throws Exception {
        server.submit(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command))
                .get(30, TimeUnit.SECONDS);
    }

    private static DynamicBatchPreGen activeTask() {
        return PregenRuns.current();
    }
}
