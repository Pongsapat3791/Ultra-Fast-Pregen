package com.example.pregen.terrain;

import com.example.pregen.mixin.RangeChoiceAccessor;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;

/**
 * A range choice's input that the branch it picks reads again, computed once per position.
 * Before 26.2 the overworld's terrain picks between surface and caves by the sloped cheese density
 * and then reads that density again in the branch it picked: the base 3D noise, 24 to 40 octaves,
 * twice at every cell corner. Minecraft 26.2 marks that function cache_once itself. Where a router
 * lacks the mark, a NoiseChunk of this mod's workers wraps a copy that has it, so the second read
 * returns the value of the first: the same double, since the function depends on the position alone.
 * Only for functions of Minecraft's own, and only where the repeated function samples noise (the
 * mark costs more than a gradient).
 */
public final class OnceInputs {
    /** Benchmark switch, on unless -Dfastpregen.onceInputs=false. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("fastpregen.onceInputs", "true"));
    /** Functions marked, over all routers seen; for diagnostics. */
    public static final LongAdder MARKED = new LongAdder();

    /** A world's router and its marked copy, or null where nothing was marked. */
    private record Known(WeakReference<NoiseRouter> source, NoiseRouter marked) {}

    /** The routers seen: few, found by the router itself (a record's equals walks all its functions). */
    private static volatile Known[] known = new Known[0];

    private OnceInputs() {}

    /** The router a NoiseChunk wraps in place of this one (RandomState#router): its marked copy, or itself. */
    public static NoiseRouter of(NoiseRouter router) {
        for (Known entry : known) {
            if (entry.source.get() == router) return entry.marked == null ? router : entry.marked;
        }
        return add(router);
    }

    private static synchronized NoiseRouter add(NoiseRouter router) {
        List<Known> kept = new ArrayList<>();
        for (Known entry : known) {
            NoiseRouter source = entry.source.get();
            if (source == router) return entry.marked == null ? router : entry.marked;
            if (source != null) kept.add(entry);
        }
        NoiseRouter marked = null;
        try {
            marked = mark(router);
        } catch (RuntimeException | LinkageError unknown) {
            // A function that does not map as Minecraft's own do: the router stays as it is.
        }
        kept.add(new Known(new WeakReference<>(router), marked));
        known = kept.toArray(new Known[0]);
        return marked == null ? router : marked;
    }

    /** The router with every repeated range-choice input of its final density marked, or null when there is none. */
    private static NoiseRouter mark(NoiseRouter router) {
        List<DensityFunction> repeated = new ArrayList<>();
        router.finalDensity().mapAll(function -> {
            if (function instanceof RangeChoiceAccessor choice) {
                DensityFunction input = choice.pregen$getInput();
                if (!cached(input) && !repeated.contains(input) && worthCaching(input)
                        && (reads(choice.pregen$getWhenInRange(), input) || reads(choice.pregen$getWhenOutOfRange(), input))) {
                    repeated.add(input);
                }
            }
            return function;
        });
        if (repeated.isEmpty()) return null;
        // Equal functions stay one instance, as RandomState leaves them (a NoiseChunk's wrapping counts on it).
        Map<DensityFunction, DensityFunction> mapped = new HashMap<>();
        NoiseRouter marked = router.mapAll(function -> mapped.computeIfAbsent(function,
                same -> repeated.contains(same) ? DensityFunctions.cacheOnce(same) : same));
        MARKED.add(repeated.size());
        return marked;
    }

    /** Whether the function is a cache of Minecraft's already (directly or by name). */
    private static boolean cached(DensityFunction function) {
        while (function instanceof DensityFunctions.HolderHolder holder) function = holder.function().value();
        return function instanceof DensityFunctions.MarkerOrMarked;
    }

    private static boolean reads(DensityFunction branch, DensityFunction input) {
        boolean[] found = {false};
        branch.mapAll(function -> {
            if (!found[0] && function.getClass() == input.getClass() && function.equals(input)) found[0] = true;
            return function;
        });
        return found[0];
    }

    /** All of Minecraft's own functions, with a noise among them. */
    private static boolean worthCaching(DensityFunction input) {
        boolean[] own = {true};
        boolean[] noise = {false};
        input.mapAll(new DensityFunction.Visitor() {
            @Override
            public DensityFunction apply(DensityFunction function) {
                if (!function.getClass().getName().startsWith("net.minecraft.")) own[0] = false;
                if (function instanceof BlendedNoise) noise[0] = true;
                return function;
            }

            @Override
            public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder holder) {
                noise[0] = true;
                return holder;
            }
        });
        return own[0] && noise[0];
    }
}
