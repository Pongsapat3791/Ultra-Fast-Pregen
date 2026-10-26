package com.example.pregen.mixin;

import com.example.pregen.terrain.FastNoiseSource;
import com.example.pregen.terrain.TerrainGenerationContext;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PerlinNoise.class)
public abstract class PerlinNoiseMixin {
    @Shadow @Final private ImprovedNoise[] noiseLevels;
    @Shadow @Final private DoubleList amplitudes;
    @Shadow @Final private double lowestFreqInputFactor;
    @Shadow @Final private double lowestFreqValueFactor;

    @Inject(method = "getValue(DDDDDZ)D", at = @At("HEAD"), cancellable = true)
    private void pregen$sampleMirroredPermutation(double x, double y, double z, double yScale, double yMax,
                                                 boolean useOriginY, CallbackInfoReturnable<Double> cir) {
        if (!TerrainGenerationContext.isFastNoiseEnabled()) return;
        double value = 0.0;
        double inputFactor = lowestFreqInputFactor;
        double valueFactor = lowestFreqValueFactor;
        for (int octave = 0; octave < noiseLevels.length; octave++) {
            ImprovedNoise noise = noiseLevels[octave];
            if (noise != null) {
                double sample = ((FastNoiseSource) (Object) noise).pregen$getFastNoise().noise(
                        PerlinNoise.wrap(x * inputFactor), useOriginY ? -noise.yo : PerlinNoise.wrap(y * inputFactor),
                        PerlinNoise.wrap(z * inputFactor), yScale * inputFactor, yMax * inputFactor);
                // Preserve vanilla multiplication, summation and octave order exactly.
                value += amplitudes.getDouble(octave) * sample * valueFactor;
            }
            inputFactor *= 2.0;
            valueFactor /= 2.0;
        }
        cir.setReturnValue(value);
    }
}
