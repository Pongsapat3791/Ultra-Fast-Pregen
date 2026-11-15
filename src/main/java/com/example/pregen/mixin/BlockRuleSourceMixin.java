package com.example.pregen.mixin;

import com.example.pregen.terrain.DeepSurface;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** The block SurfaceRules.state places, for DeepSurface. */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$BlockRuleSource")
public abstract class BlockRuleSourceMixin implements DeepSurface.BlockSource {
    @Shadow @Final private BlockState resultState;

    @Override
    public BlockState pregen$resultState() {
        return resultState;
    }
}
