package com.example.pregen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * What differs between the Minecraft versions this jar runs on (26.1 to 26.2) outside the mixins,
 * which mixin.PregenMixinPlugin chooses by the same test.
 */
public final class MinecraftVersion {
    private static final String RECURSIVE_VISITOR = "net.minecraft.world.level.levelgen.DensityFunction$1RecursiveVisitor";
    /** 26.2 or later: DensityFunction#mapAll walks every function through one local class. */
    public static final boolean MINECRAFT_26_2 = present(RECURSIVE_VISITOR);
    /** ChunkAccess#markPosForPostProcessing, spelled markPosForPostprocessing before 26.2. */
    private static final MethodHandle MARK_POS_FOR_POST_PROCESSING = markPosForPostProcessing();

    private MinecraftVersion() {}

    public static void markPosForPostProcessing(ChunkAccess chunk, BlockPos pos) {
        try {
            MARK_POS_FOR_POST_PROCESSING.invokeExact(chunk, pos);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** The local class, loaded but not initialized: it is Minecraft's own, and its mixin applies. */
    static Class<?> recursiveVisitor() throws ClassNotFoundException {
        return Class.forName(RECURSIVE_VISITOR, false, DensityFunction.class.getClassLoader());
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, DensityFunction.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException older) {
            return false;
        }
    }

    private static MethodHandle markPosForPostProcessing() {
        MethodType type = MethodType.methodType(void.class, BlockPos.class);
        try {
            return MethodHandles.publicLookup().findVirtual(ChunkAccess.class,
                    MINECRAFT_26_2 ? "markPosForPostProcessing" : "markPosForPostprocessing", type);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("ChunkAccess has no markPosForPostProcessing(BlockPos)", failure);
        }
    }
}
