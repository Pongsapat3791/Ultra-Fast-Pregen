package com.example.pregen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Writing a block into a chunk in generation, which Minecraft 1.21.5 gave update flags (this is the copy for 1.21.2 to 1.21.4, where the third argument says
 * whether the block was moved there; the family's own is in ports/neoforge-26.1.2). See Mc.
 */
public final class McBlocks {
    private McBlocks() {}

    /** Sets the block with no neighbor updates; answers what was there. */
    public static BlockState set(ChunkAccess chunk, BlockPos pos, BlockState state) {
        return chunk.setBlockState(pos, state, false);
    }

    /** Sets the block as generation code does by default; answers what was there. */
    public static BlockState place(ChunkAccess chunk, BlockPos pos, BlockState state) {
        return chunk.setBlockState(pos, state, false);
    }
}
