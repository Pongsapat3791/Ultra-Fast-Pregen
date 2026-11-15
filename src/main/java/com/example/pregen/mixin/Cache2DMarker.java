package com.example.pregen.mixin;

import org.spongepowered.asm.mixin.Mixin;

/** Marks NoiseChunk's last-column cache of a 2D function. */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$Cache2D")
public interface Cache2DMarker {
}
