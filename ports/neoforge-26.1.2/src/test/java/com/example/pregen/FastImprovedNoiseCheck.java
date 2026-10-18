package com.example.pregen;

import com.example.pregen.terrain.FastImprovedNoise;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Bit-exact reference checks against the unmodified Minecraft sampler. */
public final class FastImprovedNoiseCheck {
    private static final long[] SEEDS = {0, 1, -1, 42, Long.MIN_VALUE, Long.MAX_VALUE, 0x9e3779b97f4a7c15L};
    private static final double[] COORDINATES = {
            -33_554_432.0, -30_000_000.125, -1_024.5, -257, -256, -1,
            -0.0, 0.0, Double.MIN_VALUE, 0.125, 0.5, 1, 255, 256, 257,
            30_000_000.125, 33_554_432.0
    };
    private static final int[] CELL_BOUNDARIES = {-257, -256, -1, 0, 1, 255, 256, 257};
    private static final double[] Y_SCALES = {-2.0, -0.5, -1.0e-7, -Double.MIN_VALUE,
            -0.0, 0.0, Double.MIN_VALUE, 1.0e-7, 0.125, 0.5, 1.0, 2.0};
    private static final Field PERMUTATION = permutationField();

    private FastImprovedNoiseCheck() {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        run();
    }

    public static void run() throws Exception {
        long cases = 0;
        long parallelCases = 0;
        ExecutorService workers = Executors.newFixedThreadPool(4, task -> {
            Thread thread = new Thread(task, "ImprovedNoiseParity");
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (boolean xoroshiro : new boolean[]{false, true}) {
                for (long seed : SEEDS) {
                    RandomSource random = xoroshiro ? new XoroshiroRandomSource(seed) : new LegacyRandomSource(seed);
                    ImprovedNoise original = new ImprovedNoise(random);
                    byte[] constructorInput = ((byte[]) PERMUTATION.get(original)).clone();
                    FastImprovedNoise fast = new FastImprovedNoise(constructorInput, original.xo, original.yo, original.zo);
                    // A derived immutable table must not retain a caller-owned mutable permutation.
                    Arrays.fill(constructorInput, (byte) 0);
                    String identity = (xoroshiro ? "xoroshiro" : "legacy") + " seed=" + seed;
                    cases += checkCartesian(original, fast, identity);
                    cases += checkCellBoundaries(original, fast, identity);
                    cases += checkYQuantization(original, fast, identity);
                    cases += checkRandomAndExtreme(original, fast, identity, seed);
                    parallelCases += checkSharedSampling(original, fast, identity, workers);
                }
            }
        } finally {
            workers.shutdownNow();
        }
        System.out.println("FAST_IMPROVED_NOISE_CHECK cases=" + cases + " shared_parallel_cases=" + parallelCases
                + " prngs=2 seeds_per_prng=" + SEEDS.length
                + " cell_boundaries=true y_quantization=true large_finite=true immutable=true raw_bits_parity=true");
    }

    private static long checkCartesian(ImprovedNoise original, FastImprovedNoise fast, String identity) {
        long cases = 0;
        for (double x : COORDINATES) {
            for (double y : COORDINATES) {
                for (double z : COORDINATES) {
                    assertSame(original, fast, identity, x, y, z, 0.0, 0.0);
                    cases++;
                }
            }
        }
        return cases;
    }

    private static long checkCellBoundaries(ImprovedNoise original, FastImprovedNoise fast, String identity) {
        long cases = 0;
        for (int cellX : CELL_BOUNDARIES) {
            double[] xs = adjacent(cellX - original.xo);
            for (int cellY : CELL_BOUNDARIES) {
                double[] ys = adjacent(cellY - original.yo);
                for (int cellZ : CELL_BOUNDARIES) {
                    double[] zs = adjacent(cellZ - original.zo);
                    for (double x : xs) {
                        for (double y : ys) {
                            for (double z : zs) {
                                assertSame(original, fast, identity, x, y, z, 0.0, 0.0);
                                cases++;
                            }
                        }
                    }
                }
            }
        }
        return cases;
    }

    private static long checkYQuantization(ImprovedNoise original, FastImprovedNoise fast, String identity) {
        long cases = 0;
        for (int cellY : new int[]{-257, -1, 0, 1, 256}) {
            for (double fraction : new double[]{0.0, Double.MIN_VALUE, 1.0e-8, 0.125, 0.5,
                    Math.nextDown(1.0), 1.0}) {
                double y = cellY + fraction - original.yo;
                double shiftedY = y + original.yo;
                double remainder = shiftedY - Mth.floor(shiftedY);
                double[] yMaxValues = {-1.0, -0.0, 0.0, Math.nextDown(remainder), remainder * 0.5,
                        remainder, Math.nextUp(remainder), remainder + 1.0, 2.0};
                for (double x : new double[]{-257.25, 0.0, 255.75}) {
                    for (double z : new double[]{-255.75, 0.0, 257.25}) {
                        for (double yScale : Y_SCALES) {
                            for (double yMax : yMaxValues) {
                                assertSame(original, fast, identity, x, y, z, yScale, yMax);
                                cases++;
                            }
                        }
                    }
                }
            }
        }
        return cases;
    }

    private static long checkRandomAndExtreme(ImprovedNoise original, FastImprovedNoise fast,
                                             String identity, long seed) {
        SplittableRandom random = new SplittableRandom(seed ^ 0xc2b2ae3d27d4eb4fL);
        long cases = 0;
        for (int index = 0; index < 4_096; index++) {
            double scale = switch (index % 3) {
                case 0 -> 1_000.0;
                case 1 -> 30_000_000.0;
                default -> 1.0e12;
            };
            double x = random.nextDouble(-scale, scale);
            double y = random.nextDouble(-scale, scale);
            double z = random.nextDouble(-scale, scale);
            double yScale = Y_SCALES[index % Y_SCALES.length];
            double yMax = random.nextDouble(-1.0, 2.0);
            assertSame(original, fast, identity, x, y, z, yScale, yMax);
            cases++;
        }
        // Exercise the same saturating/overflowing integer-floor behavior as vanilla.
        double[] extremes = {Integer.MIN_VALUE - 1.0, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE + 1.0, -1.0e12, 1.0e12, -1.0e100, 1.0e100,
                -Double.MAX_VALUE, Double.MAX_VALUE};
        for (double x : extremes) {
            for (double y : extremes) {
                for (double z : extremes) {
                    assertSame(original, fast, identity, x, y, z, 0.0, 0.0);
                    assertSame(original, fast, identity, x, y, z, 0.5, 0.25);
                    cases += 2;
                }
            }
        }
        return cases;
    }

    private static long checkSharedSampling(ImprovedNoise original, FastImprovedNoise fast,
                                            String identity, ExecutorService workers) throws Exception {
        final int casesPerThread = 4_096;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int threadIndex = 0; threadIndex < 4; threadIndex++) {
            final int offset = threadIndex;
            futures.add(workers.submit(() -> {
                start.await();
                for (int index = 0; index < casesPerThread; index++) {
                    double x = (index * 31L + offset * 17) % 1_024 - 512.25;
                    double y = (index * 7L + offset * 13) % 384 - 64.5;
                    double z = (index * 19L + offset * 23) % 1_024 - 512.75;
                    assertSame(original, fast, identity, x, y, z, Y_SCALES[index % Y_SCALES.length],
                            (index % 5 - 1) * 0.25);
                }
                return (long) casesPerThread;
            }));
        }
        start.countDown();
        long cases = 0;
        for (Future<Long> future : futures) cases += future.get();
        return cases;
    }

    private static double[] adjacent(double value) {
        return new double[]{Math.nextDown(value), value, Math.nextUp(value)};
    }

    @SuppressWarnings("deprecation")
    private static void assertSame(ImprovedNoise original, FastImprovedNoise fast, String identity,
                                   double x, double y, double z, double yScale, double yMax) {
        double expected = original.noise(x, y, z, yScale, yMax);
        double actual = fast.noise(x, y, z, yScale, yMax);
        long expectedBits = Double.doubleToRawLongBits(expected);
        long actualBits = Double.doubleToRawLongBits(actual);
        if (expectedBits != actualBits) {
            throw new AssertionError("Noise mismatch " + identity + " at [" + x + ", " + y + ", " + z
                    + "] yScale=" + yScale + " yMax=" + yMax + " expected=" + expected
                    + " (0x" + Long.toHexString(expectedBits) + ") actual=" + actual
                    + " (0x" + Long.toHexString(actualBits) + ")");
        }
    }

    private static Field permutationField() {
        try {
            Field field = ImprovedNoise.class.getDeclaredField("p");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
