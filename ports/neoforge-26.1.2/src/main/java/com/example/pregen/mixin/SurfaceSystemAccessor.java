package com.example.pregen.mixin;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The block the surface step asks its rules about (the generator's default block). */
@Mixin(SurfaceSystem.class)
public interface SurfaceSystemAccessor {
    @Accessor("defaultBlock")
    BlockState pregen$getDefaultBlock();
}
