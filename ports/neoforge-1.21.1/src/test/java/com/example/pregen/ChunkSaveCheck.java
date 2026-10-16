package com.example.pregen;

import com.example.pregen.mixin.RegionFileInvoker;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

/**
 * Saving path checks in disposable folders under build/; never touches a Minecraft world.
 * 1. Worker-side framing written through RegionFile#write is byte-identical to vanilla's stream.
 * 2. WorldChunkIO, running on a real IOWorker, finds stored and pending chunks, never overwrites
 *    them, does not create region files while only checking, and stores new chunks readably.
 */
public final class ChunkSaveCheck {
    /** Region storage identity for the scratch files these checks write. */
    static net.minecraft.world.level.chunk.storage.RegionStorageInfo storageInfo(String type) {
        return new net.minecraft.world.level.chunk.storage.RegionStorageInfo("pregen-checks",
                net.minecraft.world.level.Level.OVERWORLD, type);
    }

    private ChunkSaveCheck() {}

    public static void run() throws Exception {
        Path outputRoot = Path.of("build", "chunk-save-check").toAbsolutePath().normalize();
        Files.createDirectories(outputRoot);
        Path scratch = Files.createTempDirectory(outputRoot, "regions-");
        try {
            int framed = checkFraming(scratch);
            checkWorldChunkIO(Files.createDirectories(scratch.resolve("io")));
            if (Boolean.getBoolean("pregen.benchmark.saveSpeed")) measureSaveSpeed(scratch);
            System.out.printf("CHUNK_SAVE_CHECK PASS framing_chunks=%d bytes_identical=true existing_kept=true"
                    + " pending_detected=true no_region_created_on_check=true roundtrip=true%n", framed);
        } finally {
            delete(scratch, outputRoot);
        }
    }

    private static int checkFraming(Path scratch) throws IOException {
        Path vanillaFolder = Files.createDirectories(scratch.resolve("vanilla"));
        Path framedFolder = Files.createDirectories(scratch.resolve("framed"));
        Path vanillaFile = vanillaFolder.resolve("r.0.0.mca");
        Path framedFile = framedFolder.resolve("r.0.0.mca");

        // Small, sector-spanning and external-file (>1 MiB) payloads, in a non-sequential order.
        // (i * 7) % 32 repeats every 32 steps, so de-duplicate or later writes replace earlier ones.
        Set<ChunkPos> unique = new LinkedHashSet<>();
        for (int i = 0; i < 40; i++) unique.add(new ChunkPos((i * 7) % 32, (i * 13) % 32));
        ChunkPos external = new ChunkPos(31, 31);
        unique.add(external);
        List<ChunkPos> positions = new ArrayList<>(unique);

        try (RegionFile vanilla = new RegionFile(storageInfo("pregen-chunk-save-check"), vanillaFile, vanillaFolder, false);
             RegionFile framed = new RegionFile(storageInfo("pregen-chunk-save-check"), framedFile, framedFolder, false)) {
            int index = 0;
            for (ChunkPos pos : positions) {
                CompoundTag tag = sampleChunk(pos, pos.equals(external) ? 1_200_000 : 2_000 + index * 997);
                try (DataOutputStream out = vanilla.getChunkDataOutputStream(pos)) {
                    NbtIo.write(tag, out);
                }
                ((RegionFileInvoker) framed).pregen$write(pos, frame(tag));
                index++;
            }
        }

        byte[] a = Files.readAllBytes(vanillaFile);
        byte[] b = Files.readAllBytes(framedFile);
        require(a.length == b.length, "Region sizes differ: " + a.length + " vs " + b.length);
        // Bytes 4096..8191 are per-chunk write timestamps in seconds; everything else is deterministic.
        for (int i = 0; i < a.length; i++) {
            if (i >= 4096 && i < 8192) continue;
            require(a[i] == b[i], "Region byte differs at offset " + i);
        }
        require(Files.mismatch(vanillaFolder.resolve("c.31.31.mcc"), framedFolder.resolve("c.31.31.mcc")) == -1,
                "External chunk file contents differ.");

        try (RegionFile reader = new RegionFile(storageInfo("pregen-chunk-save-check"), framedFile, framedFolder, false)) {
            int index = 0;
            for (ChunkPos pos : positions) {
                CompoundTag expected = sampleChunk(pos, pos.equals(external) ? 1_200_000 : 2_000 + index * 997);
                try (DataInputStream in = reader.getChunkDataInputStream(pos)) {
                    require(in != null, "Chunk missing after framed write: " + pos);
                    require(expected.equals(NbtIo.read(in)), "NBT mismatch after framed write: " + pos);
                }
                index++;
            }
        }
        return positions.size();
    }

    private static void checkWorldChunkIO(Path folder) throws Exception {
        ChunkPos stored = new ChunkPos(3, 4);
        ChunkPos pending = new ChunkPos(5, 6);
        ChunkPos fresh = new ChunkPos(7, 8);
        ChunkPos freshOtherRegion = new ChunkPos(-40, 70);
        ChunkPos untouchedRegion = new ChunkPos(1_000, 1_000);
        CompoundTag storedTag = sampleChunk(stored, 3_000);
        CompoundTag pendingTag = sampleChunk(pending, 4_000);

        // sync=true, like a dedicated server with sync-chunk-writes: its cached files use DSYNC.
        try (IOWorker worker = new IOWorker(storageInfo("pregen-chunk-save-check"), folder, true) {}) {
            WorldChunkIO io = new WorldChunkIO(worker);
            worker.store(stored, storedTag).join();
            worker.synchronize(true).join();
            // Queued after the existence check below on the same IO mailbox, so it is either still
            // pending or already written when the check runs; both must count as existing.
            worker.store(pending, pendingTag);

            LongArrayList all = LongArrayList.wrap(new long[]{stored.toLong(), pending.toLong(), fresh.toLong(),
                    freshOtherRegion.toLong(), untouchedRegion.toLong()});
            LongSet found = io.findStored(all).join();
            require(found.contains(stored.toLong()), "A stored chunk must be found.");
            require(found.contains(pending.toLong()), "A chunk queued for saving must be found.");
            require(found.size() == 2, "Only the stored and pending chunks exist, found " + found.size());
            require(Files.notExists(folder.resolve("r.31.31.mca")),
                    "Checking existence must not create region files.");

            List<WorldChunkIO.EncodedChunk> batch = List.of(
                    new WorldChunkIO.EncodedChunk(stored, frame(sampleChunk(stored, 9_999))),
                    new WorldChunkIO.EncodedChunk(pending, frame(sampleChunk(pending, 9_999))),
                    new WorldChunkIO.EncodedChunk(fresh, frame(sampleChunk(fresh, 5_000))),
                    new WorldChunkIO.EncodedChunk(freshOtherRegion, frame(sampleChunk(freshOtherRegion, 6_000))));
            WorldChunkIO.PendingWrite pendingWrite = io.writeNew(batch);
            WorldChunkIO.WriteResult result = pendingWrite.done().join();
            require(pendingWrite.progress() == result && result.regionsDone == result.regions && result.regions == 2,
                    "Progress must describe both region files");
            require(result.written == 2, "Exactly the two new chunks must be written, wrote " + result.written);
            require(result.skippedExisting == 2, "Both existing chunks must be skipped, skipped " + result.skippedExisting);

            worker.synchronize(true).join();
            require(storedTag.equals(worker.loadAsync(stored).join().orElseThrow()), "A stored chunk was overwritten.");
            require(pendingTag.equals(worker.loadAsync(pending).join().orElseThrow()), "A pending chunk was overwritten.");
            require(sampleChunk(fresh, 5_000).equals(worker.loadAsync(fresh).join().orElseThrow()),
                    "A new chunk must read back through the server's IO worker.");
            require(sampleChunk(freshOtherRegion, 6_000).equals(worker.loadAsync(freshOtherRegion).join().orElseThrow()),
                    "A new chunk in a new region file must read back.");
            require(io.findStored(all).join().size() == 4, "All four written or stored chunks must now exist.");

            // The server keeps saving into the same region after ours was rewritten: it must reopen
            // the file with the updated header, so nothing either side wrote is lost.
            ChunkPos later = new ChunkPos(9, 10);
            CompoundTag laterTag = sampleChunk(later, 7_000);
            worker.store(later, laterTag).join();
            worker.synchronize(true).join();
            require(laterTag.equals(worker.loadAsync(later).join().orElseThrow()), "A later server save must be readable.");
            require(storedTag.equals(worker.loadAsync(stored).join().orElseThrow()), "Earlier data must survive later saves.");
            require(sampleChunk(fresh, 5_000).equals(worker.loadAsync(fresh).join().orElseThrow()),
                    "Our chunk must survive the server's later saves.");

            CompoundTag forcedStored = sampleChunk(stored, 8_000);
            CompoundTag forcedPending = sampleChunk(pending, 8_001);
            worker.store(pending, sampleChunk(pending, 8_999));
            WorldChunkIO.WriteResult forced = io.write(List.of(
                    new WorldChunkIO.EncodedChunk(stored, frame(forcedStored)),
                    new WorldChunkIO.EncodedChunk(pending, frame(forcedPending))), true).done().join();
            worker.synchronize(true).join();
            require(forced.written == 2 && forced.skippedExisting == 0,
                    "Force regeneration must overwrite stored and pending chunks");
            require(forcedStored.equals(worker.loadAsync(stored).join().orElseThrow()),
                    "Stored chunk must contain forced replacement data");
            require(forcedPending.equals(worker.loadAsync(pending).join().orElseThrow()),
                    "An old pending save must not restore the prior chunk after forced writing");

            // One-chunk IO tasks: the server's own saves into the same region land between ours,
            // through the shared non-DSYNC handle, and nothing either side writes is lost.
            WorldChunkIO small = new WorldChunkIO(worker, 1);
            List<WorldChunkIO.EncodedChunk> many = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                ChunkPos pos = new ChunkPos(12 + i % 6, 20 + i / 6);
                many.add(new WorldChunkIO.EncodedChunk(pos, frame(sampleChunk(pos, 3_000 + i))));
            }
            WorldChunkIO.PendingWrite interleaved = small.write(many, true);
            List<ChunkPos> serverSaves = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                ChunkPos pos = new ChunkPos(25, 20 + i);
                serverSaves.add(pos);
                worker.store(pos, sampleChunk(pos, 6_000 + i));
            }
            WorldChunkIO.WriteResult interleavedResult = interleaved.done().join();
            worker.synchronize(true).join();
            require(interleavedResult.written == 24 && interleavedResult.regionsDone == 1,
                    "All one-chunk batches must be written and the region completed once");
            for (int i = 0; i < many.size(); i++) {
                ChunkPos pos = many.get(i).pos();
                require(sampleChunk(pos, 3_000 + i).equals(worker.loadAsync(pos).join().orElseThrow()),
                        "Our batched chunk must read back: " + pos);
            }
            for (int i = 0; i < serverSaves.size(); i++) {
                ChunkPos pos = serverSaves.get(i);
                require(sampleChunk(pos, 6_000 + i).equals(worker.loadAsync(pos).join().orElseThrow()),
                        "A server save made between our batches must read back: " + pos);
            }
        }
    }

    /**
     * Same chunks saved by a sync=true IOWorker (the dedicated-server default, DSYNC per write)
     * and by WorldChunkIO into an equally cached region. Compression is excluded from the
     * WorldChunkIO time because the generator compresses on its workers beforehand.
     */
    private static void measureSaveSpeed(Path scratch) throws Exception {
        int count = 1024;
        List<CompoundTag> tags = new ArrayList<>();
        List<WorldChunkIO.EncodedChunk> frames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ChunkPos pos = new ChunkPos(i % 32, i / 32);
            CompoundTag tag = sampleChunk(pos, 9_000);
            tags.add(tag);
            frames.add(new WorldChunkIO.EncodedChunk(pos, frame(tag)));
        }
        Path vanillaFolder = Files.createDirectories(scratch.resolve("speed-vanilla"));
        long vanillaNanos;
        try (IOWorker worker = new IOWorker(storageInfo("pregen-speed-vanilla"), vanillaFolder, true) {}) {
            long started = System.nanoTime();
            for (int i = 0; i < count; i++) worker.store(frames.get(i).pos(), tags.get(i));
            worker.synchronize(true).join();
            vanillaNanos = System.nanoTime() - started;
        }
        Path oursFolder = Files.createDirectories(scratch.resolve("speed-ours"));
        long oursNanos;
        try (IOWorker worker = new IOWorker(storageInfo("pregen-speed-ours"), oursFolder, true) {}) {
            // Make the server hold the region open with DSYNC first, as on a live server.
            worker.store(new ChunkPos(0, 31), sampleChunk(new ChunkPos(0, 31), 1_000)).join();
            List<WorldChunkIO.EncodedChunk> rest = frames.subList(0, count - 32);
            long started = System.nanoTime();
            new WorldChunkIO(worker).writeNew(rest).done().join();
            oursNanos = System.nanoTime() - started;
        }
        System.out.printf(java.util.Locale.ROOT,
                "CHUNK_SAVE_SPEED chunks=%d server_sync_store=%.2fs world_chunk_io=%.2fs (%d chunks)%n",
                count, vanillaNanos / 1e9, oursNanos / 1e9, count - 32);
    }

    private static java.nio.ByteBuffer frame(CompoundTag tag) throws IOException {
        FramedChunkBuffer buffer = new FramedChunkBuffer(RegionFileVersion.VERSION_DEFLATE.getId());
        try (DataOutputStream out = new DataOutputStream(RegionFileVersion.VERSION_DEFLATE.wrap(buffer))) {
            NbtIo.write(tag, out);
        }
        return buffer.frame();
    }

    private static CompoundTag sampleChunk(ChunkPos pos, int payloadBytes) {
        // Incompressible filler keeps deflated size close to payloadBytes.
        byte[] filler = new byte[payloadBytes];
        new Random(pos.toLong() ^ payloadBytes).nextBytes(filler);
        CompoundTag tag = new CompoundTag();
        tag.putInt("xPos", pos.x);
        tag.putInt("zPos", pos.z);
        tag.putString("Status", "minecraft:full");
        tag.putByteArray("Filler", filler);
        return tag;
    }

    private static void delete(Path scratch, Path outputRoot) throws IOException {
        Path absolute = scratch.toAbsolutePath().normalize();
        require(absolute.startsWith(outputRoot) && !absolute.equals(outputRoot),
                "Temporary cleanup must remain inside its test output folder.");
        try (Stream<Path> paths = Files.walk(absolute)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
