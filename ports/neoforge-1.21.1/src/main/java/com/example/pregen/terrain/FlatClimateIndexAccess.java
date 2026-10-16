package com.example.pregen.terrain;

import net.minecraft.world.level.biome.Climate;

/** For the regression check: the warm-start ThreadLocal of a parameter list's vanilla R-tree. */
public final class FlatClimateIndexAccess {
    private FlatClimateIndexAccess() {}

    public static ThreadLocal<Object> lastResult(Climate.ParameterList<?> list) throws ReflectiveOperationException {
        return FlatClimateIndex.lastResultOf(FlatClimateIndex.treeOf(list));
    }
}
