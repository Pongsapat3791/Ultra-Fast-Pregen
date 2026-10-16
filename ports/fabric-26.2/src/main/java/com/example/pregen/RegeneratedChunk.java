package com.example.pregen;

/**
 * A proto chunk the server was generating when this mod replaced its terrain with a finished
 * chunk (see LoadedChunkRegeneration). Implemented on ProtoChunk by ProtoChunkMixin.
 */
public interface RegeneratedChunk {
    boolean pregen$isRegenerated();

    void pregen$markRegenerated();
}
