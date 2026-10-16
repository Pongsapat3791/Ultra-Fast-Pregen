package com.example.pregen.terrain;

import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * Surface rules ask for the biome at the current block lazily, at most once per block. Vanilla
 * allocates a capturing lambda and a memoizing supplier for every block it visits; this is the
 * same lazy lookup, reset per block: the first get() sets the shared position and calls the
 * biome getter, later calls return that biome, exactly like Suppliers.memoize.
 */
public final class ReusableBiomeSupplier implements com.google.common.base.Supplier<Holder<Biome>> {
    /** Benchmark switch; on by default. */
    public static volatile boolean enabled = true;

    private final Function<BlockPos, Holder<Biome>> biomeGetter;
    private final BlockPos.MutableBlockPos pos;
    private int x;
    private int y;
    private int z;
    private Holder<Biome> value;
    private boolean computed;

    public ReusableBiomeSupplier(Function<BlockPos, Holder<Biome>> biomeGetter, BlockPos.MutableBlockPos pos) {
        this.biomeGetter = biomeGetter;
        this.pos = pos;
    }

    public void reset(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.value = null;
        this.computed = false;
    }

    @Override
    public Holder<Biome> get() {
        if (!computed) {
            value = biomeGetter.apply(pos.set(x, y, z));
            computed = true;
        }
        return value;
    }
}
