package com.example.pregen.mixin;

import com.example.pregen.terrain.NoiseChunkWrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A spline coordinate (continents, erosion, ridges...), read at every point of the terrain splines, wrapped once per NoiseChunk (see NoiseChunkWrap.Memo).
 * Minecraft 26.1 to 26.1.2; 26.2 walks mapAll in one place (RecursiveVisitorMixin, PregenMixinPlugin).
 */
@Mixin(DensityFunctions.Spline.Coordinate.class)
public abstract class SplineCoordinateMixin {
    @Inject(method = "mapAll", at = @At("HEAD"), cancellable = true)
    private void pregen$known(DensityFunction.Visitor visitor, CallbackInfoReturnable<DensityFunctions.Spline.Coordinate> cir) {
        Object known = NoiseChunkWrap.known(visitor, this);
        if (known != null) cir.setReturnValue((DensityFunctions.Spline.Coordinate) known);
    }

    @Inject(method = "mapAll", at = @At("RETURN"))
    private void pregen$remember(DensityFunction.Visitor visitor, CallbackInfoReturnable<DensityFunctions.Spline.Coordinate> cir) {
        NoiseChunkWrap.remember(visitor, this, cir.getReturnValue());
    }
}
