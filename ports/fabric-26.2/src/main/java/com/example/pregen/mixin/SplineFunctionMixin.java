package com.example.pregen.mixin;

import com.example.pregen.terrain.NoiseChunkWrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A terrain spline, referenced from several functions of the router, wrapped once per NoiseChunk (see NoiseChunkWrap.Memo).
 * Minecraft 26.1 to 26.1.2; 26.2 walks mapAll in one place (RecursiveVisitorMixin, PregenMixinPlugin).
 */
@Mixin(DensityFunctions.Spline.class)
public abstract class SplineFunctionMixin {
    @Inject(method = "mapAll", at = @At("HEAD"), cancellable = true)
    private void pregen$known(DensityFunction.Visitor visitor, CallbackInfoReturnable<DensityFunction> cir) {
        Object known = NoiseChunkWrap.known(visitor, this);
        if (known != null) cir.setReturnValue((DensityFunction) known);
    }

    @Inject(method = "mapAll", at = @At("RETURN"))
    private void pregen$remember(DensityFunction.Visitor visitor, CallbackInfoReturnable<DensityFunction> cir) {
        NoiseChunkWrap.remember(visitor, this, cir.getReturnValue());
    }
}
