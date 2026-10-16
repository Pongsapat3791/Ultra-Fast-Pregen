package com.example.pregen.mixin;

import com.example.pregen.terrain.WorldgenLocks;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A template piece sets its shared place settings to the calling chunk's box before placing.
 * Each chunk a piece overlaps places its own part; when two chunks far enough apart to run at
 * once (3+ chunks, so pieces 34+ blocks wide, as some mods' structures are) place the same
 * piece, they would clip to each other's box. One chunk at a time per piece.
 */
@Mixin(TemplateStructurePiece.class)
public abstract class TemplateStructurePieceMixin {
    @Unique private final ReentrantLock pregen$placeLock = new ReentrantLock();

    @Inject(method = "postProcess", at = @At("HEAD"))
    private void pregen$lockPlacement(WorldGenLevel level, StructureManager structures, ChunkGenerator generator,
                                      RandomSource random, BoundingBox box, ChunkPos chunk, BlockPos pos, CallbackInfo ci) {
        WorldgenLocks.acquire(pregen$placeLock, "another chunk placing the same structure piece");
    }

    @Inject(method = "postProcess", at = @At("RETURN"))
    private void pregen$unlockPlacement(WorldGenLevel level, StructureManager structures, ChunkGenerator generator,
                                        RandomSource random, BoundingBox box, ChunkPos chunk, BlockPos pos, CallbackInfo ci) {
        WorldgenLocks.release(pregen$placeLock);
    }
}
