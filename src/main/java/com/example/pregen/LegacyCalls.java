package com.example.pregen;

import com.mojang.datafixers.util.Either;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * The three calls of Minecraft 1.20 to 1.20.4 that 1.20.5 changed, spelled as this source is compiled
 * (against 1.20.1): the Fabric jar and the Forge jar of 1.20 to 1.20.4 use them on those versions, and
 * reach 1.20.5's spellings by name (ChunkSteps.Modern, WorldChunkIO.ModernRegionFile). A tree compiled
 * against 1.20.5 or later cannot hold these calls: it has its own class of this name, with none of
 * them (ports/neoforge-1.20.6).
 */
final class LegacyCalls {
    /** No status step up to SPAWN converts to a full chunk; FULL would get the chunk back as it is. */
    private static final Function<ChunkAccess, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> FULL_CHUNK =
            chunk -> CompletableFuture.completedFuture(Either.left(chunk));

    private LegacyCalls() {}

    /** status.generate on the cache's chunks; the future's value is not used. */
    @SuppressWarnings("unchecked")
    static CompletableFuture<Object> generate(ChunkStatus status, Executor executor, ServerLevel level, ChunkGenerator generator,
                                              StructureTemplateManager templates, ThreadedLevelLightEngine light,
                                              List<ChunkAccess> cache) {
        return (CompletableFuture<Object>) (CompletableFuture<?>) status.generate(executor, level, generator, templates,
                light, FULL_CHUNK, cache);
    }

    static CompoundTag blockEntityNbt(ProtoChunk chunk, BlockPos pos) {
        return chunk.getBlockEntityNbtForSaving(pos);
    }

    /** new RegionFile(file, folder, false), as the storage opens its files. */
    static RegionFile openRegionFile(Path file, Path folder) throws IOException {
        return new RegionFile(file, folder, false);
    }
}
