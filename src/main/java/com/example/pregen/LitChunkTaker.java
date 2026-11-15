package com.example.pregen;

import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;

/** The server's light engine taking a chunk lit elsewhere in one step (see ThreadedLevelLightEngineMixin). */
public interface LitChunkTaker {
    /**
     * Gives the engine the chunk's light (by light section, the lowest first; null or, for block light,
     * empty where there is none) as it takes a lit chunk read from disk: the steps of
     * SerializableChunkData#read, initializeLight and lightChunk for a lit chunk, as one task before the
     * engine's update and one after it. The future completes on the light thread when the engine has it.
     * Null when the engine cannot take it this way; the caller then takes Minecraft's own steps.
     */
    CompletableFuture<?> pregen$takeLitChunk(ChunkAccess chunk, DataLayer[] block, DataLayer[] sky);
}
