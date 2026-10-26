package com.example.pregen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;

/**
 * The few parts of Paper's chunk system (Moonrise) a run needs: its region file IO, which reads and
 * writes chunks on its own threads. Found by name, as the plugin is built against Spigot; this class
 * loads only on Paper.
 */
final class MoonriseAccess {
    private static final String IO = "ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO";
    private static final Class<?> FILE_TYPE = type(IO + "$RegionFileType");
    /** RegionFileType.CHUNK_DATA: the chunks themselves (not POI or entities). */
    private static final Object CHUNK_DATA = chunkData();
    private static final MethodHandle SCHEDULE_SAVE = staticMethod(IO, "scheduleSave",
            MethodType.methodType(void.class, ServerLevel.class, int.class, int.class, CompoundTag.class, FILE_TYPE));
    private static final MethodHandle LOAD_DATA_ASYNC = staticMethod(IO, "loadDataAsync",
            MethodType.methodType(type("ca.spottedleaf.concurrentutil.executor.Cancellable"), ServerLevel.class, int.class,
                    int.class, FILE_TYPE, BiConsumer.class, boolean.class));
    private static final MethodHandle FLUSH = staticMethod(IO, "flush", MethodType.methodType(void.class, ServerLevel.class, FILE_TYPE));
    /** ServerLevel#worldGenSettings: Paper keeps a world's settings in a field (Spigot: getWorldGenSettings()). */
    private static final MethodHandle WORLD_GEN_SETTINGS = worldGenSettingsGetter();
    private static final MethodHandle REGION_STORAGE = interfaceMethod(
            "ca.spottedleaf.moonrise.patches.chunk_system.storage.ChunkSystemSimpleRegionStorage", "moonrise$getRegionStorage",
            MethodType.methodType(RegionFileStorage.class));

    private MoonriseAccess() {}

    /** Forces the lookups (the plugin's start-up on Paper). */
    static void check() {}

    private static Class<?> type(String name) {
        try {
            return Class.forName(name, false, ServerLevel.class.getClassLoader());
        } catch (ClassNotFoundException missing) {
            throw new IllegalStateException("This server has no " + name, missing);
        }
    }

    private static Object chunkData() {
        for (Object constant : FILE_TYPE.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals("CHUNK_DATA")) return constant;
        }
        throw new IllegalStateException("This server's Moonrise has no RegionFileType.CHUNK_DATA");
    }

    private static MethodHandle staticMethod(String owner, String name, MethodType type) {
        try {
            return MethodHandles.publicLookup().findStatic(type(owner), name, type);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("This server has no method " + owner + "." + name + type, failure);
        }
    }

    private static MethodHandle interfaceMethod(String owner, String name, MethodType type) {
        try {
            return MethodHandles.publicLookup().findVirtual(type(owner), name, type);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("This server has no method " + owner + "." + name + type, failure);
        }
    }

    private static MethodHandle worldGenSettingsGetter() {
        try {
            return MethodHandles.publicLookup().findGetter(ServerLevel.class, "worldGenSettings",
                    net.minecraft.world.level.levelgen.WorldGenSettings.class);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("This server has no field ServerLevel.worldGenSettings", failure);
        }
    }

    private static RuntimeException rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException(failure);
    }

    /** Queues the chunk's data to be written; a later save of the same chunk replaces an earlier one. */
    static void scheduleSave(ServerLevel level, int chunkX, int chunkZ, CompoundTag data) {
        try {
            SCHEDULE_SAVE.invoke(level, chunkX, chunkZ, data, CHUNK_DATA);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /** A stored chunk's data, including a save queued and not written yet. */
    static CompletableFuture<Optional<CompoundTag>> load(ServerLevel level, int chunkX, int chunkZ) {
        CompletableFuture<Optional<CompoundTag>> loaded = new CompletableFuture<>();
        BiConsumer<CompoundTag, Throwable> done = (data, failure) -> {
            if (failure != null) loaded.completeExceptionally(failure);
            else loaded.complete(Optional.ofNullable(data));
        };
        try {
            Object ignored = LOAD_DATA_ASYNC.invoke(level, chunkX, chunkZ, CHUNK_DATA, done, false);
        } catch (Throwable failure) {
            loaded.completeExceptionally(failure);
        }
        return loaded;
    }

    /** Waits until every chunk save queued for the level is written. */
    static void flush(ServerLevel level) {
        try {
            FLUSH.invoke(level, CHUNK_DATA);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /** Whether the world generates structures. */
    static boolean generatesStructures(ServerLevel level) {
        try {
            var settings = (net.minecraft.world.level.levelgen.WorldGenSettings) WORLD_GEN_SETTINGS.invoke(level);
            return settings.options().generateStructures();
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /** The folder of the level's chunk region files. */
    static Path regionFolder(ServerLevel level) {
        try {
            RegionFileStorage storage = (RegionFileStorage) REGION_STORAGE.invoke(level.getChunkSource().chunkMap);
            return ServerAccess.folder(storage);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }
}
