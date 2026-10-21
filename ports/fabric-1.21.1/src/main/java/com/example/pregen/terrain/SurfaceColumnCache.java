package com.example.pregen.terrain;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;

/**
 * The surface step's reads of one column, a section at a time (ModernFix's prefetching block
 * column does much the same). Minecraft's surface walks every column from its surface to the
 * bottom of the world, reading each block through the chunk, and looks ahead below each run of
 * stone, reading those blocks again. Here a section's 16 blocks of the column are read once, with
 * the values ProtoChunk#getBlockState gives (air for a section holding only air), and writes of
 * the step keep them so: an entry is read again after its write, the whole section where a write
 * empties it or fills an empty one.
 */
public final class SurfaceColumnCache {
    /** Benchmark switch, on unless -Dfastpregen.surfaceColumn=false. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("fastpregen.surfaceColumn", "true"));
    /** For a column whose chunk this does not read (Minecraft's own reads then). */
    public static final SurfaceColumnCache OFF = new SurfaceColumnCache(null, 0, 0);

    private final ProtoChunk chunk;
    private final int minY;
    private final BlockState[] blocks;
    private int x = Integer.MIN_VALUE;
    private int z;
    /** The sections of the current column read so far, one bit each. */
    private long filled;

    private SurfaceColumnCache(ProtoChunk chunk, int minY, int height) {
        this.chunk = chunk;
        this.minY = minY;
        this.blocks = new BlockState[height];
    }

    /** For the surface step of this chunk, or OFF for chunks other than Minecraft's proto chunks. */
    public static SurfaceColumnCache create(ChunkAccess chunk) {
        if (chunk.getClass() != ProtoChunk.class || chunk.getSectionsCount() > 64) return OFF;
        return new SurfaceColumnCache((ProtoChunk) chunk, chunk.getMinBuildHeight(), chunk.getSectionsCount() * 16);
    }

    /** ProtoChunk#getBlockState(pos) for the step's column. */
    public BlockState get(ChunkAccess chunk, BlockPos pos) {
        int index = pos.getY() - minY;
        if (chunk != this.chunk || index < 0 || index >= blocks.length) return chunk.getBlockState(pos);
        int blockX = pos.getX();
        int blockZ = pos.getZ();
        if (blockX != x || blockZ != z) {
            x = blockX;
            z = blockZ;
            filled = 0;
        }
        int section = index >> 4;
        if ((filled & (1L << section)) == 0) fill(section);
        return blocks[index];
    }

    private void fill(int section) {
        LevelChunkSection blocksOfSection = chunk.getSection(section);
        int base = section << 4;
        if (blocksOfSection.hasOnlyAir()) {
            Arrays.fill(blocks, base, base + 16, Blocks.AIR.defaultBlockState());
        } else {
            int localX = x & 15;
            int localZ = z & 15;
            for (int y = 0; y < 16; y++) blocks[base + y] = blocksOfSection.getBlockState(localX, y, localZ);
        }
        filled |= 1L << section;
    }

    /** Whether the section at pos holds only air, before a write there (see written). */
    public boolean emptyBefore(BlockPos pos) {
        int index = pos.getY() - minY;
        return chunk != null && index >= 0 && index < blocks.length && chunk.getSection(index >> 4).hasOnlyAir();
    }

    /** After the step wrote at pos: what reads there give now. */
    public void written(BlockPos pos, boolean wasEmpty) {
        int index = pos.getY() - minY;
        if (chunk == null || pos.getX() != x || pos.getZ() != z || index < 0 || index >= blocks.length) return;
        int section = index >> 4;
        if ((filled & (1L << section)) == 0) return;
        LevelChunkSection blocksOfSection = chunk.getSection(section);
        if (wasEmpty || blocksOfSection.hasOnlyAir()) {
            filled &= ~(1L << section);
        } else {
            blocks[index] = blocksOfSection.getBlockState(x & 15, index & 15, z & 15);
        }
    }
}
