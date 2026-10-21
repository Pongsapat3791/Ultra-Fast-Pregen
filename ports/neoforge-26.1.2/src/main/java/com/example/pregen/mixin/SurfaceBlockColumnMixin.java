package com.example.pregen.mixin;

import com.example.pregen.WorldgenCompat;
import com.example.pregen.terrain.FastBlockWrites;
import com.example.pregen.terrain.SurfaceColumnCache;
import com.example.pregen.terrain.TerrainGenerationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The surface step's block reads and writes on this mod's terrain workers: reads a section of the
 * column at a time (see SurfaceColumnCache), writes without the section lock (see FastBlockWrites).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceSystem$1")
public abstract class SurfaceBlockColumnMixin {
    /** The column cache of this step's chunk; null until the first read decides it. */
    @Unique private SurfaceColumnCache pregen$column;

    @Unique
    private SurfaceColumnCache pregen$column(ChunkAccess chunk) {
        SurfaceColumnCache column = pregen$column;
        if (column == null) {
            column = SurfaceColumnCache.enabled && TerrainGenerationContext.isActive()
                    && WorldgenCompat.surfaceShortcutsAllowed() ? SurfaceColumnCache.create(chunk) : SurfaceColumnCache.OFF;
            pregen$column = column;
        }
        return column;
    }

    // Only a shortcut: a mod that replaces getBlock leaves Minecraft's own read in place.
    @Redirect(method = "getBlock", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState pregen$readColumn(ChunkAccess chunk, BlockPos pos) {
        return pregen$column(chunk).get(chunk, pos);
    }

    // Only a shortcut: a mod that replaces setBlock leaves Minecraft's own write in place.
    @Redirect(method = "setBlock", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState pregen$setUnlocked(ChunkAccess chunk, BlockPos pos, BlockState state) {
        SurfaceColumnCache column = pregen$column(chunk);
        boolean wasEmpty = column.emptyBefore(pos);
        BlockState previous = null;
        if (FastBlockWrites.enabled && chunk instanceof ProtoChunk proto && TerrainGenerationContext.isActive()) {
            previous = FastBlockWrites.setBeforeLight(proto, pos, state);
        }
        if (previous == null) previous = chunk.setBlockState(pos, state);
        column.written(pos, wasEmpty);
        return previous;
    }
}
