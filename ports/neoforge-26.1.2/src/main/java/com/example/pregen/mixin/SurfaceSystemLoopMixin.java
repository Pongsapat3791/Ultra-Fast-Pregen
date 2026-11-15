package com.example.pregen.mixin;

import com.example.pregen.terrain.DeepSurface;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The surface step's walk down a column, below the ground: where every block has its final block
 * already (see DeepSurface.Columns#floor), the walk's own read is answered with air, for which
 * Minecraft's loop does nothing, without looking at the column. Only the walk's read (the first
 * read of a block in buildSurface): the look-ahead for the next block that is not stone, the
 * second, reads the real column, so the depths the rules above the floor see stay as they are.
 */
@Mixin(SurfaceSystem.class)
public abstract class SurfaceSystemLoopMixin {
    // Only a shortcut: where this cannot be placed, the loop walks the whole column as before.
    @Redirect(method = "buildSurface", require = 0, at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/world/level/chunk/BlockColumn;getBlock(I)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState pregen$readAboveFloor(BlockColumn column, int blockY) {
        if (column instanceof DeepSurface.FloorColumn deep && blockY < deep.pregen$floor()) return Blocks.AIR.defaultBlockState();
        return column.getBlock(blockY);
    }
}
