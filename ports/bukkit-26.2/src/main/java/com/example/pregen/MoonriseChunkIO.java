package com.example.pregen;

import it.unimi.dsi.fastutil.longs.LongCollection;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

/**
 * A dimension's chunk storage on Paper, whose chunk system (Moonrise) reads and writes region files on
 * its own threads: chunks are handed to it as NBT, as Paper's own saves are, and a run's batches wait
 * until it has written them. The chunks an area already has are read from the region files directly,
 * read-only, when a run starts.
 */
final class MoonriseChunkIO implements ChunkStorage {
    private final ServerLevel level;
    private final Path regionFolder;
    /** Writes and edge merges in the order they were asked for, each finishing before the next. */
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PreGen-Moonrise-IO");
        thread.setDaemon(true);
        return thread;
    });

    MoonriseChunkIO(ServerLevel level) {
        this.level = level;
        this.regionFolder = MoonriseAccess.regionFolder(level);
    }

    @Override
    public boolean framesChunks() {
        return false;
    }

    @Override
    public Path dimensionFolder() {
        return regionFolder.getParent();
    }

    @Override
    public CompletableFuture<WorldChunkIO.StoredChunks> findStoredInRegions(LongCollection regionKeys) {
        return CompletableFuture.supplyAsync(() -> {
            Map<Long, long[]> full = new HashMap<>();
            Map<Long, long[]> present = new HashMap<>();
            for (long regionKey : regionKeys) {
                try {
                    long[][] bits = readStoredInRegion(regionKey);
                    if (bits != null) {
                        full.put(regionKey, bits[0]);
                        present.put(regionKey, bits[1]);
                    }
                } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            }
            return new WorldChunkIO.StoredChunks(full, present);
        }, writer);
    }

    /**
     * A region file's chunks, from its header and each chunk's status (see WorldChunkIO.StoredChunks),
     * or null when it has none. Read through a handle of its own, read-only: Paper keeps its own open,
     * and this only reads. A chunk stored outside the region file (an oversized one) counts as fully
     * generated, as does one that cannot be read.
     */
    private long[][] readStoredInRegion(long regionKey) throws IOException {
        int regionX = ChunkPos.getX(regionKey);
        int regionZ = ChunkPos.getZ(regionKey);
        Path file = regionFolder.resolve("r." + regionX + "." + regionZ + ".mca");
        if (!Files.exists(file)) return null;
        long[] fullBits = new long[16];
        long[] presentBits = new long[16];
        boolean any = false;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(4096);
            if (readFully(channel, header, 0) < 4096) return null;
            for (int localZ = 0; localZ < 32; localZ++) {
                for (int localX = 0; localX < 32; localX++) {
                    int entry = header.getInt((localX + localZ * 32) * 4);
                    if (entry == 0) continue;
                    int index = localX * 32 + localZ;
                    presentBits[index >>> 6] |= 1L << index;
                    any = true;
                    if (isFullyGenerated(channel, (long) (entry >>> 8) * 4096)) fullBits[index >>> 6] |= 1L << index;
                }
            }
        }
        return any ? new long[][]{fullBits, presentBits} : null;
    }

    private static boolean isFullyGenerated(FileChannel channel, long offset) {
        try {
            ByteBuffer frame = ByteBuffer.allocate(5);
            if (readFully(channel, frame, offset) < 5) return true;
            int length = frame.getInt(0);
            int compression = Byte.toUnsignedInt(frame.get(4));
            if ((compression & 128) != 0 || length <= 1) return true;
            RegionFileVersion version = RegionFileVersion.fromId(compression);
            if (version == null) return true;
            ByteBuffer payload = ByteBuffer.allocate(length - 1);
            if (readFully(channel, payload, offset + 5) < length - 1) return true;
            byte[] compressed = payload.array();
            return WorldChunkIO.isFullyGenerated(() -> new DataInputStream(version.wrap(new ByteArrayInputStream(compressed))));
        } catch (IOException | RuntimeException unreadable) {
            return true;
        }
    }

    private static int readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        int total = 0;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + total);
            if (read < 0) break;
            total += read;
        }
        return total;
    }

    @Override
    public CompletableFuture<Optional<CompoundTag>> load(long chunk) {
        return MoonriseAccess.load(level, ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
    }

    @Override
    public WorldChunkIO.PendingWrite write(List<WorldChunkIO.EncodedChunk> chunks, boolean overwrite) {
        Map<Long, List<WorldChunkIO.EncodedChunk>> byRegion = new LinkedHashMap<>();
        for (WorldChunkIO.EncodedChunk chunk : chunks) {
            byRegion.computeIfAbsent(ChunkPos.pack(chunk.pos().getRegionX(), chunk.pos().getRegionZ()),
                    key -> new ArrayList<>()).add(chunk);
        }
        WorldChunkIO.WriteResult result = new WorldChunkIO.WriteResult(chunks.size(), byRegion.size());
        CompletableFuture<WorldChunkIO.WriteResult> done = CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime();
            for (List<WorldChunkIO.EncodedChunk> region : byRegion.values()) {
                // Keep a chunk saved since the run's check only if it is fully generated (a player's);
                // the server's saves of part-way generated chunks are replaced.
                List<CompletableFuture<Optional<CompoundTag>>> stored = new ArrayList<>(region.size());
                for (WorldChunkIO.EncodedChunk chunk : region) {
                    stored.add(overwrite ? null : MoonriseAccess.load(level, chunk.pos().x(), chunk.pos().z()));
                }
                for (int i = 0; i < region.size(); i++) {
                    WorldChunkIO.EncodedChunk chunk = region.get(i);
                    if (!overwrite) {
                        CompoundTag existing = stored.get(i).join().orElse(null);
                        if (existing != null && WorldChunkIO.isFullyGenerated(existing)) {
                            result.skippedExisting++;
                            continue;
                        }
                    }
                    MoonriseAccess.scheduleSave(level, chunk.pos().x(), chunk.pos().z(), chunk.tag());
                    result.written++;
                }
                result.regionsDone++;
            }
            MoonriseAccess.flush(level);
            result.ioNanos = System.nanoTime() - started;
            return result;
        }, writer);
        return new WorldChunkIO.PendingWrite(result, done);
    }

    @Override
    public CompletableFuture<Integer> mergeIntoSaved(Map<Long, List<SeamWrites.Write>> writes) {
        return CompletableFuture.supplyAsync(() -> {
            int merged = 0;
            for (Map.Entry<Long, List<SeamWrites.Write>> entry : writes.entrySet()) {
                int chunkX = ChunkPos.getX(entry.getKey());
                int chunkZ = ChunkPos.getZ(entry.getKey());
                CompoundTag tag = MoonriseAccess.load(level, chunkX, chunkZ).join().orElse(null);
                if (tag == null || !SeamWrites.applyToSaved(tag, entry.getValue())) continue;
                MoonriseAccess.scheduleSave(level, chunkX, chunkZ, tag);
                merged++;
            }
            MoonriseAccess.flush(level);
            return merged;
        }, writer);
    }
}
