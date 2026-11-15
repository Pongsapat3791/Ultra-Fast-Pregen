package com.example.pregen.terrain;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * A column's preliminary surface level, computed once for all the chunks that ask for it.
 * Minecraft 26.x's aquifer asks for it at every fourth block of an area about 54 blocks wide when
 * a NoiseChunk is made (it wants the highest), each answer a walk down the column's density; the
 * fill and the surface step ask again. A NoiseChunk keeps the answers for itself only, so its
 * neighbors, and every one-column NoiseChunk a structure makes to ask for a height, walk the same
 * columns again: about 4% of generation time, and most of what a slow structure start does.
 * The level is asked at quart-aligned columns and depends on the column alone: the 2D functions
 * under it come out of a chunk's flat cache (kept at exactly those columns) as they do computed
 * outside it. So the first to compute a column leaves its level here for the others. Only for a
 * noise router of Minecraft's own functions (see SharedCorners), while a grid of this mod runs, and
 * only among chunks that blend no older chunks in (blending changes the terrain shape under the level).
 */
public final class SharedSurfaceLevels {
    /** Benchmark switch, on unless -Dfastpregen.sharedSurfaceLevels=false. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("fastpregen.sharedSurfaceLevels", "true"));
    /** Levels taken from another NoiseChunk's computation. */
    public static final LongAdder TAKEN = new LongAdder();
    /** Levels kept at most: the columns of several batches. */
    private static final int LIMIT = 1 << 19;
    /** The worlds a grid runs in: few, found by their router itself (a record's equals walks all its functions). */
    private static volatile SharedSurfaceLevels[] open = new SharedSurfaceLevels[0];

    private final NoiseRouter router;
    private final ConcurrentHashMap<Long, Integer> levels = new ConcurrentHashMap<>(1 << 14);
    private int users;

    private SharedSurfaceLevels(NoiseRouter router) {
        this.router = router;
    }

    /** For a grid in the world with this noise, until close; null where levels are not shared. */
    public static synchronized SharedSurfaceLevels open(RandomState state) {
        NoiseRouter router = state.router();
        if (!enabled || !SharedCorners.minecraftsOwn(router)) return null;
        for (SharedSurfaceLevels shared : open) {
            if (shared.router == router) {
                shared.users++;
                return shared;
            }
        }
        SharedSurfaceLevels shared = new SharedSurfaceLevels(router);
        shared.users = 1;
        SharedSurfaceLevels[] grown = Arrays.copyOf(open, open.length + 1);
        grown[open.length] = shared;
        open = grown;
        return shared;
    }

    /** The grid that opened these levels is done; they go when no grid of that world is left. */
    public static synchronized void close(SharedSurfaceLevels shared) {
        if (shared == null || --shared.users > 0) return;
        SharedSurfaceLevels[] now = open;
        for (int i = 0; i < now.length; i++) {
            if (now[i] != shared) continue;
            SharedSurfaceLevels[] fewer = new SharedSurfaceLevels[now.length - 1];
            System.arraycopy(now, 0, fewer, 0, i);
            System.arraycopy(now, i + 1, fewer, i, now.length - 1 - i);
            open = fewer;
            return;
        }
    }

    /** The levels shared in the world of this router (RandomState#router, not a chunk's wrapped copy), or null. */
    public static SharedSurfaceLevels of(NoiseRouter router) {
        for (SharedSurfaceLevels shared : open) {
            if (shared.router == router) return shared;
        }
        return null;
    }

    /** The level of the column (NoiseChunk's packed key), or null when nobody computed it yet. */
    public Integer known(long column) {
        Integer level = levels.get(column);
        if (level != null) TAKEN.increment();
        return level;
    }

    public void remember(long column, int level) {
        if (levels.size() >= LIMIT) levels.clear();
        levels.putIfAbsent(column, level);
    }
}
