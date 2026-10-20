package com.example.pregen.terrain;

import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * A noise biome source that answers from chunks: its getNoiseBiome(x, y, z) is the getNoiseBiome
 * of the chunk holding that cell, when it has one. Minecraft's world generation region is one
 * (LevelReader#getNoiseBiome; see WorldGenRegionMixin).
 */
public interface BiomeChunkSource {
    /** The chunk whose biomes this source returns for that chunk's cells, or null where it has none. */
    ChunkAccess pregen$getBiomeChunk(int chunkX, int chunkZ);
}
