package com.example.pregen;

import com.example.pregen.terrain.FlatClimateIndex;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Random;
import net.minecraft.world.level.biome.Climate;

/**
 * The flat biome R-tree must return vanilla's leaf for every query and leave the same warm
 * start behind, including targets exactly on interval bounds, where distances tie at zero.
 */
public final class FlatClimateIndexCheck {
    private FlatClimateIndexCheck() {}

    @SuppressWarnings("unchecked")
    public static void run(Climate.ParameterList<?> list) throws ReflectiveOperationException {
        FlatClimateIndex flat = FlatClimateIndex.of(list);
        require(flat != null, "The flat index must understand vanilla's tree");
        ThreadLocal<Object> lastResult = com.example.pregen.terrain.FlatClimateIndexAccess.lastResult(list);
        List<? extends com.mojang.datafixers.util.Pair<Climate.ParameterPoint, ?>> values = list.values();
        Random random = new Random(12345);
        int queries = 0;
        int boundaryQueries = 0;
        for (int i = 0; i < 300_000; i++) {
            Climate.TargetPoint target;
            if (i % 3 == 0) {
                // A point on some leaf's interval bounds, where several leaves can be at distance zero.
                Climate.ParameterPoint point = values.get(random.nextInt(values.size())).getFirst();
                Climate.Parameter[] space = {point.temperature(), point.humidity(), point.continentalness(),
                        point.erosion(), point.depth(), point.weirdness()};
                long[] coords = new long[6];
                for (int p = 0; p < 6; p++) {
                    Climate.Parameter parameter = space[p];
                    coords[p] = random.nextBoolean() ? parameter.min() : parameter.max();
                }
                target = new Climate.TargetPoint(coords[0], coords[1], coords[2], coords[3], coords[4], coords[5]);
                boundaryQueries++;
            } else {
                target = new Climate.TargetPoint(coord(random), coord(random), coord(random), coord(random),
                        coord(random), coord(random));
            }
            Object warm = lastResult.get();
            Object vanilla = list.findValueIndex(target);
            Object vanillaLast = lastResult.get();
            lastResult.set(warm);
            Object fast = flat.findValue(target);
            Object fastLast = lastResult.get();
            require(vanilla == fast, "Flat R-tree returned another biome for " + target);
            require(vanillaLast == fastLast, "Flat R-tree left another warm start for " + target);
            // Keep both paths on the same warm start sequence.
            lastResult.set(vanillaLast);
            queries++;
        }
        System.out.println("FLAT_CLIMATE_INDEX_CHECK PASS queries=" + queries + " boundary_queries=" + boundaryQueries
                + " leaves=" + values.size());
    }

    private static long coord(Random random) {
        return Climate.quantizeCoord((float) (random.nextDouble() * 4.0 - 2.0));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
