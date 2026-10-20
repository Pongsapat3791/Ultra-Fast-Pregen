package com.example.pregen.mixin;

import com.example.pregen.terrain.SurfaceRulePruning;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** The condition SurfaceRules.not negates, for SurfaceRulePruning. */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$NotConditionSource")
public abstract class NotConditionSourceMixin implements SurfaceRulePruning.Negation {
    @Shadow @Final private SurfaceRules.ConditionSource target;

    @Override
    public SurfaceRules.ConditionSource pregen$getNegated() {
        return target;
    }
}
