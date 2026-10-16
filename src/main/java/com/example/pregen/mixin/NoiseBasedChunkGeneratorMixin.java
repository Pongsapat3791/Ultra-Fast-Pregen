package com.example.pregen.mixin;

import com.example.pregen.WorldgenCompat;
import com.example.pregen.terrain.AquiferAirProof;
import com.example.pregen.terrain.FastNoiseFill;
import com.example.pregen.terrain.TerrainGenerationContext;
import net.minecraft.core.Holder;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Runs FastNoiseFill in place of doFill on this mod's terrain workers only. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @Shadow @Final private Holder<NoiseGeneratorSettings> settings;

    @Shadow
    private NoiseChunk createNoiseChunk(ChunkAccess chunk, StructureManager structureManager, Blender blender,
                                        RandomState randomState) {
        throw new AssertionError();
    }

    @Inject(method = "doFill", at = @At("HEAD"), cancellable = true)
    private void pregen$fastFill(Blender blender, StructureManager structureManager, RandomState randomState,
                                 ChunkAccess chunk, int minCellY, int cellCountY,
                                 CallbackInfoReturnable<ChunkAccess> cir) {
        if (!FastNoiseFill.enabled || !TerrainGenerationContext.isActive() || !WorldgenCompat.fastNoiseFillAllowed()) return;
        NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(
                created -> createNoiseChunk(created, structureManager, blender, randomState));
        ChunkAccess filled = FastNoiseFill.fill(noiseChunk, randomState, settings.value().defaultBlock(),
                chunk, minCellY, cellCountY);
        if (filled != null) {
            if ((Object) noiseChunk.aquifer() instanceof AquiferAirProof aquifer) aquifer.pregen$endFill();
            cir.setReturnValue(filled);
        }
    }

    /** Minecraft's own fill ran (with this mod's aquifer on terrain workers); the fill is over. */
    @Inject(method = "doFill", at = @At("RETURN"))
    private void pregen$endFill(Blender blender, StructureManager structureManager, RandomState randomState,
                                ChunkAccess chunk, int minCellY, int cellCountY,
                                CallbackInfoReturnable<ChunkAccess> cir) {
        NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(
                created -> createNoiseChunk(created, structureManager, blender, randomState));
        if ((Object) noiseChunk.aquifer() instanceof AquiferAirProof aquifer) aquifer.pregen$endFill();
    }
}
