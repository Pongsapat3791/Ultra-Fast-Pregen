package com.example.pregen.mixin;

import com.example.pregen.terrain.SurfaceRulePruning;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SurfaceRules.ifTrue built without the biome tests the chunk decides (see SurfaceRulePruning).
 * Priority above the default so that a mod replacing apply keeps this at its head.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$TestRuleSource", priority = 1500)
public abstract class TestRuleSourceMixin {
    @Shadow @Final private SurfaceRules.ConditionSource ifTrue;
    @Shadow @Final private SurfaceRules.RuleSource thenRun;

    @Inject(method = "apply(Lnet/minecraft/world/level/levelgen/SurfaceRules$Context;)Lnet/minecraft/world/level/levelgen/SurfaceRules$SurfaceRule;",
            at = @At("HEAD"), cancellable = true)
    private void pregen$prune(@Coerce Object context, CallbackInfoReturnable<Object> cir) {
        Object rule = SurfaceRulePruning.pruneTest(ifTrue, thenRun, context);
        if (rule != null) cir.setReturnValue(rule);
    }
}
