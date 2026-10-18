package com.example.pregen.mixin;

import com.example.pregen.terrain.BlockSetBits;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** "matching_blocks" predicates answer from BlockSetBits when their set is a fixed list of blocks. */
@Mixin(targets = "net.minecraft.world.level.levelgen.blockpredicates.MatchingBlocksPredicate")
public abstract class MatchingBlocksPredicateMixin {
    @Shadow @Final private HolderSet<Block> blocks;

    @Unique
    private BlockSetBits pregen$bits;

    @Inject(method = "test(Lnet/minecraft/world/level/block/state/BlockState;)Z", at = @At("HEAD"), cancellable = true)
    private void pregen$testBits(BlockState state, CallbackInfoReturnable<Boolean> cir) {
        BlockSetBits bits = this.pregen$bits;
        if (bits == null) {
            bits = BlockSetBits.of(this.blocks);
            this.pregen$bits = bits;
        }
        if (bits != BlockSetBits.UNSUPPORTED) {
            cir.setReturnValue(bits.contains(state.getBlock()));
        }
    }
}
