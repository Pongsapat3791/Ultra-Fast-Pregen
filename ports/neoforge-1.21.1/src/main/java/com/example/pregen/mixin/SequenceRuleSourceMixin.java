package com.example.pregen.mixin;

import com.example.pregen.terrain.SurfaceRulePruning;
import java.util.List;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SurfaceRules.sequence built without the rules the chunk's biomes rule out (see
 * SurfaceRulePruning). Priority above C2ME's, which replaces apply: this stays at its head.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$SequenceRuleSource", priority = 1500)
public abstract class SequenceRuleSourceMixin {
    @Shadow @Final private List<SurfaceRules.RuleSource> sequence;

    @Inject(method = "apply(Lnet/minecraft/world/level/levelgen/SurfaceRules$Context;)Lnet/minecraft/world/level/levelgen/SurfaceRules$SurfaceRule;",
            at = @At("HEAD"), cancellable = true)
    private void pregen$prune(@Coerce Object context, CallbackInfoReturnable<Object> cir) {
        Object rule = SurfaceRulePruning.pruneSequence(sequence, context);
        if (rule != null) cir.setReturnValue(rule);
    }
}
