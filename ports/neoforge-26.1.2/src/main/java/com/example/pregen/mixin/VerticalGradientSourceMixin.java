package com.example.pregen.mixin;

import com.example.pregen.terrain.DeepSurface;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** The two heights of SurfaceRules.verticalGradient, for DeepSurface. */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$VerticalGradientConditionSource")
public abstract class VerticalGradientSourceMixin implements DeepSurface.GradientSource {
    @Shadow @Final private VerticalAnchor trueAtAndBelow;
    @Shadow @Final private VerticalAnchor falseAtAndAbove;

    @Override
    public VerticalAnchor pregen$trueAtAndBelow() {
        return trueAtAndBelow;
    }

    @Override
    public VerticalAnchor pregen$falseAtAndAbove() {
        return falseAtAndAbove;
    }
}
