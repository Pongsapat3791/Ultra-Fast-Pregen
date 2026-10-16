package com.example.pregen;

import com.example.pregen.mixin.ChunkMapAccessor;
import com.example.pregen.mixin.ChunkStorageAccessor;
import com.example.pregen.mixin.IOWorkerAccessor;
import com.example.pregen.mixin.RegionFileInvoker;
import com.example.pregen.mixin.RegionFileStorageAccessor;
import com.example.pregen.mixin.PendingStoreAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import java.io.DataOutputStream;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.nbt.NbtIo;

/**
 * Chunk existence checks and writes that go through the server's own region storage.
 *
 * The server caches open RegionFile objects, each with its own in-memory sector table and
 * header. Writing the same .mca through a second, concurrently open RegionFile lets whichever
 * writes last erase the other's chunk offsets. Every task here therefore runs on the
 * dimension's IO worker thread, which is the only thread that touches its region files.
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

    private final IOWorkerAccessor worker;
    private final int writeBatch;
    /** Region files we opened without DSYNC; touched only on the IO thread. */
    private final Map<Long, RegionFile> fastHandles = new HashMap<>();

    WorldChunkIO(IOWorker worker) {
        this(worker, WRITE_BATCH);
    }

    WorldChunkIO(IOWorker worker, int writeBatch) {
        this.worker = (IOWorkerAccessor) worker;
        this.writeBatch = writeBatch;
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
            chain = chain.thenCompose(ignored -> worker.<Void>pregen$submitTask(() -> {
                try {
                    long[][] bits = collectStoredInRegion(regionKey);
                    if (bits != null) {
                        full.put(regionKey, bits[0]);
                        present.put(regionKey, bits[1]);
                    }
                    return null;
                } catch (IOException | RuntimeException failure) {
                    throw failure instanceof IOException io ? new java.io.UncheckedIOException(io) : (RuntimeException) failure;
                }
            }));
        }
        return chain.thenApply(ignored -> new StoredChunks(full, present));
    }

    /** A stored chunk's data (including a queued save), read on the IO thread. */
    CompletableFuture<java.util.Optional<CompoundTag>> load(long chunk) {
        return ((IOWorker) (Object) worker).loadAsync(ChunkPos.unpack(chunk));
    }

    private long[][] collectStoredInRegion(long regionKey) throws IOException {
        Map<ChunkPos, ?> pending = worker.pregen$getPendingWrites();
        RegionFileStorageAccessor storage = (RegionFileStorageAccessor) (Object) worker.pregen$getStorage();
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
                Object queued = pending.get(pos);
                boolean stored = queued != null || (region != null && region.hasChunk(pos));
                if (!stored) continue;
                int index = x * 32 + z;
                presentBits[index >>> 6] |= 1L << index;
                any = true;
                boolean full = queued != null
                        ? isFullyGenerated(((PendingStoreAccessor) queued).pregen$getData())
                        : isFullyGenerated(region, pos);
                if (full) fullBits[index >>> 6] |= 1L << index;
            }
        }
        return any ? new long[][]{fullBits, presentBits} : null;
    }

    /** A queued chunk save's status; a missing or unknown status counts as generated. */
    static boolean isFullyGenerated(CompoundTag data) {
        if (data == null) return false;
        if (data.contains("Status")) return isFullStatus(data.getStringOr("Status", ""));
        CompoundTag level = data.getCompoundOrEmpty("Level");
        return !level.contains("Status") || isFullStatus(level.getStringOr("Status", ""));
    }

    /**
     * Reads only a stored chunk's status (it comes first in the NBT, so little is decompressed).
     * Chunks from before 1.18 keep it under Level. Unreadable or unknown counts as generated.
     */
    static boolean isFullyGenerated(RegionFile region, ChunkPos pos) {
        try {
            String status = readStatus(region, pos, new FieldSelector(StringTag.TYPE, "Status"), false);
            if (status == null) status = readStatus(region, pos, new FieldSelector("Level", StringTag.TYPE, "Status"), true);
            return status == null || isFullStatus(status);
        } catch (IOException | RuntimeException failure) {
            return true;
        }
    }

    private static String readStatus(RegionFile region, ChunkPos pos, FieldSelector selector, boolean nested) throws IOException {
        try (DataInputStream in = region.getChunkDataInputStream(pos)) {
            if (in == null) return null;
            CollectFields fields = new CollectFields(selector);
            NbtIo.parse(in, fields, NbtAccounter.unlimitedHeap());
            if (!(fields.getResult() instanceof CompoundTag tag)) return null;
            CompoundTag holder = nested ? tag.getCompoundOrEmpty("Level") : tag;
            return holder.contains("Status") ? holder.getStringOr("Status", "") : null;
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
        long[] bits = stored.get(ChunkPos.pack(x >> 5, z >> 5));
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
                if (holder != null && isFull(holder.getLatestChunk())) held.add(pos);
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
        return worker.pregen$submitTask(() -> {
            try {
                return collectStored(positions);
            } catch (IOException | RuntimeException failure) {
                throw failure instanceof IOException io ? new java.io.UncheckedIOException(io) : (RuntimeException) failure;
            }
        });
    }

    private LongSet collectStored(LongCollection positions) throws IOException {
        Map<ChunkPos, ?> pending = worker.pregen$getPendingWrites();
        RegionFileStorageAccessor storage = (RegionFileStorageAccessor) (Object) worker.pregen$getStorage();
        Path folder = storage.pregen$getFolder();
        Map<Long, Boolean> regionExists = new HashMap<>();
        LongSet stored = new LongOpenHashSet();
        for (long packed : positions) {
            ChunkPos pos = ChunkPos.unpack(packed);
            if (pending.containsKey(pos)) {
                stored.add(packed);
                continue;
            }
            // Avoid getRegionFile here: it creates missing region files.
            boolean exists = regionExists.computeIfAbsent(ChunkPos.pack(pos.getRegionX(), pos.getRegionZ()),
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
            byRegion.computeIfAbsent(ChunkPos.pack(ChunkPos.getX(chunk) >> 5, ChunkPos.getZ(chunk) >> 5),
                    key -> new ArrayList<>()).add(chunk);
        }
        int[] merged = new int[1];
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Map.Entry<Long, List<Long>> region : byRegion.entrySet()) {
            chain = chain.thenCompose(ignored -> worker.<Void>pregen$submitTask(() -> {
                try {
                    merged[0] += mergeRegion(region.getKey(), region.getValue(), writes);
                    return null;
                } catch (IOException | RuntimeException failure) {
                    throw failure instanceof IOException io ? new java.io.UncheckedIOException(io) : (RuntimeException) failure;
                }
            }));
        }
        return chain.thenApply(ignored -> merged[0]);
    }

    private int mergeRegion(long regionKey, List<Long> chunks, Map<Long, List<SeamWrites.Write>> writes) throws IOException {
        Map<ChunkPos, ?> pending = worker.pregen$getPendingWrites();
        RegionFileStorageAccessor storage = (RegionFileStorageAccessor) (Object) worker.pregen$getStorage();
        int merged = 0;
        try {
            RegionFile region = fastHandle(storage, regionKey);
            for (long packed : chunks) {
                ChunkPos pos = ChunkPos.unpack(packed);
                if (pending.containsKey(pos)) continue;
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
            long region = ChunkPos.pack(chunk.pos().getRegionX(), chunk.pos().getRegionZ());
            byRegion.computeIfAbsent(region, key -> new ArrayList<>()).add(chunk);
        }
        WriteResult result = new WriteResult(chunks.size(), byRegion.size());
        CompletableFuture<WriteResult> chain = CompletableFuture.completedFuture(result);
        for (Map.Entry<Long, List<EncodedChunk>> region : byRegion.entrySet()) {
            List<EncodedChunk> regionChunks = region.getValue();
            for (int start = 0; start < regionChunks.size(); start += writeBatch) {
                List<EncodedChunk> batch = regionChunks.subList(start, Math.min(regionChunks.size(), start + writeBatch));
                boolean lastOfRegion = start + writeBatch >= regionChunks.size();
                chain = chain.thenCompose(ignored -> worker.<WriteResult>pregen$submitTask(() -> {
                    try {
                        writeBatch(region.getKey(), batch, lastOfRegion, result, overwrite);
                        return result;
                    } catch (IOException | RuntimeException failure) {
                        throw failure instanceof IOException io ? new java.io.UncheckedIOException(io) : (RuntimeException) failure;
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
        Map<ChunkPos, ?> pending = worker.pregen$getPendingWrites();
        RegionFileStorageAccessor storage = (RegionFileStorageAccessor) (Object) worker.pregen$getStorage();
        int written = 0;
        int skipped = 0;
        try {
            RegionFile region = fastHandle(storage, regionKey);
            for (EncodedChunk chunk : chunks) {
                Object queued = pending.get(chunk.pos());
                if (!overwrite) {
                    // Keep a chunk saved since the run's check only if it is fully generated (a
                    // player's); the server's saves of part-way generated chunks are replaced.
                    boolean keep = queued != null
                            ? isFullyGenerated(((PendingStoreAccessor) queued).pregen$getData())
                            : region.hasChunk(chunk.pos()) && isFullyGenerated(region, chunk.pos());
                    if (keep) {
                        skipped++;
                        continue;
                    }
                }
                if (queued != null) {
                    // Its completion stays intact, but a stale queued save can no longer
                    // restore the old chunk after our raw region write.
                    ((PendingStoreAccessor) queued).pregen$setData(decode(chunk));
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
        ours = new RegionFile(((RegionFileStorage) (Object) storage).info(), file, folder, false);
        cache.putAndMoveToFirst(regionKey, ours);
        fastHandles.put(regionKey, ours);
        return ours;
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
