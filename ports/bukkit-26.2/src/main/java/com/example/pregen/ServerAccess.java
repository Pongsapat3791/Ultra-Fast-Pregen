package com.example.pregen;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * The server internals the mod builds reach through mixin accessors, found by name instead: since
 * 26.1 the server runs with Minecraft's own names, so the plugin sees the same fields and methods.
 * Those every server has are looked up when the class loads, Minecraft's own chunk system (which
 * Paper replaces) when first used; a server whose internals differ fails with a clear error when
 * the plugin is enabled (see checkVanilla).
 */
final class ServerAccess {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private static final VarHandle FOLDER = field(RegionFileStorage.class, "folder");
    private static final MethodHandle UPDATING_CHUNK = method(ChunkMap.class, "getUpdatingChunkIfPresent", long.class);
    private static final MethodHandle VISIBLE_CHUNK = method(ChunkMap.class, "getVisibleChunkIfPresent", long.class);
    private static final MethodHandle DO_CREATE_BIOMES = method(NoiseBasedChunkGenerator.class, "doCreateBiomes",
            Blender.class, RandomState.class, StructureManager.class, ChunkAccess.class);
    private static final MethodHandle DO_FILL = method(NoiseBasedChunkGenerator.class, "doFill",
            Blender.class, StructureManager.class, RandomState.class, ChunkAccess.class, int.class, int.class);
    /** How long the server has been empty (pause-when-empty-seconds); null on a server without it. */
    private static final VarHandle EMPTY_TICKS = optionalField(net.minecraft.server.MinecraftServer.class, "emptyTicks");

    /**
     * Minecraft's own chunk storage, unload queue and light engine internals, which Paper's chunk
     * system (Moonrise) replaces: looked up when first used, on Spigot and CraftBukkit only.
     */
    private static final class Vanilla {
        static final VarHandle WORKER = field(SimpleRegionStorage.class, "worker");
        static final VarHandle STORAGE = field(IOWorker.class, "storage");
        static final VarHandle PENDING_WRITES = field(IOWorker.class, "pendingWrites");
        static final MethodHandle SUBMIT_TASK = method(IOWorker.class, "submitTask", Supplier.class);
        static final VarHandle PENDING_DATA = field(type("net.minecraft.world.level.chunk.storage.IOWorker$PendingStore"), "data");
        static final VarHandle REGION_CACHE = field(RegionFileStorage.class, "regionCache");
        static final MethodHandle REGION_WRITE = method(RegionFile.class, "write", ChunkPos.class, ByteBuffer.class);
        static final VarHandle PENDING_UNLOADS = field(ChunkMap.class, "pendingUnloads");
        static final MethodHandle UPDATE_CHUNK_STATUS = method(ThreadedLevelLightEngine.class, "updateChunkStatus", ChunkPos.class);
        /** RegionFileStorage#getRegionFile: CraftBukkit adds whether only an existing file may be opened. */
        static final MethodHandle GET_REGION_FILE = regionFileGetter();
    }

    private ServerAccess() {}

    /** Forces the lookups of Minecraft's own chunk system (the plugin's start-up on Spigot and CraftBukkit). */
    static void checkVanilla() {
        Vanilla.GET_REGION_FILE.type();
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name, false, IOWorker.class.getClassLoader());
        } catch (ClassNotFoundException missing) {
            throw new IllegalStateException("This server has no " + name, missing);
        }
    }

    private static VarHandle field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            return MethodHandles.privateLookupIn(owner, LOOKUP).unreflectVarHandle(field);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("This server has no field " + owner.getName() + "." + name, failure);
        }
    }

    private static VarHandle optionalField(Class<?> owner, String name) {
        try {
            return field(owner, name);
        } catch (IllegalStateException absent) {
            return null;
        }
    }

    private static MethodHandle method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            Method method = owner.getDeclaredMethod(name, parameters);
            return MethodHandles.privateLookupIn(owner, LOOKUP).unreflect(method);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("This server has no method " + owner.getName() + "." + name, failure);
        }
    }

    private static MethodHandle regionFileGetter() {
        try {
            Method method = RegionFileStorage.class.getDeclaredMethod("getRegionFile", ChunkPos.class, boolean.class);
            MethodHandle handle = MethodHandles.privateLookupIn(RegionFileStorage.class, LOOKUP).unreflect(method);
            return MethodHandles.insertArguments(handle, 2, false);
        } catch (NoSuchMethodException vanilla) {
            return method(RegionFileStorage.class, "getRegionFile", ChunkPos.class);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("RegionFileStorage.getRegionFile is not accessible", failure);
        }
    }

    private static RuntimeException rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException(failure);
    }

    /** The dimension's IO worker; ChunkMap extends SimpleRegionStorage. */
    static IOWorker worker(ChunkMap chunkMap) {
        return (IOWorker) Vanilla.WORKER.get((SimpleRegionStorage) chunkMap);
    }

    /** Only from a task the worker runs (see submitTask). */
    static RegionFileStorage storage(IOWorker worker) {
        return (RegionFileStorage) Vanilla.STORAGE.get(worker);
    }

    /** Saves the worker has queued and not written yet; only from a task it runs. */
    static SequencedMap<ChunkPos, ?> pendingWrites(IOWorker worker) {
        return (SequencedMap<ChunkPos, ?>) Vanilla.PENDING_WRITES.get(worker);
    }

    /** Runs on the IO thread; a thrown exception fails the future. */
    @SuppressWarnings("unchecked")
    static <T> CompletableFuture<T> submitTask(IOWorker worker, Supplier<T> task) {
        try {
            return (CompletableFuture<T>) Vanilla.SUBMIT_TASK.invoke(worker, task);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    static CompoundTag pendingData(Object pendingStore) {
        return (CompoundTag) Vanilla.PENDING_DATA.get(pendingStore);
    }

    static void setPendingData(Object pendingStore, CompoundTag data) {
        Vanilla.PENDING_DATA.set(pendingStore, data);
    }

    static Path folder(RegionFileStorage storage) {
        return (Path) FOLDER.get(storage);
    }

    /** Open region files keyed by ChunkPos.pack(regionX, regionZ). */
    @SuppressWarnings("unchecked")
    static Long2ObjectLinkedOpenHashMap<RegionFile> regionCache(RegionFileStorage storage) {
        return (Long2ObjectLinkedOpenHashMap<RegionFile>) Vanilla.REGION_CACHE.get(storage);
    }

    /** Opens (creating if needed) and caches the region file, exactly as the server's own reads and writes do. */
    static RegionFile regionFile(RegionFileStorage storage, ChunkPos pos) throws java.io.IOException {
        try {
            return (RegionFile) Vanilla.GET_REGION_FILE.invoke(storage, pos);
        } catch (java.io.IOException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /** Stores an already framed [length][compression id][payload] buffer; synchronized in Minecraft. */
    static void write(RegionFile region, ChunkPos pos, ByteBuffer framed) throws java.io.IOException {
        try {
            Vanilla.REGION_WRITE.invoke(region, pos, framed);
        } catch (java.io.IOException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /** Server thread only. */
    static ChunkHolder updatingChunk(ChunkMap chunkMap, long pos) {
        try {
            return (ChunkHolder) UPDATING_CHUNK.invoke(chunkMap, pos);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /** Server thread only. */
    static ChunkHolder visibleChunk(ChunkMap chunkMap, long pos) {
        try {
            return (ChunkHolder) VISIBLE_CHUNK.invoke(chunkMap, pos);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /** Server thread only. */
    @SuppressWarnings("unchecked")
    static Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads(ChunkMap chunkMap) {
        return (Long2ObjectLinkedOpenHashMap<ChunkHolder>) Vanilla.PENDING_UNLOADS.get(chunkMap);
    }

    /** Same call ChunkMap makes when a chunk unloads: drops the chunk's block and sky light data. */
    static void updateChunkStatus(ThreadedLevelLightEngine engine, ChunkPos pos) {
        try {
            Vanilla.UPDATE_CHUNK_STATUS.invoke(engine, pos);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /**
     * Keeps the server from pausing while it is empty (pause-when-empty-seconds), as if a player were
     * online. Server thread only. A paused server no longer drops the chunks it loads for others,
     * such as those around a structure lookup made during a batch (see DynamicBatchPreGen#awaitReleased).
     */
    static void keepAwake(net.minecraft.server.MinecraftServer server) {
        if (EMPTY_TICKS != null) EMPTY_TICKS.set(server, 0);
    }

    /** NoiseBasedChunkGenerator's biome step itself, on the calling thread (Minecraft hands it to its own pool). */
    static void createBiomes(NoiseBasedChunkGenerator generator, Blender blender, RandomState randomState,
                             StructureManager structureManager, ChunkAccess chunk) {
        try {
            DO_CREATE_BIOMES.invoke(generator, blender, randomState, structureManager, chunk);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /** NoiseBasedChunkGenerator's noise fill itself, on the calling thread. */
    static void fill(NoiseBasedChunkGenerator generator, Blender blender, StructureManager structureManager,
                     RandomState randomState, ChunkAccess chunk, int minCellY, int cellCountY) {
        try {
            DO_FILL.invoke(generator, blender, structureManager, randomState, chunk, minCellY, cellCountY);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }
}
