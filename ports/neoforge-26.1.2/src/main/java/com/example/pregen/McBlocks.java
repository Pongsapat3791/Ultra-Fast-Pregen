package com.example.pregen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Writing a block into a chunk in generation, which Minecraft 1.21.5 gave update flags (this is the family's copy, for 1.21.5 to 26.x; ports/neoforge-1.21.4 has the one
 * for 1.21.2 to 1.21.4, where the third argument says whether the block was moved there). See Mc.
 */
public final class McBlocks {
    private McBlocks() {}

    /** Sets the block with no neighbor updates; answers what was there. */
    public static BlockState set(ChunkAccess chunk, BlockPos pos, BlockState state) {
        return chunk.setBlockState(pos, state, 0);
    }

    /** Sets the block as generation code does by default; answers what was there. */
    public static BlockState place(ChunkAccess chunk, BlockPos pos, BlockState state) {
        return chunk.setBlockState(pos, state);
    }
}
