package com.example.pregen;

import com.example.pregen.mixin.ChunkMapAccessor;
import com.example.pregen.mixin.ChunkStorageAccessor;
import com.example.pregen.mixin.IOWorkerAccessor;
import com.example.pregen.mixin.RegionFileInvoker;
import com.example.pregen.mixin.RegionFileStorageAccessor;
import com.example.pregen.mixin.PendingStoreAccessor;
import com.mojang.datafixers.util.Either;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.Tag;
import java.io.DataOutputStream;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

/**
 * Chunk existence checks and writes that go through the server's own region storage.
 *
 * The server caches open RegionFile objects, each with its own in-memory sector table and
 * header. Writing the same .mca through a second, concurrently open RegionFile lets whichever
 * writes last erase the other's chunk offsets. Every task here therefore runs on the
 * dimension's storage thread (see StorageThread), the only thread that touches its region files.
 */
final class WorldChunkIO {
    /** Chunk payload in the [length][compression id][compressed NBT] layout of a region sector. */
    record EncodedChunk(ChunkPos pos, ByteBuffer frame) {}

    /** Updated only by the IO thread; read by the generation thread for progress. */
    static final class WriteResult {
        final int total;
        volatile int written;
        volatile int skippedExisting;
        volatile int regionsDone;
        final int regions;
        volatile long ioNanos;

        WriteResult(int total, int regions) {
            this.total = total;
            this.regions = regions;
        }

        int processed() {
            return written + skippedExisting;
        }
    }

    /** Chunks per IO task, so the server's own chunk loads run between our batches. */
    static final int WRITE_BATCH = 64;
    /** RegionFileStorage's own limit on open region files. */
    private static final int MAX_OPEN_REGIONS = 256;

    private final IOWorker worker;
    private final StorageThread thread;
    private final int writeBatch;
    /** Region files we opened without DSYNC; touched only on the IO thread. */
    private final Map<Long, RegionFile> fastHandles = new HashMap<>();

    WorldChunkIO(IOWorker worker) {
        this(worker, WRITE_BATCH);
    }

    WorldChunkIO(IOWorker worker, int writeBatch) {
        this(worker, StorageThread.of(worker), writeBatch);
    }

    /** Tests: a storage thread given directly (see C2meStorage). */
    WorldChunkIO(IOWorker worker, StorageThread thread, int writeBatch) {
        this.worker = worker;
        this.thread = thread;
        this.writeBatch = writeBatch;
    }

    /**
     * The thread that owns a dimension's region files. Its RegionFileStorage and the saves it has
     * queued but not written yet are touched only by tasks it runs. Minecraft's IOWorker, or the
     * storage thread C2ME (and its forks) replace it with (see C2meStorage).
     */
    interface StorageThread {
        /** Runs the task there; a right result fails the returned future with that exception. */
        <T> CompletableFuture<T> submit(Supplier<Either<T, Exception>> task);

        /** Only from a task this thread runs. */
        RegionFileStorageAccessor storage();

        /** The save queued for this chunk, or null. Only from a task this thread runs. */
        QueuedSave queued(ChunkPos pos);

        static StorageThread of(IOWorker worker) {
            IOWorkerAccessor vanilla = (IOWorkerAccessor) worker;
            return vanilla.pregen$getStorage() != null ? new VanillaStorage(vanilla) : C2meStorage.of(worker);
        }
    }

    /** A chunk save queued on the storage thread, not written to its region file yet. */
    interface QueuedSave {
        /** Whether it stores a fully generated chunk; unknown counts as generated. */
        boolean isFullyGenerated();

        /** Makes it store {@code data} instead, so it cannot restore an older chunk over a raw region write. */
        void replaceWith(CompoundTag data);
    }

    private record VanillaStorage(IOWorkerAccessor worker) implements StorageThread {
        @Override
        public <T> CompletableFuture<T> submit(Supplier<Either<T, Exception>> task) {
            return worker.pregen$submitTask(task);
        }

        @Override
        public RegionFileStorageAccessor storage() {
            return (RegionFileStorageAccessor) (Object) worker.pregen$getStorage();
        }

        @Override
        public QueuedSave queued(ChunkPos pos) {
            PendingStoreAccessor store = (PendingStoreAccessor) worker.pregen$getPendingWrites().get(pos);
            if (store == null) return null;
            return new QueuedSave() {
                @Override
                public boolean isFullyGenerated() {
                    return WorldChunkIO.isFullyGenerated(store.pregen$getData());
                }

                @Override
                public void replaceWith(CompoundTag data) {
                    store.pregen$setData(data);
                }
            };
        }
    }

    static WorldChunkIO forLevel(ServerLevel level) {
        return new WorldChunkIO(((ChunkStorageAccessor) level.getChunkSource().chunkMap).pregen$getWorker());
    }

    /**
     * Chunks the server is holding in memory in any state (loaded, generating, or unloaded but
     * not yet saved). They are not on disk yet, or may be newer than disk. Runs on the server thread.
     */
    static CompletableFuture<LongSet> findHeldByServer(ServerLevel level, LongCollection positions) {
        return level.getServer().submit(() -> {
            ChunkMapAccessor chunkMap = (ChunkMapAccessor) level.getChunkSource().chunkMap;
            LongSet held = new LongOpenHashSet();
            for (long pos : positions) {
                if (chunkMap.pregen$getUpdatingChunk(pos) != null || chunkMap.pregen$getVisibleChunk(pos) != null
                        || chunkMap.pregen$getPendingUnloads().containsKey(pos)) {
                    held.add(pos);
                }
            }
            return held;
        });
    }

    /**
     * Which chunks of the given regions exist fully generated, on disk or in a queued save: per
     * region key, a 1024-bit set indexed (x & 31) * 32 + (z & 31). Chunks stored part-way through
     * generation do not count; they are generated and overwritten. Only each stored chunk's
     * status is read, and a missing region file is not created. One IO-thread task per region,
     * so the server's own chunk loads and saves run in between.
     */
    /** Per region (see isStored): chunks fully generated, and chunks stored in any status. */
    record StoredChunks(Map<Long, long[]> full, Map<Long, long[]> present) {
        static final StoredChunks NONE = new StoredChunks(Map.of(), Map.of());
    }

    CompletableFuture<StoredChunks> findStoredInRegions(LongCollection regionKeys) {
        Map<Long, long[]> full = new HashMap<>();
        Map<Long, long[]> present = new HashMap<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (long regionKey : regionKeys) {
            chain = chain.thenCompose(ignored -> thread.<Void>submit(() -> {
                try {
                    long[][] bits = collectStoredInRegion(regionKey);
                    if (bits != null) {
                        full.put(regionKey, bits[0]);
                        present.put(regionKey, bits[1]);
                    }
                    return Either.left(null);
                } catch (IOException | RuntimeException failure) {
                    return Either.right(failure);
                }
            }));
        }
        return chain.thenApply(ignored -> new StoredChunks(full, present));
    }

    /** A stored chunk's data (including a queued save), read on the IO thread. */
    CompletableFuture<java.util.Optional<CompoundTag>> load(long chunk) {
        return worker.loadAsync(new ChunkPos(chunk));
    }

    private long[][] collectStoredInRegion(long regionKey) throws IOException {
        RegionFileStorageAccessor storage = thread.storage();
        int regionX = ChunkPos.getX(regionKey);
        int regionZ = ChunkPos.getZ(regionKey);
        boolean fileExists = Files.exists(storage.pregen$getFolder().resolve("r." + regionX + "." + regionZ + ".mca"));
        RegionFile region = fileExists ? storage.pregen$getRegionFile(new ChunkPos(regionX << 5, regionZ << 5)) : null;
        long[] fullBits = new long[16];
        long[] presentBits = new long[16];
        boolean any = false;
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                ChunkPos pos = new ChunkPos((regionX << 5) + x, (regionZ << 5) + z);
                QueuedSave queued = thread.queued(pos);
                boolean stored = queued != null || (region != null && region.hasChunk(pos));
                if (!stored) continue;
                int index = x * 32 + z;
                presentBits[index >>> 6] |= 1L << index;
                any = true;
                boolean full = queued != null ? queued.isFullyGenerated() : isFullyGenerated(region, pos);
                if (full) fullBits[index >>> 6] |= 1L << index;
            }
        }
        return any ? new long[][]{fullBits, presentBits} : null;
    }

    /** A queued chunk save's status; a missing or unknown status counts as generated. */
    static boolean isFullyGenerated(CompoundTag data) {
        if (data == null) return false;
        if (data.contains("Status", Tag.TAG_STRING)) return isFullStatus(data.getString("Status"));
        CompoundTag level = data.getCompound("Level");
        return !level.contains("Status", Tag.TAG_STRING) || isFullStatus(level.getString("Status"));
    }

    /**
     * Reads only a stored chunk's status (it comes first in the NBT, so little is decompressed).
     * Chunks from before 1.18 keep it under Level. Unreadable or unknown counts as generated.
     */
    static boolean isFullyGenerated(RegionFile region, ChunkPos pos) {
        return isFullyGenerated(() -> region.getChunkDataInputStream(pos));
    }

    /** The same for a chunk's uncompressed NBT. */
    static boolean isFullyGenerated(byte[] nbt) {
        return isFullyGenerated(() -> new DataInputStream(new ByteArrayInputStream(nbt)));
    }

    private interface ChunkStream {
        DataInputStream open() throws IOException;
    }

    private static boolean isFullyGenerated(ChunkStream chunk) {
        try (DataInputStream in = chunk.open()) {
            if (in == null) return true;
            String status = readStatus(in);
            return status == null || isFullStatus(status);
        } catch (IOException | RuntimeException failure) {
            return true;
        }
    }

    /**
     * Status, else Level/Status, of a chunk's uncompressed NBT, read without building tags; reading
     * stops at the root's Status. Parsed here rather than with NbtIo.parse, whose parameters
     * changed in 1.20.2, so one jar runs on 1.20 to 1.20.6.
     */
    private static String readStatus(DataInput in) throws IOException {
        if (in.readByte() != Tag.TAG_COMPOUND) return null;
        in.readUTF();
        return compoundStatus(in, true, 1);
    }

    /** Reads a compound's entries; the root returns at its Status, Level is read to its end. */
    private static String compoundStatus(DataInput in, boolean root, int depth) throws IOException {
        String status = null;
        String levelStatus = null;
        for (byte type = in.readByte(); type != Tag.TAG_END; type = in.readByte()) {
            String name = in.readUTF();
            if (type == Tag.TAG_STRING && name.equals("Status")) {
                status = in.readUTF();
                if (root) return status;
            } else if (root && type == Tag.TAG_COMPOUND && name.equals("Level")) {
                levelStatus = compoundStatus(in, false, depth + 1);
            } else {
                skipPayload(in, type, depth + 1);
            }
        }
        return status != null ? status : levelStatus;
    }

    /** Skips one tag's payload, nested at most 512 deep as in Minecraft. */
    private static void skipPayload(DataInput in, byte type, int depth) throws IOException {
        if (depth > 512) throw new IOException("NBT nested deeper than 512");
        switch (type) {
            case Tag.TAG_BYTE, Tag.TAG_SHORT, Tag.TAG_INT, Tag.TAG_LONG, Tag.TAG_FLOAT, Tag.TAG_DOUBLE ->
                    skip(in, fixedSize(type));
            case Tag.TAG_BYTE_ARRAY -> skip(in, length(in));
            case Tag.TAG_STRING -> skip(in, in.readUnsignedShort());
            case Tag.TAG_INT_ARRAY -> skip(in, 4L * length(in));
            case Tag.TAG_LONG_ARRAY -> skip(in, 8L * length(in));
            case Tag.TAG_LIST -> {
                byte element = in.readByte();
                int count = length(in);
                int size = fixedSize(element);
                if (size > 0) {
                    skip(in, (long) size * count);
                } else {
                    for (int i = 0; i < count; i++) skipPayload(in, element, depth + 1);
                }
            }
            case Tag.TAG_COMPOUND -> {
                for (byte entry = in.readByte(); entry != Tag.TAG_END; entry = in.readByte()) {
                    skip(in, in.readUnsignedShort());
                    skipPayload(in, entry, depth + 1);
                }
            }
            default -> throw new IOException("Unknown NBT tag type " + type);
        }
    }

    /** Bytes of a number tag's payload; 0 for every other type. */
    private static int fixedSize(byte type) {
        return switch (type) {
            case Tag.TAG_BYTE -> 1;
            case Tag.TAG_SHORT -> 2;
            case Tag.TAG_INT, Tag.TAG_FLOAT -> 4;
            case Tag.TAG_LONG, Tag.TAG_DOUBLE -> 8;
            default -> 0;
        };
    }

    private static int length(DataInput in) throws IOException {
        int length = in.readInt();
        if (length < 0) throw new IOException("Negative NBT length " + length);
        return length;
    }

    private static void skip(DataInput in, long bytes) throws IOException {
        while (bytes > 0) {
            int skipped = in.skipBytes((int) Math.min(bytes, Integer.MAX_VALUE));
            if (skipped <= 0) {
                in.readByte();
                skipped = 1;
            }
            bytes -= skipped;
        }
    }

    /** The final status in this and older versions (1.13 to 1.17 used "full", 1.13 also "fullchunk"/"postprocessed"). */
    private static boolean isFullStatus(String status) {
        return status.equals("minecraft:full") || status.equals("full") || status.equals("fullchunk")
                || status.equals("postprocessed");
    }

    static boolean isStored(Map<Long, long[]> stored, long chunk) {
        int x = ChunkPos.getX(chunk);
        int z = ChunkPos.getZ(chunk);
        long[] bits = stored.get(ChunkPos.asLong(x >> 5, z >> 5));
        int index = (x & 31) * 32 + (z & 31);
        return bits != null && (bits[index >>> 6] & 1L << index) != 0;
    }

    /**
     * Chunks the server holds fully generated (loaded, or unloading but not yet saved). Chunks it
     * holds only part-way through generation are not included: they do not exist yet. Runs on
     * the server thread.
     */
    static CompletableFuture<LongSet> findHeldFullByServer(ServerLevel level, LongCollection positions) {
        return level.getServer().submit(() -> {
            ChunkMapAccessor chunkMap = (ChunkMapAccessor) level.getChunkSource().chunkMap;
            LongSet held = new LongOpenHashSet();
            for (long pos : positions) {
                ChunkHolder holder = chunkMap.pregen$getUpdatingChunk(pos);
                if (holder == null) holder = chunkMap.pregen$getVisibleChunk(pos);
                if (holder == null) holder = chunkMap.pregen$getPendingUnloads().get(pos);
                if (holder != null && isFull(holder.getLastAvailable())) held.add(pos);
            }
            return held;
        });
    }

    /** A fully generated chunk, or one viewed through a lower status (ImposterProtoChunk). */
    static boolean isFull(ChunkAccess chunk) {
        return chunk instanceof LevelChunk || chunk instanceof ImposterProtoChunk;
    }

    /** Chunks stored in any generation status, or queued to be stored. Runs on the IO thread. */
    CompletableFuture<LongSet> findStored(LongCollection positions) {
        return thread.submit(() -> {
            try {
                return Either.left(collectStored(positions));
            } catch (IOException | RuntimeException failure) {
                return Either.right(failure);
            }
        });
    }

    private LongSet collectStored(LongCollection positions) throws IOException {
        RegionFileStorageAccessor storage = thread.storage();
        Path folder = storage.pregen$getFolder();
        Map<Long, Boolean> regionExists = new HashMap<>();
        LongSet stored = new LongOpenHashSet();
        for (long packed : positions) {
            ChunkPos pos = new ChunkPos(packed);
            if (thread.queued(pos) != null) {
                stored.add(packed);
                continue;
            }
            // Avoid getRegionFile here: it creates missing region files.
            boolean exists = regionExists.computeIfAbsent(ChunkPos.asLong(pos.getRegionX(), pos.getRegionZ()),
                    key -> Files.exists(folder.resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca")));
            if (exists && storage.pregen$getRegionFile(pos).hasChunk(pos)) stored.add(packed);
        }
        return stored;
    }

    /**
     * Stores chunks that are still absent when their region's task runs; a chunk the server saved
     * (or queued) in the meantime is left untouched. One IO task per region file, chained, so the
     * server's own chunk loads and saves run between regions.
     *
     * With sync-chunk-writes (the dedicated-server default) the server's cached RegionFile is
     * opened with DSYNC, so every chunk write and its header rewrite each wait for the disk;
     * thousands of chunks then take minutes. Within one task, the cached file is closed (as the
     * server's own cache eviction does), the region is written through a private handle without
     * DSYNC, and that handle is closed, which forces it to disk once. No other IO task can run in
     * between, so two handles are never open at once; the server reopens the file on next use and
     * reads the updated header.
     */
    /**
     * Applies feature writes from a later batch to chunks this run already saved (see
     * SeamWrites.applyToSaved). One IO-thread task per region. A chunk with a queued server save
     * is left alone: the server's version is newer. Completes with the number of chunks changed.
     */
    CompletableFuture<Integer> mergeIntoSaved(Map<Long, List<SeamWrites.Write>> writes) {
        Map<Long, List<Long>> byRegion = new LinkedHashMap<>();
        for (long chunk : writes.keySet()) {
            byRegion.computeIfAbsent(ChunkPos.asLong(ChunkPos.getX(chunk) >> 5, ChunkPos.getZ(chunk) >> 5),
                    key -> new ArrayList<>()).add(chunk);
        }
        int[] merged = new int[1];
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Map.Entry<Long, List<Long>> region : byRegion.entrySet()) {
            chain = chain.thenCompose(ignored -> thread.<Void>submit(() -> {
                try {
                    merged[0] += mergeRegion(region.getKey(), region.getValue(), writes);
                    return Either.left(null);
                } catch (IOException | RuntimeException failure) {
                    return Either.right(failure);
                }
            }));
        }
        return chain.thenApply(ignored -> merged[0]);
    }

    private int mergeRegion(long regionKey, List<Long> chunks, Map<Long, List<SeamWrites.Write>> writes) throws IOException {
        RegionFileStorageAccessor storage = thread.storage();
        int merged = 0;
        try {
            RegionFile region = fastHandle(storage, regionKey);
            for (long packed : chunks) {
                ChunkPos pos = new ChunkPos(packed);
                if (thread.queued(pos) != null) continue;
                CompoundTag tag;
                try (DataInputStream in = region.getChunkDataInputStream(pos)) {
                    if (in == null) continue;
                    tag = NbtIo.read(in);
                }
                if (!SeamWrites.applyToSaved(tag, writes.get(packed))) continue;
                RegionFileVersion version = RegionFileVersion.VERSION_DEFLATE;
                FramedChunkBuffer framed = new FramedChunkBuffer(version.getId());
                try (DataOutputStream stream = new DataOutputStream(version.wrap(framed))) {
                    NbtIo.write(tag, stream);
                }
                ((RegionFileInvoker) region).pregen$write(pos, framed.frame());
                merged++;
            }
        } finally {
            releaseFastHandle(storage, regionKey);
        }
        return merged;
    }

    /** Live progress (updated by the IO thread) and the completion of every region's task. */
    record PendingWrite(WriteResult progress, CompletableFuture<WriteResult> done) {}

    PendingWrite writeNew(List<EncodedChunk> chunks) {
        return write(chunks, false);
    }

    PendingWrite write(List<EncodedChunk> chunks, boolean overwrite) {
        Map<Long, List<EncodedChunk>> byRegion = new LinkedHashMap<>();
        for (EncodedChunk chunk : chunks) {
            long region = ChunkPos.asLong(chunk.pos().getRegionX(), chunk.pos().getRegionZ());
            byRegion.computeIfAbsent(region, key -> new ArrayList<>()).add(chunk);
        }
        WriteResult result = new WriteResult(chunks.size(), byRegion.size());
        CompletableFuture<WriteResult> chain = CompletableFuture.completedFuture(result);
        for (Map.Entry<Long, List<EncodedChunk>> region : byRegion.entrySet()) {
            List<EncodedChunk> regionChunks = region.getValue();
            for (int start = 0; start < regionChunks.size(); start += writeBatch) {
                List<EncodedChunk> batch = regionChunks.subList(start, Math.min(regionChunks.size(), start + writeBatch));
                boolean lastOfRegion = start + writeBatch >= regionChunks.size();
                chain = chain.thenCompose(ignored -> thread.<WriteResult>submit(() -> {
                    try {
                        writeBatch(region.getKey(), batch, lastOfRegion, result, overwrite);
                        return Either.left(result);
                    } catch (IOException | RuntimeException failure) {
                        return Either.right(failure);
                    }
                }));
            }
        }
        return new PendingWrite(result, chain);
    }

    /**
     * With sync-chunk-writes (the dedicated-server default) the server's cached RegionFile uses
     * DSYNC, so every chunk write and its header rewrite each wait for the disk. Instead the region
     * gets one handle without DSYNC that sits in the server's own cache, so the server and we share
     * it (a second, concurrently open handle would corrupt the header). Short tasks let the
     * server's loads and saves run between batches; after the region's last batch the handle is
     * closed, which forces it to disk once, and the server reopens the file with its own setting.
     */
    private void writeBatch(long regionKey, List<EncodedChunk> chunks, boolean lastOfRegion, WriteResult result,
                            boolean overwrite) throws IOException {
        long started = System.nanoTime();
        RegionFileStorageAccessor storage = thread.storage();
        int written = 0;
        int skipped = 0;
        try {
            RegionFile region = fastHandle(storage, regionKey);
            for (EncodedChunk chunk : chunks) {
                QueuedSave queued = thread.queued(chunk.pos());
                if (!overwrite) {
                    // Keep a chunk saved since the run's check only if it is fully generated (a
                    // player's); the server's saves of part-way generated chunks are replaced.
                    boolean keep = queued != null
                            ? queued.isFullyGenerated()
                            : region.hasChunk(chunk.pos()) && isFullyGenerated(region, chunk.pos());
                    if (keep) {
                        skipped++;
                        continue;
                    }
                }
                if (queued != null) {
                    // Its completion stays intact, but a stale queued save can no longer
                    // restore the old chunk after our raw region write.
                    queued.replaceWith(decode(chunk));
                }
                ((RegionFileInvoker) region).pregen$write(chunk.pos(), chunk.frame().duplicate());
                written++;
            }
        } finally {
            if (lastOfRegion) releaseFastHandle(storage, regionKey);
            result.written += written;
            result.skippedExisting += skipped;
            if (lastOfRegion) result.regionsDone++;
            result.ioNanos += System.nanoTime() - started;
        }
    }

    private RegionFile fastHandle(RegionFileStorageAccessor storage, long regionKey) throws IOException {
        Long2ObjectLinkedOpenHashMap<RegionFile> cache = storage.pregen$getRegionCache();
        RegionFile ours = fastHandles.get(regionKey);
        RegionFile cached = cache.get(regionKey);
        if (ours != null && cached == ours) {
            cache.getAndMoveToFirst(regionKey);
            return ours;
        }
        // First batch for this region, or the cache evicted (and closed) our handle in between.
        if (cached != null) {
            cache.remove(regionKey);
            cached.close();
        }
        if (cache.size() >= MAX_OPEN_REGIONS) cache.removeLast().close();
        Path folder = storage.pregen$getFolder();
        Files.createDirectories(folder);
        Path file = folder.resolve("r." + ChunkPos.getX(regionKey) + "." + ChunkPos.getZ(regionKey) + ".mca");
        ours = openRegionFile(storage, file, folder);
        cache.putAndMoveToFirst(regionKey, ours);
        fastHandles.put(regionKey, ours);
        return ours;
    }

    /**
     * new RegionFile(file, folder, false), as the storage opens its files. 1.20.5 put the storage's
     * RegionStorageInfo first in that constructor; there it is called through a method handle.
     */
    private static RegionFile openRegionFile(RegionFileStorageAccessor storage, Path file, Path folder) throws IOException {
        if (ModernRegionFile.OPEN == null) return new RegionFile(file, folder, false);
        try {
            return (RegionFile) ModernRegionFile.OPEN.invoke(ModernRegionFile.INFO.get(storage), file, folder, false);
        } catch (IOException | RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IOException(failure);
        }
    }

    /** RegionFile's constructor of 1.20.5 and later and RegionFileStorage's RegionStorageInfo; null before. */
    private static final class ModernRegionFile {
        static final MethodHandle OPEN;
        static final Field INFO;

        static {
            MethodHandle open = null;
            Field info = null;
            try {
                Class<?> infoType = Class.forName(FabricLoader.getInstance().getMappingResolver()
                        .mapClassName("intermediary", "net.minecraft.class_9240"), false, WorldChunkIO.class.getClassLoader());
                for (Field field : RegionFileStorage.class.getDeclaredFields()) {
                    if (field.getType() == infoType) info = field;
                }
                if (info == null) throw new NoSuchFieldException("RegionFileStorage has no RegionStorageInfo field");
                info.setAccessible(true);
                open = MethodHandles.publicLookup().findConstructor(RegionFile.class,
                        MethodType.methodType(void.class, infoType, Path.class, Path.class, boolean.class));
            } catch (ClassNotFoundException olderMinecraft) {
                info = null;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                throw new IllegalStateException("This Minecraft has RegionStorageInfo (1.20.5 or later) but not the"
                        + " RegionFile constructor of 1.20.5 and 1.20.6", failure);
            }
            OPEN = open;
            INFO = info;
        }
    }

    private void releaseFastHandle(RegionFileStorageAccessor storage, long regionKey) throws IOException {
        RegionFile ours = fastHandles.remove(regionKey);
        if (ours != null && storage.pregen$getRegionCache().get(regionKey) == ours) {
            storage.pregen$getRegionCache().remove(regionKey);
            ours.close();
        }
    }

    static CompoundTag decode(EncodedChunk chunk) throws IOException {
        ByteBuffer frame = chunk.frame().duplicate();
        frame.getInt();
        int compression = Byte.toUnsignedInt(frame.get());
        RegionFileVersion version = RegionFileVersion.fromId(compression);
        if (version == null) throw new IOException("Unknown chunk compression " + compression);
        byte[] bytes = new byte[frame.remaining()];
        frame.get(bytes);
        try (DataInputStream stream = new DataInputStream(version.wrap(new ByteArrayInputStream(bytes)))) {
            return NbtIo.read(stream);
        }
    }
}
