package com.example.pregen;

import com.example.pregen.terrain.AquiferCenterCache;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;

import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent literal-vanilla nearest-center reference and cache invalidation checks. */
public final class AquiferCenterCacheCheck {
    private static final int[][] TIED_CENTERS = {
            {1, 1, 0}, {1, -1, 0}, {-1, 1, 0}, {-1, -1, 0},
            {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1},
            {0, 1, 1}, {0, 1, -1}, {0, -1, 1}, {0, -1, -1}
    };

    /** findNearest must never resolve aquifer statuses; only the air shortcut may. */
    private static final AquiferCenterCache.FluidLevelResolver NO_FLUID_LEVELS = position -> {
        throw new AssertionError("findNearest resolved a fluid level");
    };
    /** DimensionType.WAY_BELOW_MIN_Y: the level vanilla gives aquifers that hold no fluid. */
    private static final int NO_FLUID_LEVEL = -32512;

    private AquiferCenterCacheCheck() {}

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        run();
    }

    public static void run() {
        long queries = checkBoundaries();
        queries += checkTerrainTraversal();
        queries += checkArbitraryTraversal();
        checkEqualDistanceOrder();
        checkResolverReuse();
        long shortcuts = checkAirShortcut();
        checkFluidLevelReuse();
        System.out.println("AQUIFER_CENTER_CHECK queries=" + queries
                + " ties=true cache_reuse=true vanilla_parity=true air_shortcuts_vs_vanilla=" + shortcuts);
    }

    /**
     * Whenever the cache reports a block as above every candidate's fluid, a literal transcription
     * of vanilla computeSubstance (for air density and a non-lava global fluid) must return air,
     * whatever the barrier noise is. Levels include sea level, the no-fluid level and random ones.
     */
    private static long checkAirShortcut() {
        SplittableRandom random = new SplittableRandom(20_261_014);
        long shortcuts = 0;
        long queries = 0;
        for (long seed : new long[]{0, 42, -7046029254386353131L, 79}) {
            AquiferCenterCache.LocationResolver centers = generatedCenters(seed);
            AquiferCenterCache.FluidLevelResolver levels = position -> {
                long hash = scramble(position ^ seed * 0x9e3779b97f4a7c15L);
                return switch (Math.floorMod((int) hash, 4)) {
                    case 0 -> 63;
                    case 1 -> NO_FLUID_LEVEL;
                    default -> Math.floorMod((int) (hash >>> 32), 260) - 64;
                };
            };
            AquiferCenterCache cache = new AquiferCenterCache(centers, levels);
            for (int index = 0; index < 400_000; index++) {
                int x = random.nextInt(-4096, 4096);
                int y = random.nextInt(-54, 321);
                int z = random.nextInt(-4096, 4096);
                double density = index % 7 == 0 ? 0.0 : -random.nextDouble(0.0, 3.0);
                double barrier = random.nextDouble(-8.0, 8.0);
                queries++;
                if (!cache.isAboveAllFluid(x, y, z)) continue;
                shortcuts++;
                if (!vanillaYieldsAir(centers, levels, x, y, z, density, barrier)) {
                    throw new AssertionError("Air shortcut disagrees with vanilla at [" + x + ", " + y + ", " + z
                            + "] density=" + density + " barrier=" + barrier);
                }
            }
        }
        if (shortcuts < queries / 10) {
            throw new AssertionError("Air shortcut check is nearly vacuous: " + shortcuts + "/" + queries);
        }
        return shortcuts;
    }

    // Transcribed from 1.20.1 Aquifer$NoiseBasedAquifer.computeSubstance after its density and
    // global-lava early returns. FluidStatus.at(y) is fluid below its level, air at or above it.
    private static boolean vanillaYieldsAir(AquiferCenterCache.LocationResolver centers,
                                            AquiferCenterCache.FluidLevelResolver levels,
                                            int x, int y, int z, double density, double barrier) {
        Nearest nearest = vanillaNearest(centers, x, y, z);
        int first = levels.get(nearest.firstPosition);
        int second = levels.get(nearest.secondPosition);
        int third = levels.get(nearest.thirdPosition);
        boolean stateIsAir = y >= first;
        double similarity12 = 1.0 - Math.abs(nearest.secondDistance - nearest.firstDistance) / 25.0;
        if (similarity12 <= 0.0) return stateIsAir;
        if (!stateIsAir) return false;
        double pressure12 = similarity12 * vanillaPressure(y, first, second, barrier);
        if (density + pressure12 > 0.0) return false;
        double similarity13 = 1.0 - Math.abs(nearest.thirdDistance - nearest.firstDistance) / 25.0;
        if (similarity13 > 0.0
                && density + similarity12 * similarity13 * vanillaPressure(y, first, third, barrier) > 0.0) {
            return false;
        }
        double similarity23 = 1.0 - Math.abs(nearest.thirdDistance - nearest.secondDistance) / 25.0;
        if (similarity23 > 0.0
                && density + similarity12 * similarity23 * vanillaPressure(y, second, third, barrier) > 0.0) {
            return false;
        }
        return true;
    }

    // calculatePressure as disassembled; both statuses hold air at y in every shortcut case,
    // so the lava/water mixing return of 2.0 cannot apply.
    private static double vanillaPressure(int y, int firstLevel, int secondLevel, double barrier) {
        int difference = Math.abs(firstLevel - secondLevel);
        if (difference == 0) return 0.0;
        double middle = 0.5 * (double) (firstLevel + secondLevel);
        double offset = (double) y + 0.5 - middle;
        double half = (double) difference / 2.0;
        double distance = half - Math.abs(offset);
        double q;
        if (offset > 0.0) {
            double p = 0.0 + distance;
            q = p > 0.0 ? p / 1.5 : p / 2.5;
        } else {
            double p = 3.0 + distance;
            q = p > 0.0 ? p / 3.0 : p / 10.0;
        }
        double r = q < -2.0 || q > 2.0 ? 0.0 : barrier;
        return 2.0 * (r + q);
    }

    private static void checkFluidLevelReuse() {
        AtomicInteger levelCalls = new AtomicInteger();
        AquiferCenterCache cache = new AquiferCenterCache(generatedCenters(5), position -> {
            levelCalls.incrementAndGet();
            return 63;
        });
        cache.findNearest(5, 100, 5);
        requireCalls(levelCalls, 0, "findNearest alone");
        cache.isAboveAllFluid(5, 100, 5);
        requireCalls(levelCalls, 12, "first shortcut test in a cell");
        // Grid Y is floorDiv(y + 1, 12): y 95..106 share a cell, y 107 starts the next one.
        cache.isAboveAllFluid(20, 106, 20);
        cache.findNearest(20, 106, 20);
        requireCalls(levelCalls, 12, "same cell reuses its maximum level");
        cache.isAboveAllFluid(5, 107, 5);
        requireCalls(levelCalls, 24, "new Y cell recomputes its maximum level");
    }

    private static long checkBoundaries() {
        int[] origins = {0, -257 * 16, 512 * 16, -1024 * 16, 29_999_984, -30_000_000};
        int[] offsets = {-1, 0, 4, 5, 15, 16, 20, 21};
        int[] ys = {-65, -64, -25, -24, -13, -12, -2, -1, 0, 10, 11, 12, 22, 23, 24, 318, 319, 320};
        long queries = 0;
        for (long seed : new long[]{0, 42, -7046029254386353131L}) {
            AquiferCenterCache.LocationResolver resolver = generatedCenters(seed);
            AquiferCenterCache cache = new AquiferCenterCache(resolver, NO_FLUID_LEVELS);
            for (int origin : origins) {
                for (int dx : offsets) {
                    for (int y : ys) {
                        for (int dz : offsets) {
                            assertMatches(cache, resolver, origin + dx, y, -origin + dz);
                            queries++;
                        }
                    }
                }
            }
        }
        return queries;
    }

    private static long checkTerrainTraversal() {
        int[][] chunkPositions = {{0, 0}, {-257, -257}, {512, -513}, {-1024, 768}};
        long queries = 0;
        for (int[] position : chunkPositions) {
            AquiferCenterCache.LocationResolver resolver = generatedCenters(position[0] * 31L + position[1]);
            AquiferCenterCache cache = new AquiferCenterCache(resolver, NO_FLUID_LEVELS);
            // Match doFill's cell and intra-cell traversal, including descending Y.
            for (int cellX = 0; cellX < 4; cellX++) {
                for (int cellZ = 0; cellZ < 4; cellZ++) {
                    for (int cellY = 47; cellY >= 0; cellY--) {
                        for (int inY = 7; inY >= 0; inY--) {
                            for (int inX = 0; inX < 4; inX++) {
                                for (int inZ = 0; inZ < 4; inZ++) {
                                    int x = position[0] * 16 + cellX * 4 + inX;
                                    int y = -64 + cellY * 8 + inY;
                                    int z = position[1] * 16 + cellZ * 4 + inZ;
                                    assertMatches(cache, resolver, x, y, z);
                                    queries++;
                                }
                            }
                        }
                    }
                }
            }
        }
        return queries;
    }

    private static long checkArbitraryTraversal() {
        SplittableRandom random = new SplittableRandom(783_921);
        AquiferCenterCache.LocationResolver resolver = generatedCenters(Long.MIN_VALUE);
        AquiferCenterCache cache = new AquiferCenterCache(resolver, NO_FLUID_LEVELS);
        for (int index = 0; index < 10_000; index++) {
            int x = random.nextInt(-30_000_000, 30_000_001);
            int y = random.nextInt(-1024, 1024);
            int z = random.nextInt(-30_000_000, 30_000_001);
            assertMatches(cache, resolver, x, y, z);
        }
        // Signed packing and integer overflow must follow vanilla even outside playable bounds.
        int[][] extremes = {
                {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE},
                {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE},
                {Integer.MIN_VALUE + 4, -2049, Integer.MAX_VALUE - 4},
                {33_554_431, 2047, -33_554_432},
                {-33_554_432, -2048, 33_554_431}
        };
        for (int[] position : extremes) {
            assertMatches(cache, resolver, position[0], position[1], position[2]);
        }
        return 10_000 + extremes.length;
    }

    private static void checkEqualDistanceOrder() {
        for (int fixedRanks = 0; fixedRanks <= 2; fixedRanks++) {
            final int fixed = fixedRanks;
            AquiferCenterCache.LocationResolver resolver = (gridX, gridY, gridZ) -> {
                int index = ((gridX + 1) * 3 + gridY + 1) * 2 + gridZ + 1;
                if (fixed >= 1 && index == 0) return BlockPos.asLong(0, 0, 0);
                if (fixed >= 2 && index == 1) return BlockPos.asLong(1, 0, 0);
                int[] center = TIED_CENTERS[index];
                return BlockPos.asLong(center[0], center[1], center[2]);
            };
            AquiferCenterCache cache = new AquiferCenterCache(resolver, NO_FLUID_LEVELS);
            assertMatches(cache, resolver, 0, 0, 0);
            int[] last = TIED_CENTERS[11];
            long lastPosition = BlockPos.asLong(last[0], last[1], last[2]);
            long rankedLast = fixed == 0 ? cache.firstPosition
                    : fixed == 1 ? cache.secondPosition : cache.thirdPosition;
            if (rankedLast != lastPosition) {
                throw new AssertionError("Later equal-distance center lost at rank " + (fixed + 1));
            }
        }
    }

    private static void checkResolverReuse() {
        AtomicInteger calls = new AtomicInteger();
        AquiferCenterCache.LocationResolver delegate = generatedCenters(79);
        AquiferCenterCache cache = new AquiferCenterCache((x, y, z) -> {
            calls.incrementAndGet();
            return delegate.get(x, y, z);
        }, NO_FLUID_LEVELS);
        cache.findNearest(5, -1, 5);
        requireCalls(calls, 12, "cold grid cell");
        cache.findNearest(20, 10, 20);
        requireCalls(calls, 12, "same grid key, different block");
        cache.findNearest(21, -1, 5);
        requireCalls(calls, 24, "second horizontal parity");
        cache.findNearest(5, -1, 21);
        requireCalls(calls, 36, "third horizontal parity");
        cache.findNearest(21, -1, 21);
        requireCalls(calls, 48, "fourth horizontal parity");
        cache.findNearest(5, -1, 5);
        requireCalls(calls, 48, "all four slots retained");
        cache.findNearest(37, -1, 5);
        requireCalls(calls, 60, "same parity, different exact X key");
        cache.findNearest(5, -1, 5);
        requireCalls(calls, 72, "return to evicted X key");
        cache.findNearest(5, 11, 5);
        requireCalls(calls, 84, "Y-only grid change");
        cache.findNearest(20, 22, 20);
        requireCalls(calls, 84, "same updated Y key");
        cache.findNearest(5, -1, 37);
        requireCalls(calls, 96, "same parity, different exact Z key");
        cache.findNearest(-11, -1, -11);
        requireCalls(calls, 108, "negative grid key");
        cache.findNearest(-12, -2, -12);
        requireCalls(calls, 120, "negative floor division across boundaries");
    }

    private static void requireCalls(AtomicInteger actual, int expected, String description) {
        if (actual.get() != expected) {
            throw new AssertionError(description + ": expected " + expected + " resolver calls, got " + actual.get());
        }
    }

    private static AquiferCenterCache.LocationResolver generatedCenters(long seed) {
        return (x, y, z) -> {
            long hash = scramble(seed ^ x * 0x9e3779b97f4a7c15L ^ y * 0xc2b2ae3d27d4eb4fL ^ z * 0x165667b19e3779f9L);
            int dx = Math.floorMod((int) hash, 10);
            int dy = Math.floorMod((int) (hash >>> 21), 9);
            int dz = Math.floorMod((int) (hash >>> 42), 10);
            return BlockPos.asLong(x * 16 + dx, y * 12 + dy, z * 16 + dz);
        };
    }

    private static long scramble(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static void assertMatches(AquiferCenterCache cache, AquiferCenterCache.LocationResolver resolver,
                                      int x, int y, int z) {
        Nearest expected = vanillaNearest(resolver, x, y, z);
        cache.findNearest(x, y, z);
        if (cache.firstPosition != expected.firstPosition || cache.secondPosition != expected.secondPosition
                || cache.thirdPosition != expected.thirdPosition || cache.firstDistance != expected.firstDistance
                || cache.secondDistance != expected.secondDistance || cache.thirdDistance != expected.thirdDistance) {
            throw new AssertionError("Nearest-center mismatch at [" + x + ", " + y + ", " + z + "] expected "
                    + expected + " but got " + new Nearest(cache.firstDistance, cache.secondDistance,
                    cache.thirdDistance, cache.firstPosition, cache.secondPosition, cache.thirdPosition));
        }
    }

    // Deliberately retain the literal nested vanilla traversal and per-center decoding.
    private static Nearest vanillaNearest(AquiferCenterCache.LocationResolver resolver, int i, int j, int k) {
        int l = Math.floorDiv(i - 5, 16);
        int m = Math.floorDiv(j + 1, 12);
        int n = Math.floorDiv(k - 5, 16);
        int o = Integer.MAX_VALUE;
        int p = Integer.MAX_VALUE;
        int q = Integer.MAX_VALUE;
        long r = 0;
        long s = 0;
        long t = 0;
        for (int u = 0; u <= 1; u++) {
            for (int v = -1; v <= 1; v++) {
                for (int w = 0; w <= 1; w++) {
                    long center = resolver.get(l + u, m + v, n + w);
                    int dx = BlockPos.getX(center) - i;
                    int dy = BlockPos.getY(center) - j;
                    int dz = BlockPos.getZ(center) - k;
                    int distance = dx * dx + dy * dy + dz * dz;
                    if (o >= distance) {
                        t = s;
                        s = r;
                        r = center;
                        q = p;
                        p = o;
                        o = distance;
                    } else if (p >= distance) {
                        t = s;
                        s = center;
                        q = p;
                        p = distance;
                    } else if (q >= distance) {
                        t = center;
                        q = distance;
                    }
                }
            }
        }
        return new Nearest(o, p, q, r, s, t);
    }

    private record Nearest(int firstDistance, int secondDistance, int thirdDistance,
                           long firstPosition, long secondPosition, long thirdPosition) {}
}
