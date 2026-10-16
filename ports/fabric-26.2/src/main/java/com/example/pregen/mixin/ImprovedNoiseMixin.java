package com.example.pregen.mixin;

import com.example.pregen.terrain.FastImprovedNoise;
import com.example.pregen.terrain.FastNoiseSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ImprovedNoise.class)
public abstract class ImprovedNoiseMixin implements FastNoiseSource {
    @Shadow @Final private byte[] p;
    @Shadow @Final public double xo;
    @Shadow @Final public double yo;
    @Shadow @Final public double zo;
    @Unique private FastImprovedNoise pregen$fastNoise;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void pregen$mirrorPermutation(CallbackInfo ci) {
        pregen$fastNoise = new FastImprovedNoise(p, xo, yo, zo);
    }

    @Override
    public FastImprovedNoise pregen$getFastNoise() {
        return pregen$fastNoise;
    }
}
