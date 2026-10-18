package com.example.pregen;

import com.ibm.asyncutil.util.Either;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.Long2ReferenceLinkedOpenHashMap;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

/**
 * Writing through C2ME's storage thread, without C2ME installed. C2ME replaces Minecraft's IOWorker
 * with a subclass whose own queue fields are null and whose saves wait in a cache on a thread of
 * its own. This builds the same shape (a worker holding a "backend" with executor, storage and
 * cache) and runs WorldChunkIO through C2meStorage on it:
 * 1. a world storage that is neither Minecraft's nor a C2ME this mod knows fails with the setting to change;
 * 2. tasks run on the storage thread, and the queued saves in its cache are read for their status;
 * 3. chunks are found, kept and written as with Minecraft's own IOWorker.
 * The real C2ME jar is exercised by the production runs in the release notes.
 */
public final class C2meStorageCheck {
    private C2meStorageCheck() {}

    /** C2ME's storage thread, reduced to the three members C2meStorage reads. */
    public static final class FakeBackend {
        public final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fake-c2me-storage");
            thread.setDaemon(true);
            return thread;
        });
        public final RegionFileStorage storage;
        public final Long2ReferenceLinkedOpenHashMap<CompletableFuture<Either<CompoundTag, byte[]>>> cache =
                new Long2ReferenceLinkedOpenHashMap<>();

        FakeBackend(RegionFileStorage storage) {
            this.storage = storage;
        }
    }

    /** C2ME's IOWorker replacement: a worker with a backend, whose own queue is unused. */
    static final class FakeC2meWorker extends IOWorker {
        final Object backend;
        final List<ChunkPos> stored = new CopyOnWriteArrayList<>();
        final List<CompoundTag> storedTags = new CopyOnWriteArrayList<>();

        FakeC2meWorker(Path folder, Object backend) {
            super(ChunkSaveCheck.storageInfo("pregen-c2me-worker"), folder, false);
            this.backend = backend;
        }

        @Override
        public CompletableFuture<Void> store(ChunkPos pos, CompoundTag tag) {
            stored.add(pos);
            storedTags.add(tag);
            return CompletableFuture.completedFuture(null);
        }
    }

    public static void run() throws Exception {
        Path outputRoot = Path.of("build", "c2me-storage-check").toAbsolutePath().normalize();
        Files.createDirectories(outputRoot);
        Path scratch = Files.createTempDirectory(outputRoot, "c2me-");
        try {
            checkUnknownStorage(Files.createDirectories(scratch.resolve("unknown")));
            checkStatusFromBytes();
            checkThroughStorageThread(Files.createDirectories(scratch.resolve("thread")));
            System.out.println("C2ME_STORAGE_CHECK PASS unknown_storage_names_setting=true runs_on_storage_thread=true"
                    + " queued_saves_read=true found=true kept_full=true replaced_partial=true written_readable=true");
        } finally {
            delete(scratch, outputRoot);
        }
    }

    private static void checkUnknownStorage(Path folder) throws Exception {
        try (IOWorker plain = new IOWorker(ChunkSaveCheck.storageInfo("pregen-c2me-unknown"), folder, false) {}) {
            // Not C2ME: no backend to find.
            try {
                C2meStorage.of(plain);
                throw new AssertionError("A worker with no C2ME backend must be refused");
            } catch (IllegalStateException refused) {
                require(refused.getMessage().contains("replaceImpl = false"),
                        "The refusal must name the C2ME setting: " + refused.getMessage());
            }
        }
    }

    private static byte[] nbtBytes(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(tag, out);
        }
        return bytes.toByteArray();
    }

    private static CompoundTag chunk(ChunkPos pos, String status) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("xPos", pos.x);
        tag.putInt("zPos", pos.z);
        if (status != null) tag.putString("Status", status);
        return tag;
    }

    /** What a save queued by C2ME may hold: a tag or uncompressed bytes, current or pre-1.18 layout. */
    private static void checkStatusFromBytes() throws IOException {
        require(WorldChunkIO.isFullyGenerated(nbtBytes(chunk(new ChunkPos(0, 0), "minecraft:full"))), "full bytes");
        require(!WorldChunkIO.isFullyGenerated(nbtBytes(chunk(new ChunkPos(0, 0), "minecraft:carvers"))), "carvers bytes");
        require(WorldChunkIO.isFullyGenerated(nbtBytes(chunk(new ChunkPos(0, 0), null))), "bytes without a status are generated");
        CompoundTag legacy = new CompoundTag();
        legacy.put("Level", chunk(new ChunkPos(0, 0), "carvers"));
        require(!WorldChunkIO.isFullyGenerated(nbtBytes(legacy)), "pre-1.18 layout, part-way");
        legacy.put("Level", chunk(new ChunkPos(0, 0), "full"));
        require(WorldChunkIO.isFullyGenerated(nbtBytes(legacy)), "pre-1.18 layout, full");
        require(WorldChunkIO.isFullyGenerated(new byte[]{1, 2, 3}), "unreadable bytes count as generated");
    }

    private static void checkThroughStorageThread(Path folder) throws Exception {
        ChunkPos regionFile = new ChunkPos(3, 4);
        ChunkPos queuedFull = new ChunkPos(5, 6);
        ChunkPos queuedPartial = new ChunkPos(7, 8);
        ChunkPos queuedBytes = new ChunkPos(9, 10);
        ChunkPos pendingEncode = new ChunkPos(11, 12);
        ChunkPos deleted = new ChunkPos(13, 14);
        ChunkPos fresh = new ChunkPos(15, 16);
        ChunkPos absent = new ChunkPos(17, 18);

        RegionFileStorage storage = regionStorage(folder);
        FakeBackend backend = new FakeBackend(storage);
        try (FakeC2meWorker worker = new FakeC2meWorker(folder.resolveSibling("unused-io"), backend)) {
            C2meStorage c2me = C2meStorage.of(worker);
            String thread = c2me.submit(() -> com.mojang.datafixers.util.Either.left(Thread.currentThread().getName())).get(10, TimeUnit.SECONDS);
            require("fake-c2me-storage".equals(thread), "Tasks must run on C2ME's storage thread, ran on " + thread);
            require(c2me.storage() == (Object) storage, "The region storage must be C2ME's own");

            // One chunk already in its region file, written through the storage thread itself.
            WorldChunkIO io = new WorldChunkIO(worker, c2me, 2);
            CompoundTag onDisk = chunk(regionFile, "minecraft:full");
            onDisk.putString("Marker", "on-disk");
            io.write(List.of(encoded(regionFile, onDisk)), true).done().get(30, TimeUnit.SECONDS);

            backend.executor.submit(() -> {
                backend.cache.put(queuedFull.toLong(), CompletableFuture.completedFuture(Either.ofLeft(chunk(queuedFull, "minecraft:full"))));
                backend.cache.put(queuedPartial.toLong(), CompletableFuture.completedFuture(Either.ofLeft(chunk(queuedPartial, "minecraft:structure_starts"))));
                try {
                    backend.cache.put(queuedBytes.toLong(), CompletableFuture.completedFuture(Either.ofRight(nbtBytes(chunk(queuedBytes, "minecraft:carvers")))));
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
                backend.cache.put(pendingEncode.toLong(), new CompletableFuture<>());
                backend.cache.put(deleted.toLong(), CompletableFuture.completedFuture(null));
            }).get(10, TimeUnit.SECONDS);

            c2me.submit(() -> {
                require(c2me.queued(queuedFull).isFullyGenerated(), "A queued full tag is generated");
                require(!c2me.queued(queuedPartial).isFullyGenerated(), "A queued part-way tag is not");
                require(!c2me.queued(queuedBytes).isFullyGenerated(), "Queued part-way bytes are not");
                require(c2me.queued(pendingEncode).isFullyGenerated(), "A save still being serialized counts as generated");
                require(!c2me.queued(deleted).isFullyGenerated(), "A queued deletion is not a generated chunk");
                require(c2me.queued(absent) == null && c2me.queued(fresh) == null, "Nothing queued, nothing found");
                return com.mojang.datafixers.util.Either.left(null);
            }).get(10, TimeUnit.SECONDS);

            LongArrayList all = LongArrayList.wrap(new long[]{regionFile.toLong(), queuedFull.toLong(), queuedPartial.toLong(),
                    queuedBytes.toLong(), pendingEncode.toLong(), deleted.toLong(), fresh.toLong(), absent.toLong()});
            LongSet found = io.findStored(all).get(10, TimeUnit.SECONDS);
            require(found.contains(regionFile.toLong()) && found.contains(queuedFull.toLong()) && found.contains(queuedPartial.toLong())
                            && found.contains(queuedBytes.toLong()) && found.contains(pendingEncode.toLong()),
                    "Chunks in the region file or queued must be found: " + found);
            require(!found.contains(fresh.toLong()) && !found.contains(absent.toLong()), "Missing chunks must not be found: " + found);

            WorldChunkIO.StoredChunks regions = io.findStoredInRegions(LongArrayList.wrap(new long[]{
                    ChunkPos.asLong(regionFile.getRegionX(), regionFile.getRegionZ())})).get(10, TimeUnit.SECONDS);
            require(WorldChunkIO.isStored(regions.full(), regionFile.toLong()), "The region file's chunk is fully generated");
            require(WorldChunkIO.isStored(regions.full(), queuedFull.toLong()), "A queued full save is fully generated");
            require(!WorldChunkIO.isStored(regions.full(), queuedPartial.toLong())
                            && WorldChunkIO.isStored(regions.present(), queuedPartial.toLong()),
                    "A queued part-way save is present but not full");

            // Chunks to write: one the server saved fully (kept), one saved part-way (replaced), one new.
            CompoundTag generatedFull = chunk(queuedFull, "minecraft:full");
            generatedFull.putString("Marker", "generated");
            CompoundTag generatedPartial = chunk(queuedPartial, "minecraft:full");
            generatedPartial.putString("Marker", "generated");
            CompoundTag generatedFresh = chunk(fresh, "minecraft:full");
            generatedFresh.putString("Marker", "generated");
            WorldChunkIO.WriteResult result = io.write(List.of(encoded(queuedFull, generatedFull),
                    encoded(queuedPartial, generatedPartial), encoded(fresh, generatedFresh)), false)
                    .done().get(30, TimeUnit.SECONDS);
            require(result.skippedExisting == 1, "The queued full chunk must be kept, kept " + result.skippedExisting);
            require(result.written == 2, "The part-way and the new chunk must be written, wrote " + result.written);
            require(worker.stored.equals(List.of(queuedPartial)),
                    "The queued part-way save must be replaced through C2ME, replaced " + worker.stored);
            require("generated".equals(worker.storedTags.get(0).getString("Marker")),
                    "The replacement must hold the generated chunk");

            // Overwriting (skip_existing_chunks=false) writes even over a queued full chunk.
            io.write(List.of(encoded(queuedFull, generatedFull)), true).done().get(30, TimeUnit.SECONDS);
            require(worker.stored.size() == 2 && worker.stored.get(1).equals(queuedFull),
                    "Overwriting must replace the queued full save as well: " + worker.stored);

            // Flushed on the storage thread before it closes the files.
            c2me.submit(() -> {
                try {
                    storage.flush();
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
                return com.mojang.datafixers.util.Either.left(null);
            }).get(10, TimeUnit.SECONDS);
        } finally {
            backend.executor.submit(() -> {
                try {
                    storage.close();
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }).get(10, TimeUnit.SECONDS);
            backend.executor.shutdown();
        }

        // What reached the region files reads back with Minecraft's own reader.
        try (IOWorker reader = new IOWorker(ChunkSaveCheck.storageInfo("pregen-c2me-reader"), folder, false) {}) {
            Optional<CompoundTag> a = reader.loadAsync(regionFile).get(10, TimeUnit.SECONDS);
            require(a.isPresent() && "on-disk".equals(a.get().getString("Marker")), "The first chunk reads back");
            Optional<CompoundTag> b = reader.loadAsync(fresh).get(10, TimeUnit.SECONDS);
            require(b.isPresent() && "generated".equals(b.get().getString("Marker")), "The new chunk reads back");
            require(reader.loadAsync(queuedFull).get(10, TimeUnit.SECONDS).isPresent(), "The overwritten chunk is in the file");
        }
    }

    /** This version's RegionFileStorage constructor is package-private. */
    private static RegionFileStorage regionStorage(Path folder) throws ReflectiveOperationException {
        var constructor = RegionFileStorage.class.getDeclaredConstructor(net.minecraft.world.level.chunk.storage.RegionStorageInfo.class, java.nio.file.Path.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(ChunkSaveCheck.storageInfo("pregen-c2me-backend"), folder, false);
    }

    private static WorldChunkIO.EncodedChunk encoded(ChunkPos pos, CompoundTag tag) throws IOException {
        FramedChunkBuffer buffer = new FramedChunkBuffer(RegionFileVersion.VERSION_DEFLATE.getId());
        try (DataOutputStream out = new DataOutputStream(RegionFileVersion.VERSION_DEFLATE.wrap(buffer))) {
            NbtIo.write(tag, out);
        }
        return new WorldChunkIO.EncodedChunk(pos, buffer.frame());
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
