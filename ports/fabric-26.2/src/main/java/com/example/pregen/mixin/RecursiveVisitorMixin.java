package com.example.pregen.mixin;

import com.example.pregen.terrain.NoiseChunkWrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DensityFunction#mapAll's walk, which maps every node it reaches: a named function or spline,
 * referenced from many places of the router, is mapped once per NoiseChunk (see NoiseChunkWrap.Memo).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunction$1RecursiveVisitor")
public abstract class RecursiveVisitorMixin {
    /** The visitor mapAll was given, which the walk applies to each mapped node. */
    @Shadow @Final private DensityFunction.Visitor val$visitor;

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true)
    private void pregen$known(DensityFunction input, CallbackInfoReturnable<DensityFunction> cir) {
        if (!NoiseChunkWrap.memoized(input)) return;
        Object known = NoiseChunkWrap.known(val$visitor, input);
        if (known != null) cir.setReturnValue((DensityFunction) known);
    }

    @Inject(method = "apply", at = @At("RETURN"))
    private void pregen$remember(DensityFunction input, CallbackInfoReturnable<DensityFunction> cir) {
        if (NoiseChunkWrap.memoized(input)) NoiseChunkWrap.remember(val$visitor, input, cir.getReturnValue());
    }
}
