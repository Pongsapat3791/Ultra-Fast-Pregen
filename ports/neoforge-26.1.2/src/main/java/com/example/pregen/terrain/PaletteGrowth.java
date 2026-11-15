package com.example.pregen.terrain;

import java.util.concurrent.atomic.LongAdder;

/**
 * A block or biome container growing from one value to a palette. Minecraft then copies its
 * 4,096 (or 64) entries one by one from the old storage, which holds nothing but that value:
 * every entry gets the id the new palette gives the value, the palette's first, 0. So the new
 * storage is all zeros, which is what it is cleared to instead (PalettedDataMixin). Every section
 * of every chunk grows this way at its first block: about 2% of generation time.
 */
public final class PaletteGrowth {
    /** Benchmark switch, on unless -Dfastpregen.paletteGrowth=false. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("fastpregen.paletteGrowth", "true"));
    /** Containers grown without the copy. */
    public static final LongAdder CLEARED = new LongAdder();

    private PaletteGrowth() {}
}
