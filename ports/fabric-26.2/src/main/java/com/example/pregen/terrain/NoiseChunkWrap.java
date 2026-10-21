package com.example.pregen.terrain;

import com.example.pregen.mixin.ShiftedNoiseAccessor;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/**
 * Building a NoiseChunk's copy of the noise router with less work (about a tenth of all
 * generation time goes into it). Minecraft rebuilds the router node by node for every chunk,
 * putting each node into a HashMap keyed by its value (a record's hash covers its whole subtree),
 * and rebuilds a named function or spline again wherever it is referenced: the overworld's
 * terrain splines each read the same few coordinates at every point.
 *  - The map matters only for nodes it turns into the chunk's caches: markers, and the climate
 *    noises this mod caches per column (see NoiseChunkMixin), so that equal ones share one cache.
 *    Every other node of Minecraft's own comes back as itself (or a holder as its value), so an
 *    equal node found in the map instead is the same function over the same caches: these skip it.
 *  - Within one chunk, a named function and a spline come out of the chunk's wrapping the same
 *    every time (the same nodes over the same caches), so mapAll's walk keeps the first result
 *    and gives it again (Memo, see RecursiveVisitorMixin).
 */
public final class NoiseChunkWrap {
    /** Benchmark switch, on unless -Dfastpregen.noiseChunkWrap=false. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("fastpregen.noiseChunkWrap", "true"));
    private static final Class<?> MARKER = DensityFunctions.flatCache(DensityFunctions.zero()).getClass();

    private NoiseChunkWrap() {}

    /** Whether wrap may skip the map: a function of Minecraft's own that the chunk does not cache. */
    public static boolean bypassesLookup(DensityFunction function) {
        return function.getClass() != MARKER && !(function instanceof ShiftedNoiseAccessor)
                && function.getClass().getName().startsWith("net.minecraft.");
    }

    /** Whether mapAll's walk keeps what the node maps to: named functions and splines. */
    public static boolean memoized(DensityFunction function) {
        return function instanceof DensityFunctions.HolderHolder || function instanceof DensityFunctions.Spline;
    }

    /** What an earlier mapAll of the node gave with this chunk's wrapping, or null. */
    public static Object known(DensityFunction.Visitor visitor, Object node) {
        return visitor instanceof Memo memo ? memo.results.get(node) : null;
    }

    /** Keeps what mapAll of the node gave, for the rest of this chunk's wrapping. */
    public static void remember(DensityFunction.Visitor visitor, Object node, Object result) {
        if (visitor instanceof Memo memo) memo.results.put(node, result);
    }

    /** One NoiseChunk's wrapping (its wrap method), with the results of shared nodes kept. */
    public static final class Memo implements DensityFunction.Visitor {
        private final DensityFunction.Visitor wrap;
        private final Map<Object, Object> results = new IdentityHashMap<>();

        public Memo(DensityFunction.Visitor wrap) {
            this.wrap = wrap;
        }

        @Override
        public DensityFunction apply(DensityFunction function) {
            return wrap.apply(function);
        }

        @Override
        public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noise) {
            return wrap.visitNoise(noise);
        }
    }
}
