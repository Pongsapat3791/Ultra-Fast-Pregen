package com.example.pregen.terrain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * The density at the cell corners on chunk edges, computed once for the chunks that share them.
 * A chunk interpolates its terrain between 5x5 columns of cell corners; the 16 on its edges are
 * corner columns of one or three neighbors too, which compute them again: a third of all corner
 * work, itself about a quarter of generation time. Where a chunk adds nothing of its own to those
 * values (no blending with older chunks, no structure pieces nearby) a corner column depends only
 * on its position, so the first chunk of a terrain stage to compute it leaves it here for the
 * others (see NoiseChunkMixin), which copy it instead. Only for a noise router of Minecraft's own
 * functions: one added by a mod could read the chunk it computes for.
 */
public final class SharedCorners {
    /** Benchmark switch, on unless -Dfastpregen.sharedCorners=false. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("fastpregen.sharedCorners", "true"));
    /** Corner columns copied from a neighbor (the regression checks that the shortcut ran). */
    public static final LongAdder COPIED = new LongAdder();
    /** The terrain stages running, by their level's noise (RandomState keeps identity equality). */
    private static final Map<RandomState, SharedCorners> OPEN = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Long, Column> columns = new ConcurrentHashMap<>();

    private SharedCorners() {}

    /** One corner column: each interpolated function's values up the column, and the chunks yet to read it. */
    private static final class Column {
        final AtomicReferenceArray<double[]> values;
        final AtomicInteger readers;

        Column(int functions, int sharers) {
            values = new AtomicReferenceArray<>(functions);
            readers = new AtomicInteger(sharers);
        }
    }

    /** Shares corners among the chunks of a terrain stage of the level with this noise, until close. */
    public static void open(RandomState state) {
        if (enabled && minecraftsOwn(state.router())) OPEN.put(state, new SharedCorners());
    }

    private static boolean minecraftsOwn(NoiseRouter router) {
        boolean[] own = {true};
        router.mapAll(function -> {
            if (!function.getClass().getName().startsWith("net.minecraft.")) own[0] = false;
            return function;
        });
        return own[0];
    }

    public static void close(RandomState state) {
        OPEN.remove(state);
    }

    /** The corners of the stage running for this noise, or null. */
    public static SharedCorners of(RandomState state) {
        return OPEN.get(state);
    }

    /**
     * Fills {@code column} with the values of interpolated function {@code index} (of
     * {@code functions}) at the corner column (x, z), which {@code sharers} chunks have: from the
     * chunk that computed it first, or by {@code compute}.
     */
    public void fill(int x, int z, int index, int functions, int sharers, double[] column, Runnable compute) {
        long key = (long) x << 32 | z & 0xFFFFFFFFL;
        Column entry = columns.get(key);
        double[] known = entry != null && entry.values.length() == functions ? entry.values.get(index) : null;
        if (known != null && known.length == column.length) {
            System.arraycopy(known, 0, column, 0, column.length);
            COPIED.increment();
        } else {
            compute.run();
            if (entry == null) entry = columns.computeIfAbsent(key, ignored -> new Column(functions, sharers));
            if (entry.values.length() == functions) entry.values.compareAndSet(index, null, column.clone());
        }
        // After a chunk's last function here, one chunk fewer will read the column.
        if (index == functions - 1 && entry.readers.decrementAndGet() == 0) columns.remove(key, entry);
    }
}
