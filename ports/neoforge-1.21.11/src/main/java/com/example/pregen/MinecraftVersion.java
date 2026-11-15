package com.example.pregen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Minecraft 1.21.11's copy of the family's version probe (which tells 26.1 from 26.2 at start-up): this version is
 * neither, so nothing is probed. mixin.PregenMixinPlugin finds no DensityFunction$1RecursiveVisitor here either and
 * takes the mixins of before 26.2.
 */
public final class MinecraftVersion {
    public static final boolean MINECRAFT_26_2 = false;

    private MinecraftVersion() {}

    public static void markPosForPostProcessing(ChunkAccess chunk, BlockPos pos) {
        chunk.markPosForPostprocessing(pos);
    }

    static Class<?> recursiveVisitor() throws ClassNotFoundException {
        throw new ClassNotFoundException("DensityFunction$1RecursiveVisitor is Minecraft 26.2's");
    }
}
