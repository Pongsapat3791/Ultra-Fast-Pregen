package com.example.pregen;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The chunks this mod has generated and saved in one dimension, and the settings of its last
 * run. The whole record is held in RAM (one bit per chunk, grouped by region file) so the
 * per-tile lookups never touch the disk; it is written back to {@code <dimension>/data/} after
 * every saved tile. Fully generated regions are stored as one key, which keeps the file small.
 */
final class PreGenSaveFile {
    static final String FILE_NAME = "ultrafastpregen.dat";
    /** The name before the mod was renamed (1.20.1-11 and earlier); taken over on first load. */
    static final String LEGACY_FILE_NAME = "fastpregen.dat";
    private static final int VERSION = 1;
    private static final int REGION_WORDS = 32 * 32 / 64;

    private final Path file;
    private final long seed;
    /** Region key -> one bit per chunk of that region. */
    private final Long2ObjectMap<long[]> regions = new Long2ObjectOpenHashMap<>();
    private final boolean seedChanged;
    private boolean hasLastRun;
    private int lastWorldK;
    private int lastCenterX;
    private int lastCenterZ;
    private boolean lastCompleted;

    private PreGenSaveFile(Path file, long seed, boolean seedChanged) {
        this.file = file;
        this.seed = seed;
        this.seedChanged = seedChanged;
    }

    static Path fileFor(ServerLevel level) {
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        return DimensionType.getStorageFolder(level.dimension(), root).resolve("data").resolve(FILE_NAME).normalize();
    }

    static PreGenSaveFile load(ServerLevel level) throws IOException {
        Path file = fileFor(level);
        Path legacy = file.resolveSibling(LEGACY_FILE_NAME);
        if (Files.notExists(file) && Files.exists(legacy)) Files.move(legacy, file);
        return load(file, level.getSeed());
    }

    /** A record made for another world seed describes other terrain, so it starts empty. */
    static PreGenSaveFile load(Path file, long seed) throws IOException {
        if (!Files.exists(file)) return new PreGenSaveFile(file, seed, false);
        CompoundTag tag;
        try (InputStream in = Files.newInputStream(file)) {
            tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
        if (tag.getLong("seed") != seed) return new PreGenSaveFile(file, seed, true);
        PreGenSaveFile record = new PreGenSaveFile(file, seed, false);
        for (long region : tag.getLongArray("full")) {
            long[] bits = new long[REGION_WORDS];
            Arrays.fill(bits, -1L);
            record.regions.put(region, bits);
        }
        ListTag partial = tag.getList("partial", Tag.TAG_COMPOUND);
        for (int i = 0; i < partial.size(); i++) {
            CompoundTag entry = partial.getCompound(i);
            long[] bits = entry.getLongArray("chunks");
            if (bits.length == REGION_WORDS) record.regions.put(entry.getLong("region"), bits);
        }
        if (tag.contains("last_run", Tag.TAG_COMPOUND)) {
            CompoundTag last = tag.getCompound("last_run");
            record.hasLastRun = true;
            record.lastWorldK = last.getInt("world_k");
            record.lastCenterX = last.getInt("center_x");
            record.lastCenterZ = last.getInt("center_z");
            record.lastCompleted = last.getBoolean("completed");
        }
        return record;
    }

    static boolean delete(ServerLevel level) throws IOException {
        Path file = fileFor(level);
        boolean legacy = Files.deleteIfExists(file.resolveSibling(LEGACY_FILE_NAME));
        return Files.deleteIfExists(file) | legacy;
    }

    Path file() {
        return file;
    }

    /** True if the file on disk belonged to another seed and was ignored. */
    boolean seedChanged() {
        return seedChanged;
    }

    synchronized boolean isDone(int chunkX, int chunkZ) {
        long[] bits = regions.get(ChunkPos.asLong(chunkX >> 5, chunkZ >> 5));
        int bit = bitIndex(chunkX, chunkZ);
        return bits != null && (bits[bit >>> 6] & 1L << bit) != 0;
    }

    /** The given chunk positions (packed ChunkPos longs) that were already generated. */
    synchronized LongSet doneAmong(long[] positions) {
        LongSet done = new LongOpenHashSet();
        for (long pos : positions) {
            if (isDone(ChunkPos.getX(pos), ChunkPos.getZ(pos))) done.add(pos);
        }
        return done;
    }

    synchronized void markDone(Iterable<ChunkPos> positions) {
        for (ChunkPos pos : positions) {
            long[] bits = regions.computeIfAbsent(ChunkPos.asLong(pos.x >> 5, pos.z >> 5), key -> new long[REGION_WORDS]);
            int bit = bitIndex(pos.x, pos.z);
            bits[bit >>> 6] |= 1L << bit;
        }
    }

    synchronized long doneChunks() {
        long count = 0;
        for (long[] bits : regions.values()) {
            for (long word : bits) count += Long.bitCount(word);
        }
        return count;
    }

    synchronized void setLastRun(int worldK, int centerX, int centerZ, boolean completed) {
        hasLastRun = true;
        lastWorldK = worldK;
        lastCenterX = centerX;
        lastCenterZ = centerZ;
        lastCompleted = completed;
    }

    synchronized boolean hasLastRun() {
        return hasLastRun;
    }

    synchronized int lastWorldK() {
        return lastWorldK;
    }

    synchronized int lastCenterX() {
        return lastCenterX;
    }

    synchronized int lastCenterZ() {
        return lastCenterZ;
    }

    synchronized boolean lastCompleted() {
        return lastCompleted;
    }

    /** Writes a temporary file first, so a crash mid-save keeps the previous record intact. */
    synchronized void save() throws IOException {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", VERSION);
        tag.putLong("seed", seed);
        LongOpenHashSet full = new LongOpenHashSet();
        ListTag partial = new ListTag();
        for (Long2ObjectMap.Entry<long[]> entry : regions.long2ObjectEntrySet()) {
            long[] bits = entry.getValue();
            if (Arrays.stream(bits).allMatch(word -> word == -1L)) {
                full.add(entry.getLongKey());
            } else {
                CompoundTag region = new CompoundTag();
                region.putLong("region", entry.getLongKey());
                region.put("chunks", new LongArrayTag(bits.clone()));
                partial.add(region);
            }
        }
        tag.put("full", new LongArrayTag(full.toLongArray()));
        tag.put("partial", partial);
        if (hasLastRun) {
            CompoundTag last = new CompoundTag();
            last.putInt("world_k", lastWorldK);
            last.putInt("center_x", lastCenterX);
            last.putInt("center_z", lastCenterZ);
            last.putBoolean("completed", lastCompleted);
            tag.put("last_run", last);
        }
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(FILE_NAME + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            NbtIo.writeCompressed(tag, out);
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int bitIndex(int chunkX, int chunkZ) {
        return (chunkX & 31) << 5 | (chunkZ & 31);
    }
}
