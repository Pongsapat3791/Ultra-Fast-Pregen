package com.example.pregen;

import com.example.pregen.mixin.RegionFileStorageAccessor;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;

/**
 * C2ME's storage thread, which replaces Minecraft's IOWorker while C2ME's ioSystem.replaceImpl is
 * on (its default); forks such as FastChunkGen carry the same classes under their own package.
 * The IOWorker's own queue fields are left null. C2ME keeps a RegionFileStorage that only its
 * thread touches, and a save waits in its cache, as an NBT tag or uncompressed NBT bytes (held
 * directly by older versions, in a future by newer ones), until written. A newer save of a chunk replaces the older one there, and the older one is
 * then never written. C2ME offers no way to run other code on that thread, so its fields are found
 * by name. A C2ME version that renamed them fails with a message naming the setting that turns the
 * replacement off.
 */
final class C2meStorage implements WorldChunkIO.StorageThread {
    private final IOWorker worker;
    private final Executor executor;
    private final RegionFileStorageAccessor storage;
    private final Long2ReferenceMap<?> cache;
    private final Method left;
    private final Method right;

    private C2meStorage(IOWorker worker, Executor executor, RegionFileStorage storage, Long2ReferenceMap<?> cache,
                        Method left, Method right) {
        this.worker = worker;
        this.executor = executor;
        this.storage = (RegionFileStorageAccessor) (Object) storage;
        this.cache = cache;
        this.left = left;
        this.right = right;
    }

    static C2meStorage of(IOWorker worker) {
        try {
            Object backend = field(worker.getClass(), "backend").get(worker);
            Class<?> type = backend.getClass();
            Executor executor = (Executor) field(type, "executor").get(backend);
            RegionFileStorage storage = (RegionFileStorage) field(type, "storage").get(backend);
            Long2ReferenceMap<?> cache = (Long2ReferenceMap<?>) field(type, "cache").get(backend);
            Class<?> either = Class.forName("com.ibm.asyncutil.util.Either", false, type.getClassLoader());
            if (executor == null || storage == null || cache == null) throw new NoSuchFieldException("unset storage fields");
            return new C2meStorage(worker, executor, storage, cache, either.getMethod("left"), either.getMethod("right"));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("This world's chunk storage (" + worker.getClass().getName()
                    + ") is not Minecraft's, and not a C2ME version Ultra Fast Pregen can write to."
                    + " With C2ME, set ioSystem.replaceImpl = false in config/c2me.toml (a fork of it: the same"
                    + " setting in its own config)", failure);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // declared further up
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    @Override
    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, this::queue);
    }

    /**
     * C2ME's executor runs a task at once when called from its own thread, so tasks chained there
     * would hold it back to back. Handed over from another thread, each queues behind the
     * server's own loads and saves, as with Minecraft's IOWorker.
     */
    private void queue(Runnable task) {
        java.util.concurrent.ForkJoinPool.commonPool().execute(() -> executor.execute(task));
    }

    @Override
    public RegionFileStorageAccessor storage() {
        return storage;
    }

    @Override
    public WorldChunkIO.QueuedSave queued(ChunkPos pos) {
        long key = pos.pack();
        Object entry = cache.get(key);
        // Older versions hold a pending deletion as a null entry; newer ones as a future of null.
        if (entry == null && !cache.containsKey(key)) return null;
        CompletableFuture<?> data = entry instanceof CompletionStage<?> stage
                ? stage.toCompletableFuture() : CompletableFuture.completedFuture(entry);
        return new WorldChunkIO.QueuedSave() {
            @Override
            public boolean isFullyGenerated() {
                // Still being serialized: unknown.
                if (!data.isDone() || data.isCompletedExceptionally()) return true;
                Object either = data.getNow(null);
                if (either == null) return false;  // the chunk is being deleted
                try {
                    Optional<?> tag = (Optional<?>) left.invoke(either);
                    if (tag.isPresent()) return WorldChunkIO.isFullyGenerated((CompoundTag) tag.get());
                    Optional<?> bytes = (Optional<?>) right.invoke(either);
                    return bytes.isEmpty() || WorldChunkIO.isFullyGenerated((byte[]) bytes.get());
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    return true;
                }
            }

            @Override
            public void replaceWith(CompoundTag replacement) {
                // On C2ME's thread this queues at once, replacing the older save in its cache.
                worker.store(pos, replacement);
            }
        };
    }
}
