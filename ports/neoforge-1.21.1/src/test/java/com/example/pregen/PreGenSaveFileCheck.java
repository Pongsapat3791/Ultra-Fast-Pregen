package com.example.pregen;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.ChunkPos;

/** The save file must round-trip generated chunks (full and partial regions) and the last run. */
public final class PreGenSaveFileCheck {
    private PreGenSaveFileCheck() {}

    public static void run() throws IOException {
        Path dir = Files.createTempDirectory("fastpregen-save");
        Path file = dir.resolve("data").resolve(PreGenSaveFile.FILE_NAME);
        try {
            PreGenSaveFile record = PreGenSaveFile.load(file, 42L);
            require(record.doneChunks() == 0 && !record.hasLastRun(), "A missing file must start empty");

            List<ChunkPos> done = new ArrayList<>();
            // One whole region with negative coordinates, plus a few chunks of another.
            for (int x = -32; x < 0; x++) {
                for (int z = -32; z < 0; z++) done.add(new ChunkPos(x, z));
            }
            done.add(new ChunkPos(5, 7));
            done.add(new ChunkPos(31, 0));
            record.markDone(done);
            record.setLastRun(10_500, 120, -40, ChunkBatchPlan.Shape.CIRCLE, false);
            record.setLastStopped(true);
            record.countAutoResume();
            record.countAutoResume();
            record.save();
            require(!Files.exists(file.resolveSibling(PreGenSaveFile.FILE_NAME + ".tmp")), "Temporary file must be moved");

            PreGenSaveFile loaded = PreGenSaveFile.load(file, 42L);
            require(loaded.doneChunks() == 1024 + 2, "Every recorded chunk must be loaded back");
            require(loaded.isDone(-1, -1) && loaded.isDone(-32, -32) && loaded.isDone(5, 7) && loaded.isDone(31, 0),
                    "Recorded chunks must be found");
            require(!loaded.isDone(0, 0) && !loaded.isDone(-33, -1) && !loaded.isDone(7, 5),
                    "Chunks never recorded must not be found");
            LongSet among = loaded.doneAmong(new long[]{ChunkPos.asLong(5, 7), ChunkPos.asLong(6, 7)});
            require(among.size() == 1 && among.contains(ChunkPos.asLong(5, 7)), "doneAmong must return only recorded chunks");
            require(loaded.hasLastRun() && loaded.lastSizeBlocks() == 10_500 && loaded.lastCenterX() == 120
                    && loaded.lastCenterZ() == -40 && !loaded.lastCompleted() && loaded.lastShape() == ChunkBatchPlan.Shape.CIRCLE
                    && loaded.lastStopped() && loaded.autoResumes() == 2,
                    "Last run settings must round-trip");
            // Round 14 and earlier read world_k only: the size rounded up to whole thousands, and
            // their files (world_k without size_blocks) still load.
            net.minecraft.nbt.CompoundTag tag;
            try (var in = Files.newInputStream(file)) {
                tag = net.minecraft.nbt.NbtIo.readCompressed(in, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            }
            require(tag.getCompound("last_run").getInt("world_k") == 11, "world_k must hold the size rounded up");
            tag.getCompound("last_run").remove("size_blocks");
            tag.getCompound("last_run").remove("shape");
            try (var out = Files.newOutputStream(file)) {
                net.minecraft.nbt.NbtIo.writeCompressed(tag, out);
            }
            PreGenSaveFile round14 = PreGenSaveFile.load(file, 42L);
            require(round14.lastSizeBlocks() == 11_000 && round14.lastShape() == ChunkBatchPlan.Shape.SQUARE,
                    "A round 14 file must load its world_k as a square");

            PreGenSaveFile otherSeed = PreGenSaveFile.load(file, 43L);
            require(otherSeed.seedChanged() && otherSeed.doneChunks() == 0 && !otherSeed.hasLastRun(),
                    "A record from another seed must be ignored");
            System.out.println("PREGEN_SAVE_FILE_CHECK round_trip=true full_regions=true seed_guard=true size_in_blocks=true shape=true stopped_and_continuations=true");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.getParent());
            Files.deleteIfExists(dir);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
