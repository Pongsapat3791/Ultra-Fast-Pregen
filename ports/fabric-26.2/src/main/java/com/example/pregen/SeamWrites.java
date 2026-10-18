package com.example.pregen;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.EnumMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;

/**
 * Feature placements that cross a batch boundary. A chunk's features write into its eight
 * neighbors, and Minecraft applies those writes whichever neighbor is generated first. Here a
 * batch's edge neighbors are only temporary copies, so their writes are collected and routed:
 * to a chunk of a later batch before its own features run (as if this batch's chunk had been
 * generated first), or to an already saved chunk of an earlier batch.
 *
 * The saved side follows the same order: a later batch's halo copy of an already saved chunk
 * gets that chunk's final blocks ({@link #keepFinal}), so its features see the neighbor as
 * Minecraft shows a chunk generated earlier, and their writes into it apply as they were made.
 *
 * The same holds for fully generated chunks that were on disk before the run (an earlier run,
 * or the world as players left it): their final blocks are read from disk, they are not
 * generated again, and writes into them are merged into the stored chunk, as Minecraft writes
 * into a generated neighbor. Halo chunks that do not exist yet are saved part-generated (status
 * carvers, with the writes), as Minecraft leaves a generated chunk's neighbors; Minecraft then
 * finishes them when it needs them, and a later run (or a resume) reads the writes back from
 * them ({@link #diffStored}).
 */
final class SeamWrites {
    /** One block a feature changed in a neighbor: absolute position, previous and new state. */
    record Write(int x, int y, int z, BlockState before, BlockState after, CompoundTag blockEntity) {}

    /** ChunkSerializer's codec for a section's block states. */
    static final net.minecraft.world.level.chunk.Strategy<BlockState> STATES =
            net.minecraft.world.level.chunk.Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
    static final Codec<PalettedContainer<BlockState>> BLOCK_STATE_CODEC = PalettedContainer.codecRW(
            BlockState.CODEC, STATES, Blocks.AIR.defaultBlockState());

    /** Off only in the seam regression, to measure the batch edges without this. */
    static volatile boolean enabled = true;
    /**
     * Off only in regressions that generate the same area again with overwrite: the first run's
     * part-generated border chunks would carry that run's writes into the next.
     */
    static volatile boolean importStoredChunks = true;
    /**
     * Off only in the regression's baseline: stored chunks are neither joined nor read back, and
     * no border chunks are saved (the behaviour before 1.20.1-10).
     */
    static volatile boolean joinStoredChunks = true;

    /** Writes waiting for chunks of later batches. */
    private final Long2ObjectOpenHashMap<List<Write>> pending = new Long2ObjectOpenHashMap<>();
    /** Per region, the chunks this run handled (written or kept) and the ones it wrote. */
    private final Map<Long, long[]> handled = new HashMap<>();
    private final Map<Long, long[]> written = new HashMap<>();
    /**
     * A saved chunk as a later batch's halo needs it: final block states (by section index), the
     * heightmaps it was saved with, and the writes merged into it since (see InMemoryChunkGrid
     * #adoptSavedHalo).
     */
    record Final(PalettedContainer<BlockState>[] states, Map<Heightmap.Types, long[]> heightmaps, List<Write> merged) {}

    private final Map<Long, Final> finals = new HashMap<>();
    /** Chunks whose stored part-generated data this run has read, or replaced with its own. */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet storedRead = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private int minSection;
    private long carried;
    private long mergedBack;
    private long joinedExisting;
    private long importedWrites;
    private long borderChunksSaved;

    synchronized void markHandled(long chunk, boolean wrote) {
        set(handled, chunk);
        if (wrote) set(written, chunk);
    }

    /** A fully generated chunk this run did not write but may merge writes into (see class doc). */
    synchronized void markExisting(long chunk) {
        set(written, chunk);
        joinedExisting++;
    }

    synchronized boolean isHandled(long chunk) {
        return isSet(handled, chunk);
    }

    synchronized boolean isMergeable(long chunk) {
        return isSet(written, chunk);
    }

    synchronized boolean storedWasRead(long chunk) {
        return storedRead.contains(chunk);
    }

    /** The chunk's stored part-generated data was read, or this run saved its own over it. */
    synchronized void markStoredRead(long chunk) {
        storedRead.add(chunk);
    }

    synchronized void countBorderSaved(int chunks) {
        borderChunksSaved += chunks;
    }

    /**
     * Writes read back from a stored part-generated chunk ({@link #diffStored}); they reached it
     * before any write of this run.
     */
    synchronized void importStored(long chunk, List<Write> writes) {
        if (writes.isEmpty()) return;
        pending.computeIfAbsent(chunk, key -> new ArrayList<>()).addAll(0, writes);
        importedWrites += writes.size();
    }

    /** The writes waiting for this chunk, left in place (empty when none). */
    synchronized List<Write> peek(long chunk) {
        List<Write> writes = pending.get(chunk);
        return writes == null ? List.of() : List.copyOf(writes);
    }

    synchronized boolean hasPending(long chunk) {
        List<Write> writes = pending.get(chunk);
        return writes != null && !writes.isEmpty();
    }

    /**
     * Routes one batch's writes into its neighbors. Returns the writes for stored, fully
     * generated chunks (saved by this run or before it), to merge into them. Writes for chunks
     * not generated yet are kept: for chunks of later batches until those run, and for every
     * one of them in the part-generated chunk saved with them. Writes for chunks the server
     * holds, or kept chunks that cannot take writes, are dropped.
     */
    synchronized Map<Long, List<Write>> route(Map<Long, List<Write>> writes, ChunkBatchPlan plan,
                                              java.util.function.LongPredicate heldByServer) {
        Map<Long, List<Write>> backToSaved = new HashMap<>();
        for (Map.Entry<Long, List<Write>> entry : writes.entrySet()) {
            long chunk = entry.getKey();
            if (isSet(written, chunk)) {
                backToSaved.put(chunk, entry.getValue());
                mergedBack += entry.getValue().size();
                updateFinal(chunk, entry.getValue());
            } else if (heldByServer.test(chunk)) {
                continue;
            } else if (!plan.containsChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) || !isSet(handled, chunk)) {
                pending.computeIfAbsent(chunk, key -> new ArrayList<>()).addAll(entry.getValue());
            }
        }
        return backToSaved;
    }

    /** Keeps a copy of a saved chunk's final block states while a later batch may need them. */
    @SuppressWarnings("unchecked")
    synchronized void keepFinal(long chunk, ChunkAccess saved) {
        LevelChunkSection[] sections = saved.getSections();
        PalettedContainer<BlockState>[] copy = new PalettedContainer[sections.length];
        for (int i = 0; i < sections.length; i++) copy[i] = writableCopy(sections[i].getStates());
        Map<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
        for (Map.Entry<Heightmap.Types, Heightmap> entry : saved.getHeightmaps()) {
            heightmaps.put(entry.getKey(), entry.getValue().getRawData().clone());
        }
        finals.put(chunk, new Final(copy, heightmaps, new ArrayList<>()));
        minSection = saved.getMinSectionY();
    }

    /** Keeps a stored chunk's final state read from disk ({@link #finalFromStored}). */
    synchronized void keepFinal(long chunk, Final stored, int minSectionY) {
        finals.put(chunk, stored);
        minSection = minSectionY;
    }

    private static boolean currentVersion(CompoundTag chunk) {
        return chunk.contains("DataVersion") && chunk.getIntOr("DataVersion", 0)
                == net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
    }

    /** The stored chunk's status, or null when it is unknown or from another Minecraft version. */
    static net.minecraft.world.level.chunk.status.ChunkStatus storedStatus(CompoundTag chunk) {
        if (!currentVersion(chunk) || !chunk.contains("Status")) return null;
        return net.minecraft.world.level.chunk.status.ChunkStatus.byName(chunk.getStringOr("Status", ""));
    }

    /**
     * A stored chunk's block states (by section index) and heightmaps, or null when it is not a
     * fully generated chunk of this Minecraft version (older chunks are upgraded only when the
     * server loads them, so they are left alone).
     */
    @SuppressWarnings("unchecked")
    static Final finalFromStored(CompoundTag chunk, int minSectionY, int sectionCount) {
        if (storedStatus(chunk) != net.minecraft.world.level.chunk.status.ChunkStatus.FULL) return null;
        PalettedContainer<BlockState>[] states = new PalettedContainer[sectionCount];
        ListTag sections = chunk.getListOrEmpty("sections");
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompoundOrEmpty(i);
            int index = section.getByteOr("Y", (byte) 0) - minSectionY;
            if (index < 0 || index >= sectionCount || !section.contains("block_states")) continue;
            PalettedContainer<BlockState> decoded = BLOCK_STATE_CODEC
                    .parse(NbtOps.INSTANCE, section.getCompoundOrEmpty("block_states")).result().orElse(null);
            if (decoded == null) return null;
            states[index] = writableCopy(decoded);
        }
        for (int i = 0; i < sectionCount; i++) {
            if (states[i] == null) {
                states[i] = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), STATES);
            }
        }
        Map<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
        CompoundTag stored = chunk.getCompoundOrEmpty("Heightmaps");
        for (Heightmap.Types type : Heightmap.Types.values()) {
            if (stored.contains(type.getSerializationKey())) {
                heightmaps.put(type, stored.getLongArray(type.getSerializationKey()).orElse(new long[0]));
            }
        }
        return new Final(states, heightmaps, new ArrayList<>());
    }

    /**
     * The writes other chunks' features made into a stored part-generated chunk: every block
     * where it differs from the same chunk generated again up to carvers. Only chunks stored at
     * status carvers (as this mod and Minecraft leave the neighbors of a generated chunk) are
     * read; at other statuses the difference would include the chunk's own steps.
     */
    static List<Write> diffStored(CompoundTag chunk, ProtoChunk regenerated) {
        if (storedStatus(chunk) != net.minecraft.world.level.chunk.status.ChunkStatus.CARVERS) return List.of();
        int minX = regenerated.getPos().getMinBlockX();
        int minZ = regenerated.getPos().getMinBlockZ();
        Map<Long, CompoundTag> blockEntities = new HashMap<>();
        ListTag entities = chunk.getListOrEmpty("block_entities");
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag entity = entities.getCompoundOrEmpty(i);
            blockEntities.put(BlockPos.asLong(entity.getIntOr("x", 0), entity.getIntOr("y", 0), entity.getIntOr("z", 0)), entity);
        }
        List<Write> writes = new ArrayList<>();
        LevelChunkSection[] mine = regenerated.getSections();
        ListTag sections = chunk.getListOrEmpty("sections");
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompoundOrEmpty(i);
            int sectionY = section.getByteOr("Y", (byte) 0);
            int index = regenerated.getSectionIndexFromSectionY(sectionY);
            if (index < 0 || index >= mine.length || !section.contains("block_states")) continue;
            PalettedContainer<BlockState> stored = BLOCK_STATE_CODEC
                    .parse(NbtOps.INSTANCE, section.getCompoundOrEmpty("block_states")).result().orElse(null);
            if (stored == null) continue;
            PalettedContainer<BlockState> generated = mine[index].getStates();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState after = stored.get(x, y, z);
                        BlockState before = generated.get(x, y, z);
                        if (after == before) continue;
                        int bx = minX + x;
                        int by = (sectionY << 4) + y;
                        int bz = minZ + z;
                        CompoundTag entity = after.hasBlockEntity() ? blockEntities.get(BlockPos.asLong(bx, by, bz)) : null;
                        writes.add(new Write(bx, by, bz, before, after, entity));
                    }
                }
            }
        }
        return writes;
    }

    /**
     * A copy that can be written to. PalettedContainer.copy() keeps the source's resize handler
     * in its palette (Minecraft 1.20.1), so a write that grows the copy's palette resizes the
     * source instead and corrupts the copy; copies made by copy() are only safe to read.
     */
    static PalettedContainer<BlockState> writableCopy(PalettedContainer<BlockState> source) {
        PalettedContainer<BlockState> copy = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), STATES);
        if (!source.maybeHas(state -> !state.isAir())) return copy;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = source.get(x, y, z);
                    if (!state.isAir()) copy.getAndSetUnchecked(x, y, z, state);
                }
            }
        }
        return copy;
    }

    /** The kept final state of a saved chunk, or null. Callers copy before changing it. */
    synchronized Final finalOf(long chunk) {
        return finals.get(chunk);
    }

    /** Whether some chunk around this one lies in the area and is not handled yet. */
    synchronized boolean hasUnhandledNeighbor(long chunk, ChunkBatchPlan plan) {
        int x = ChunkPos.getX(chunk);
        int z = ChunkPos.getZ(chunk);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx != 0 || dz != 0) && plan.containsChunk(x + dx, z + dz)
                        && !isSet(handled, ChunkPos.pack(x + dx, z + dz))) return true;
            }
        }
        return false;
    }

    /**
     * Drops kept final states, and writes kept for chunks outside the area (already saved with
     * them), that no later batch needs any more.
     */
    synchronized void pruneFinals(ChunkBatchPlan plan) {
        finals.keySet().removeIf(chunk -> settled(chunk, plan));
        pending.keySet().removeIf(chunk -> !plan.containsChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))
                && !hasUnhandledNeighbor(chunk, plan));
    }

    private boolean settled(long chunk, ChunkBatchPlan plan) {
        boolean inArea = plan.containsChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
        return (!inArea || isSet(handled, chunk)) && !hasUnhandledNeighbor(chunk, plan);
    }

    synchronized int keptFinals() {
        return finals.size();
    }

    /** Writes taken for a batch that is generated again (see DynamicBatchPreGen#generateWithFallback). */
    synchronized void putBack(long chunk, List<Write> writes) {
        if (writes.isEmpty()) return;
        pending.computeIfAbsent(chunk, key -> new ArrayList<>()).addAll(0, writes);
        carried -= writes.size();
    }

    /** Removes and returns the writes waiting for this chunk (empty when none). */
    synchronized List<Write> take(long chunk) {
        List<Write> writes = pending.remove(chunk);
        if (writes == null) return List.of();
        carried += writes.size();
        return writes;
    }

    synchronized long carriedForward() {
        return carried;
    }

    synchronized long mergedBack() {
        return mergedBack;
    }

    synchronized int pendingChunks() {
        return pending.size();
    }

    synchronized long joinedExisting() {
        return joinedExisting;
    }

    synchronized long importedWrites() {
        return importedWrites;
    }

    synchronized long borderChunksSaved() {
        return borderChunksSaved;
    }

    /** Applies writes to a chunk of this batch after its carvers, before its own features. */
    static void applyToGenerating(ProtoChunk chunk, List<Write> writes) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Write write : writes) {
            pos.set(write.x(), write.y(), write.z());
            chunk.setBlockState(pos, write.after(), 0);
            if (!write.after().getFluidState().isEmpty()) chunk.markPosForPostProcessing(pos.immutable());
            if (write.blockEntity() != null) chunk.setBlockEntityNbt(write.blockEntity().copy());
        }
    }

    /**
     * Applies writes to a saved chunk's NBT where the stored block still is what the write found,
     * i.e. the chunk's own features did not change it. Heightmaps are dropped and the light marked
     * stale, so the server recomputes both when it loads the chunk. Returns false if unchanged.
     */
    static boolean applyToSaved(CompoundTag chunk, List<Write> writes) {
        ListTag sections = chunk.getListOrEmpty("sections");
        Map<Integer, CompoundTag> sectionByY = new HashMap<>();
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompoundOrEmpty(i);
            sectionByY.put((int) section.getByteOr("Y", (byte) 0), section);
        }
        Map<Integer, PalettedContainer<BlockState>> decoded = new HashMap<>();
        ListTag blockEntities = chunk.getListOrEmpty("block_entities");
        boolean changed = false;
        for (Write write : writes) {
            int sectionY = write.y() >> 4;
            CompoundTag section = sectionByY.get(sectionY);
            if (section == null || !section.contains("block_states")) continue;
            PalettedContainer<BlockState> states = decoded.computeIfAbsent(sectionY, y -> BLOCK_STATE_CODEC
                    .parse(NbtOps.INSTANCE, section.getCompoundOrEmpty("block_states")).result().orElse(null));
            if (states == null) continue;
            int x = write.x() & 15;
            int y = write.y() & 15;
            int z = write.z() & 15;
            if (states.get(x, y, z) != write.before()) continue;
            states.getAndSetUnchecked(x, y, z, write.after());
            removeBlockEntity(blockEntities, write.x(), write.y(), write.z());
            if (write.blockEntity() != null) blockEntities.add(write.blockEntity().copy());
            changed = true;
        }
        if (!changed) return false;
        for (Map.Entry<Integer, PalettedContainer<BlockState>> entry : decoded.entrySet()) {
            if (entry.getValue() == null) continue;
            BLOCK_STATE_CODEC.encodeStart(NbtOps.INSTANCE, entry.getValue()).result()
                    .ifPresent(tag -> sectionByY.get(entry.getKey()).put("block_states", tag));
        }
        chunk.put("block_entities", blockEntities);
        // The server recomputes the final heightmaps on load; the stored worldgen ones stay as
        // a part-generated neighbor keeps them (see InMemoryChunkGrid#adoptSaved).
        CompoundTag heightmaps = chunk.getCompoundOrEmpty("Heightmaps");
        for (Heightmap.Types type : Heightmap.Types.values()) {
            if (type.keepAfterWorldgen()) heightmaps.remove(type.getSerializationKey());
        }
        chunk.put("Heightmaps", heightmaps);
        chunk.putBoolean("isLightOn", false);
        return true;
    }

    /** Keeps a kept final copy equal to the saved chunk after applyToSaved. */
    private void updateFinal(long chunk, List<Write> writes) {
        Final kept = finals.get(chunk);
        if (kept == null) return;
        PalettedContainer<BlockState>[] states = kept.states();
        for (Write write : writes) {
            int index = (write.y() >> 4) - minSection;
            if (index < 0 || index >= states.length) continue;
            int x = write.x() & 15;
            int y = write.y() & 15;
            int z = write.z() & 15;
            if (states[index].get(x, y, z) == write.before()) {
                states[index].getAndSetUnchecked(x, y, z, write.after());
                kept.merged().add(write);
            }
        }
    }

    private static void removeBlockEntity(ListTag blockEntities, int x, int y, int z) {
        for (int i = blockEntities.size() - 1; i >= 0; i--) {
            CompoundTag entity = blockEntities.getCompoundOrEmpty(i);
            if (entity.getIntOr("x", 0) == x && entity.getIntOr("y", 0) == y && entity.getIntOr("z", 0) == z) blockEntities.remove(i);
        }
    }

    private static void set(Map<Long, long[]> bits, long chunk) {
        int x = ChunkPos.getX(chunk);
        int z = ChunkPos.getZ(chunk);
        int index = (x & 31) * 32 + (z & 31);
        bits.computeIfAbsent(ChunkPos.pack(x >> 5, z >> 5), key -> new long[16])[index >>> 6] |= 1L << index;
    }

    private static boolean isSet(Map<Long, long[]> bits, long chunk) {
        int x = ChunkPos.getX(chunk);
        int z = ChunkPos.getZ(chunk);
        long[] region = bits.get(ChunkPos.pack(x >> 5, z >> 5));
        int index = (x & 31) * 32 + (z & 31);
        return region != null && (region[index >>> 6] & 1L << index) != 0;
    }
}
