package com.example.pregen;

import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Which Bukkit server this plugin runs on, and what differs between them: Spigot and CraftBukkit keep
 * Minecraft's chunk system, Paper replaces it with its own (Moonrise), which keeps its own chunk
 * holders, region file IO and light engine (Starlight, which keeps light in the chunks). Folia is
 * Paper with regions ticked on many threads and no main thread: what the others hand to the server
 * thread goes to its global region, or is done where it is, as Moonrise's chunk holders allow.
 */
final class Platform {
    /** Folia: Paper with regions ticked on many threads, and no main thread. */
    static final boolean FOLIA = has("io.papermc.paper.threadedregions.RegionizedServer");
    /** Paper's chunk system (Paper and Folia). */
    static final boolean MOONRISE = has("ca.spottedleaf.moonrise.common.util.MoonriseCommon");

    private Platform() {}

    /** The plugin, for the schedulers (set when it is enabled). */
    static volatile Plugin plugin;

    /** The name of this server's kind, for the log. */
    static String name() {
        return FOLIA ? "Folia" : MOONRISE ? "Paper" : has("org.spigotmc.SpigotConfig") ? "Spigot" : "CraftBukkit";
    }

    /** Looks up the server internals a run uses, so a server whose internals differ fails at start-up. */
    static void checkInternals() {
        if (MOONRISE) {
            MoonriseAccess.check();
        } else {
            ServerAccess.checkVanilla();
        }
    }

    private static boolean has(String className) {
        try {
            Class.forName(className, false, Platform.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /** The dimension's chunk storage. */
    static ChunkStorage storage(ServerLevel level) {
        return MOONRISE ? new MoonriseChunkIO(level) : WorldChunkIO.forLevel(level);
    }

    /** The dimension's folder, which holds its region folder and its data folder. */
    static Path dimensionFolder(ServerLevel level) {
        return MOONRISE ? MoonriseAccess.regionFolder(level).getParent()
                : level.storageSource.getDimensionPath(level.dimension());
    }

    /** Whether the world generates structures (the world's own setting on Bukkit servers). */
    static boolean generatesStructures(ServerLevel level) {
        return MOONRISE ? MoonriseAccess.generatesStructures(level)
                : level.getWorldGenSettings().options().generateStructures();
    }

    /**
     * Chunks the server is holding in memory in any state (loaded, generating, or unloaded but not
     * yet saved). They are not on disk yet, or may be newer than disk. Read on the server thread,
     * or where it is on Folia: Moonrise's chunk holders may be looked up from any thread.
     */
    static CompletableFuture<LongSet> findHeld(ServerLevel level, LongCollection positions) {
        if (!MOONRISE) return WorldChunkIO.findHeldByServer(level, positions);
        // Paper's chunk holders stay until their chunk is unloaded and its save queued.
        return onServerOrHere(level.getServer(), () -> {
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            LongSet held = new LongOpenHashSet();
            for (long pos : positions) {
                if (ServerAccess.visibleChunk(chunkMap, pos) != null) held.add(pos);
            }
            return held;
        });
    }

    /**
     * Chunks the server holds fully generated. Chunks it holds only part-way through generation are
     * not included: they do not exist yet. Read as findHeld reads.
     */
    static CompletableFuture<LongSet> findHeldFull(ServerLevel level, LongCollection positions) {
        if (!MOONRISE) return WorldChunkIO.findHeldFullByServer(level, positions);
        return onServerOrHere(level.getServer(), () -> {
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            LongSet held = new LongOpenHashSet();
            for (long pos : positions) {
                ChunkHolder holder = ServerAccess.visibleChunk(chunkMap, pos);
                if (holder != null && WorldChunkIO.isFull(holder.getLatestChunk())) held.add(pos);
            }
            return held;
        });
    }

    private static <T> CompletableFuture<T> onServerOrHere(MinecraftServer server, java.util.function.Supplier<T> task) {
        if (!FOLIA) return server.submit(task);
        try {
            return CompletableFuture.completedFuture(task.get());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** Runs the task on the server thread, or on Folia, which has none, on its global region. */
    static void runOnServer(MinecraftServer server, Runnable task) {
        if (FOLIA) {
            GlobalScheduler.execute(plugin, task);
        } else {
            server.execute(task);
        }
    }

    /** Runs the task once the server ticks (Folia: on its global region). */
    static void runNextTick(Plugin plugin, Runnable task) {
        if (FOLIA) {
            GlobalScheduler.execute(plugin, task);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /** Runs the task every tick from the next one on (Folia: on its global region). */
    static void runEveryTick(Plugin plugin, Runnable task) {
        if (FOLIA) {
            GlobalScheduler.runAtFixedRate(plugin, task);
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, task, 1, 1);
        }
    }

    /** Folia's global region scheduler (Paper's API), found by name: the plugin is built against Spigot's. */
    private static final class GlobalScheduler {
        private static final Class<?> TYPE = apiType("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
        private static final MethodHandle EXECUTE = find("execute", MethodType.methodType(void.class, Plugin.class, Runnable.class));
        private static final MethodHandle RUN_AT_FIXED_RATE = find("runAtFixedRate", MethodType.methodType(
                apiType("io.papermc.paper.threadedregions.scheduler.ScheduledTask"), Plugin.class, Consumer.class, long.class, long.class));

        private static Class<?> apiType(String name) {
            try {
                return Class.forName(name, false, Bukkit.class.getClassLoader());
            } catch (ClassNotFoundException missing) {
                throw new IllegalStateException("This server has no " + name, missing);
            }
        }

        private static MethodHandle find(String name, MethodType type) {
            try {
                return MethodHandles.publicLookup().findVirtual(TYPE, name, type);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("This server's GlobalRegionScheduler has no " + name, failure);
            }
        }

        private static Object scheduler() throws ReflectiveOperationException {
            return Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer());
        }

        static void execute(Plugin plugin, Runnable task) {
            try {
                EXECUTE.invoke(scheduler(), plugin, task);
            } catch (Throwable failure) {
                throw new IllegalStateException("Could not schedule a task on Folia's global region", failure);
            }
        }

        static void runAtFixedRate(Plugin plugin, Runnable task) {
            Consumer<Object> each = scheduled -> task.run();
            try {
                Object ignored = RUN_AT_FIXED_RATE.invoke(scheduler(), plugin, each, 1L, 1L);
            } catch (Throwable failure) {
                throw new IllegalStateException("Could not schedule a task on Folia's global region", failure);
            }
        }
    }
}
