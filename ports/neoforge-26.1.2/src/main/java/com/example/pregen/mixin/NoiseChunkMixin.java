package com.example.pregen.mixin;

import com.example.pregen.WorldgenCompat;
import com.example.pregen.terrain.ColumnCachedDensity;
import com.example.pregen.terrain.TerrainGenerationContext;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** On this mod's terrain workers, caches 2D climate noises per column (see ColumnCachedDensity). */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin {
    @Shadow @Final int firstNoiseX;
    @Shadow @Final int firstNoiseZ;

    @Inject(method = "wrapNew", at = @At("RETURN"), cancellable = true)
    private void pregen$cacheColumnConstant(DensityFunction function, CallbackInfoReturnable<DensityFunction> cir) {
        DensityFunction wrapped = cir.getReturnValue();
        if (!(wrapped instanceof ShiftedNoiseAccessor) || !TerrainGenerationContext.isActive()
                || !WorldgenCompat.fastNoiseFillAllowed()) {
            return;
        }
        DensityFunction cached = ColumnCachedDensity.wrapIfColumnConstant(wrapped,
                QuartPos.toBlock(firstNoiseX), QuartPos.toBlock(firstNoiseZ));
        if (cached != wrapped) cir.setReturnValue(cached);
    }
}
