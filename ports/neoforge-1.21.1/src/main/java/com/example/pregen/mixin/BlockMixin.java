package com.example.pregen.mixin;

import com.example.pregen.InMemoryChunkGrid;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Full-block shape checks on this mod's workers, answered per thread (see InMemoryChunkGrid.WorkerThread#isShapeFullBlock). */
@Mixin(Block.class)
public abstract class BlockMixin {
    @Inject(method = "isShapeFullBlock", at = @At("HEAD"), cancellable = true)
    private static void pregen$isShapeFullBlockPerWorker(VoxelShape shape, CallbackInfoReturnable<Boolean> cir) {
        if (Thread.currentThread() instanceof InMemoryChunkGrid.WorkerThread worker) {
            cir.setReturnValue(worker.isShapeFullBlock(shape));
        }
    }
}
