package com.example.pregen.mixin;

import com.example.pregen.InMemoryChunkGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Light as a chunk's features read it. Minecraft places a chunk's features before it lights the chunk; what a
 * feature reads then is the light that already flowed in from lit chunks beside it: low a few blocks away from them.
 * A batch's chunks have no light in the engine at all at that time, and the engine answers sky light 15 for them,
 * so a block that needs dim light where it is placed never was (a brown mushroom on plain ground: Minecraft keeps
 * those that end up under trees and removes the others when the chunk becomes full). While a thread places the
 * features of a batch's chunk, raw brightness is the block light alone. Off with -Dfastpregen.darkFeatures=false.
 */
@Mixin(LevelLightEngine.class)
public abstract class LevelLightEngineMixin {
    @Inject(method = "getRawBrightness", at = @At("HEAD"), cancellable = true, require = 0)
    private void pregen$noSkyLightWhileFeatures(BlockPos pos, int amount, CallbackInfoReturnable<Integer> cir) {
        if (InMemoryChunkGrid.placingFeatures()) {
            cir.setReturnValue(((LevelLightEngine) (Object) this).getLayerListener(LightLayer.BLOCK).getLightValue(pos));
        }
    }
}
