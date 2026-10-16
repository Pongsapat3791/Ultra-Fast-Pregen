package com.example.pregen.mixin;

import com.example.pregen.terrain.FastBlockWrites;
import com.example.pregen.terrain.TerrainGenerationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The surface step's block writes on this mod's terrain workers (see FastBlockWrites). */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceSystem$1")
public abstract class SurfaceBlockColumnMixin {
    @Redirect(method = "setBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState pregen$setUnlocked(ChunkAccess chunk, BlockPos pos, BlockState state, boolean moved) {
        if (FastBlockWrites.enabled && chunk instanceof ProtoChunk proto && TerrainGenerationContext.isActive()) {
            BlockState previous = FastBlockWrites.setBeforeLight(proto, pos, state);
            if (previous != null) return previous;
        }
        return chunk.setBlockState(pos, state, moved);
    }
}
