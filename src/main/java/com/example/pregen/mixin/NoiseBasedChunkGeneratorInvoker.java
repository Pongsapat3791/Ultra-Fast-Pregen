package com.example.pregen.mixin;

import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(NoiseBasedChunkGenerator.class)
public interface NoiseBasedChunkGeneratorInvoker {

    @Invoker("doCreateBiomes")
    void callDoCreateBiomes(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk);

    @Invoker("doFill")
    ChunkAccess callDoFill(Blender blender, StructureManager structureManager, RandomState randomState, ChunkAccess chunk, int minCellY, int cellCountY);
}
