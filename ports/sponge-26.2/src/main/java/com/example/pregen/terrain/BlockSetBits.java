package com.example.pregen.terrain;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A fixed set of blocks for membership tests without a hash set lookup and Holder.equals calls:
 * a few blocks are compared by identity, larger sets use bits by BlockIndex. Feature placement
 * in worldgen-mod-heavy worlds tests millions of blocks against "matching_blocks" predicates per
 * batch (see MatchingBlocksPredicateMixin).
 *
 * Same answer as HolderSet.contains: a direct set (fixed at load, unlike a tag) holds a block
 * when one of its holders is a reference holder for it; reference holders equal by registry
 * key, and a key names one block.
 */
public final class BlockSetBits {
    /** Not a direct set of bound block references: the vanilla lookup answers. */
    public static final BlockSetBits UNSUPPORTED = new BlockSetBits(null, null);
    /** Up to this many blocks are compared one by one (no lookup through the block's class). */
    private static final int MAX_LISTED = 8;

    private final Block[] listed;
    private final long[] bits;

    private BlockSetBits(Block[] listed, long[] bits) {
        this.listed = listed;
        this.bits = bits;
    }

    public static BlockSetBits of(HolderSet<Block> set) {
        if (!(set instanceof HolderSet.Direct<Block>)) return UNSUPPORTED;
        List<Block> blocks = new ArrayList<>();
        for (Holder<Block> holder : set) {
            // Direct (non-reference) holders never equal a block's reference holder.
            if (holder.kind() != Holder.Kind.REFERENCE) continue;
            if (!holder.isBound()) return UNSUPPORTED;
            if (!blocks.contains(holder.value())) blocks.add(holder.value());
        }
        if (blocks.size() <= MAX_LISTED) return new BlockSetBits(blocks.toArray(new Block[0]), null);
        long[] bits = new long[0];
        for (Block block : blocks) {
            int index = ((BlockIndex) block).pregen$blockIndex();
            int word = index >>> 6;
            if (word >= bits.length) bits = Arrays.copyOf(bits, word + 1);
            bits[word] |= 1L << index;
        }
        return new BlockSetBits(null, bits);
    }

    public boolean contains(Block block) {
        Block[] listed = this.listed;
        if (listed != null) {
            for (Block member : listed) {
                if (member == block) return true;
            }
            return false;
        }
        int index = ((BlockIndex) block).pregen$blockIndex();
        int word = index >>> 6;
        return word < bits.length && (bits[word] & (1L << index)) != 0;
    }
}
