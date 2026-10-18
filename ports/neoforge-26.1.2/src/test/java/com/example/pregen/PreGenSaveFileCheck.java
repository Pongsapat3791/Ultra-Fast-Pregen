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
            record.setLastRun(10, 120, -40, false);
            record.save();
            require(!Files.exists(file.resolveSibling(PreGenSaveFile.FILE_NAME + ".tmp")), "Temporary file must be moved");

            PreGenSaveFile loaded = PreGenSaveFile.load(file, 42L);
            require(loaded.doneChunks() == 1024 + 2, "Every recorded chunk must be loaded back");
            require(loaded.isDone(-1, -1) && loaded.isDone(-32, -32) && loaded.isDone(5, 7) && loaded.isDone(31, 0),
                    "Recorded chunks must be found");
            require(!loaded.isDone(0, 0) && !loaded.isDone(-33, -1) && !loaded.isDone(7, 5),
                    "Chunks never recorded must not be found");
            LongSet among = loaded.doneAmong(new long[]{ChunkPos.pack(5, 7), ChunkPos.pack(6, 7)});
            require(among.size() == 1 && among.contains(ChunkPos.pack(5, 7)), "doneAmong must return only recorded chunks");
            require(loaded.hasLastRun() && loaded.lastWorldK() == 10 && loaded.lastCenterX() == 120
                    && loaded.lastCenterZ() == -40 && !loaded.lastCompleted(), "Last run settings must round-trip");

            PreGenSaveFile otherSeed = PreGenSaveFile.load(file, 43L);
            require(otherSeed.seedChanged() && otherSeed.doneChunks() == 0 && !otherSeed.hasLastRun(),
                    "A record from another seed must be ignored");
            System.out.println("PREGEN_SAVE_FILE_CHECK round_trip=true full_regions=true seed_guard=true");
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
