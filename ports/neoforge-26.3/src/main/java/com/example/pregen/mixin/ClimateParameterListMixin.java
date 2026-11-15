package com.example.pregen.mixin;

import com.example.pregen.terrain.FlatClimateIndex;
import com.example.pregen.terrain.TerrainGenerationContext;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** On this mod's terrain workers, biome lookups search a flat copy of the same R-tree. */
@Mixin(Climate.ParameterList.class)
public abstract class ClimateParameterListMixin {
    @Unique private volatile FlatClimateIndex pregen$flatIndex;
    @Unique private volatile boolean pregen$flatIndexFailed;

    @Inject(method = "findValue", at = @At("HEAD"), cancellable = true)
    private void pregen$findValueFlat(Climate.TargetPoint target, CallbackInfoReturnable<Object> cir) {
        if (pregen$flatIndexFailed || !TerrainGenerationContext.isActive()) return;
        FlatClimateIndex index = pregen$flatIndex;
        if (index == null) {
            index = FlatClimateIndex.of((Climate.ParameterList<?>) (Object) this);
            if (index == null) {
                pregen$flatIndexFailed = true;
                return;
            }
            pregen$flatIndex = index;
        }
        Object value = index.findValue(target);
        if (value != null) cir.setReturnValue(value);
    }
}
