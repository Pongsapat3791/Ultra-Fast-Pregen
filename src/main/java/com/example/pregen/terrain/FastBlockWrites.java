package com.example.pregen.terrain;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * ProtoChunk.setBlockState for a chunk not yet at INITIALIZE_LIGHT, without the section's
 * cross-thread access check (a semaphore taken and released per block). Each chunk is written
 * by one terrain worker at a time here, and the result is the same: the section write, the
 * early return for air in an empty section, and the update of every heightmap the status
 * keeps. Returns null where vanilla would do more (lighting, or priming a missing heightmap).
 */
public final class FastBlockWrites {
    /** Benchmark switch; on by default. */
    public static volatile boolean enabled = true;

    private FastBlockWrites() {}

    public static BlockState setBeforeLight(ProtoChunk chunk, BlockPos pos, BlockState state) {
        ChunkStatus status = chunk.getStatus();
        if (status.isOrAfter(ChunkStatus.INITIALIZE_LIGHT)) return null;
        int y = pos.getY();
        if (y < chunk.getMinBuildHeight() || y >= chunk.getMaxBuildHeight()) return null;
        EnumSet<Heightmap.Types> heightmaps = status.heightmapsAfter();
        for (Heightmap.Types type : heightmaps) {
            if (!chunk.hasPrimedHeightmap(type)) return null;
        }
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
        if (section.hasOnlyAir() && state.is(Blocks.AIR)) return state;
        int x = pos.getX() & 15;
        int z = pos.getZ() & 15;
        BlockState previous = section.setBlockState(x, y & 15, z, state, false);
        for (Heightmap.Types type : heightmaps) {
            chunk.getOrCreateHeightmapUnprimed(type).update(x, y, z, state);
        }
        return previous;
    }
}
