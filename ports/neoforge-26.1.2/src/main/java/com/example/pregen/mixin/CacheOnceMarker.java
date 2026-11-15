package com.example.pregen.mixin;

import org.spongepowered.asm.mixin.Mixin;

/** Marks NoiseChunk's once-per-position cache (its wrapped function is read through MarkerOrMarked). */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$CacheOnce")
public interface CacheOnceMarker {
}
