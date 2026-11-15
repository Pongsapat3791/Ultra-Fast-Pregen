package com.example.pregen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * The family's LegacyCalls for a build against Minecraft 1.20.5 or later: the calls only 1.20 to
 * 1.20.4 have are not here, and are never asked for (this build runs on 1.20.5 and 1.20.6, where
 * ChunkSteps and WorldChunkIO take the other path; see OfficialNames).
 */
final class LegacyCalls {
    private LegacyCalls() {}

    static CompletableFuture<Object> generate(ChunkStatus status, Executor executor, ServerLevel level, ChunkGenerator generator,
                                              StructureTemplateManager templates, ThreadedLevelLightEngine light,
                                              List<ChunkAccess> cache) {
        throw notHere();
    }

    static CompoundTag blockEntityNbt(ProtoChunk chunk, BlockPos pos) {
        throw notHere();
    }

    static RegionFile openRegionFile(Path file, Path folder) throws IOException {
        throw notHere();
    }

    private static IllegalStateException notHere() {
        return new IllegalStateException("This build of Ultra Fast Pregen is for Minecraft 1.20.5 and 1.20.6;"
                + " it found the classes of neither");
    }
}
