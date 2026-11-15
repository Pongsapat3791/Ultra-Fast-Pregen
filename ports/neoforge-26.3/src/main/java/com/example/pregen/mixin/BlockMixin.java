package com.example.pregen.mixin;

import com.example.pregen.InMemoryChunkGrid;
import com.example.pregen.terrain.BlockIndex;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Full-block shape checks on this mod's workers, answered per thread (see
 * InMemoryChunkGrid.WorkerThread#isShapeFullBlock), and each block's BlockIndex.
 */
@Mixin(Block.class)
public abstract class BlockMixin implements BlockIndex {
    @Unique
    private static final AtomicInteger PREGEN$NEXT_INDEX = new AtomicInteger();

    @Unique
    private int pregen$blockIndex;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void pregen$numberBlock(CallbackInfo ci) {
        this.pregen$blockIndex = PREGEN$NEXT_INDEX.getAndIncrement();
    }

    @Override
    public int pregen$blockIndex() {
        return this.pregen$blockIndex;
    }

    @Inject(method = "isShapeFullBlock", at = @At("HEAD"), cancellable = true)
    private static void pregen$isShapeFullBlockPerWorker(VoxelShape shape, CallbackInfoReturnable<Boolean> cir) {
        if (Thread.currentThread() instanceof InMemoryChunkGrid.WorkerThread worker) {
            cir.setReturnValue(worker.isShapeFullBlock(shape));
        }
    }
}
