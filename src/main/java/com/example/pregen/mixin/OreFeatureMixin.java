package com.example.pregen.mixin;

import com.example.pregen.terrain.OreHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * An ore attempt whose whole square lies above the terrain ends as Minecraft's does, without the
 * height lookup of every column (see OreHeights). Only in the features step of this mod's batches.
 */
@Mixin(OreFeature.class)
public abstract class OreFeatureMixin {
    // Only a shortcut: without it every attempt probes its columns.
    @Inject(method = "place", require = 0, at = @At("HEAD"), cancellable = true)
    private void pregen$aboveTheTerrain(FeaturePlaceContext<OreConfiguration> context, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) context.level() instanceof OreHeights.Region region)) return;
        OreHeights heights = region.pregen$oreHeights();
        if (heights == null) return;
        // The square and lowest y that place works out before it probes.
        BlockPos origin = context.origin();
        int size = context.config().size;
        float spreadXY = size / 8.0F;
        int maxRadius = Mth.ceil((size / 16.0F * 2.0F + 1.0F) / 2.0F);
        int reach = Mth.ceil(spreadXY) + maxRadius;
        if (!heights.noneAtOrAbove(origin.getX() - reach, origin.getZ() - reach, 2 * reach, origin.getY() - 2 - maxRadius)) return;
        // The three numbers place draws before the probing, so the features after this one draw the same.
        RandomSource random = context.random();
        random.nextFloat();
        random.nextInt(3);
        random.nextInt(3);
        cir.setReturnValue(false);
    }
}
