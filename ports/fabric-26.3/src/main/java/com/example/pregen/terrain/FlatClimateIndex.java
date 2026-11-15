package com.example.pregen.terrain;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.biome.Climate;

/**
 * Minecraft's biome R-tree (Climate.RTree) with each node's seven parameter intervals laid out in
 * one long[] instead of seven Parameter objects. The search is vanilla's SubTree.search step for
 * step: the same child order, distances and comparisons, and the same per-thread warm start,
 * read from and written back to the tree's own lastResult. So it returns the same leaf as
 * vanilla in every case, ties included, and leaves the same state behind.
 */
public final class FlatClimateIndex {
    // treeOf and lastResultOf are package-visible for the regression check.
    private static final int PARAMETERS = 7;

    private final long[] bounds;
    private final int[] childStart;
    private final int[] childEnd;
    private final int[] children;
    private final Object[] leaves;
    private final Object[] values;
    private final Map<Object, Integer> leafIndex = new IdentityHashMap<>();
    private final ThreadLocal<Object> lastResult;

    private FlatClimateIndex(Object root, ThreadLocal<Object> lastResult) throws ReflectiveOperationException {
        this.lastResult = lastResult;
        List<Object> nodes = new ArrayList<>();
        Map<Object, Integer> index = new IdentityHashMap<>();
        collect(root, nodes, index);
        int count = nodes.size();
        bounds = new long[count * PARAMETERS * 2];
        childStart = new int[count];
        childEnd = new int[count];
        leaves = new Object[count];
        values = new Object[count];
        List<Integer> flatChildren = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Object node = nodes.get(i);
            Climate.Parameter[] space = (Climate.Parameter[]) field(node, Climate.Parameter[].class).get(node);
            if (space.length != PARAMETERS) throw new IllegalStateException("Expected 7 climate parameters");
            for (int p = 0; p < PARAMETERS; p++) {
                bounds[(i * PARAMETERS + p) * 2] = space[p].min();
                bounds[(i * PARAMETERS + p) * 2 + 1] = space[p].max();
            }
            Object[] nodeChildren = childrenOf(node);
            childStart[i] = flatChildren.size();
            if (nodeChildren == null) {
                leaves[i] = node;
                values[i] = valueOf(node);
                leafIndex.put(node, i);
            } else {
                for (Object child : nodeChildren) flatChildren.add(index.get(child));
            }
            childEnd[i] = flatChildren.size();
        }
        children = flatChildren.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The flat copy of a parameter list's tree, or null if the tree's layout is unexpected. */
    public static FlatClimateIndex of(Climate.ParameterList<?> list) {
        try {
            Object tree = treeOf(list);
            ThreadLocal<Object> lastResult = lastResultOf(tree);
            Object root = null;
            for (Field field : tree.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() == ThreadLocal.class) continue;
                field.setAccessible(true);
                root = field.get(tree);
            }
            return root == null ? null : new FlatClimateIndex(root, lastResult);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    public Object findValue(Climate.TargetPoint target) {
        long t0 = target.temperature();
        long t1 = target.humidity();
        long t2 = target.continentalness();
        long t3 = target.erosion();
        long t4 = target.depth();
        long t5 = target.weirdness();
        Object last = lastResult.get();
        Integer lastIndex = last == null ? null : leafIndex.get(last);
        if (last != null && lastIndex == null) {
            // Not a leaf of this tree; vanilla would still start from it, so defer to it.
            return null;
        }
        int leaf = search(0, t0, t1, t2, t3, t4, t5, lastIndex == null ? -1 : lastIndex);
        lastResult.set(leaves[leaf]);
        return values[leaf];
    }

    /** Vanilla's Node.distance: squared distances to each interval summed; the offset target is 0. */
    private long distance(int node, long t0, long t1, long t2, long t3, long t4, long t5) {
        long[] b = bounds;
        int o = node * PARAMETERS * 2;
        long sum = square(axis(t0, b[o], b[o + 1]));
        sum += square(axis(t1, b[o + 2], b[o + 3]));
        sum += square(axis(t2, b[o + 4], b[o + 5]));
        sum += square(axis(t3, b[o + 6], b[o + 7]));
        sum += square(axis(t4, b[o + 8], b[o + 9]));
        sum += square(axis(t5, b[o + 10], b[o + 11]));
        sum += square(axis(0L, b[o + 12], b[o + 13]));
        return sum;
    }

    /** Vanilla's Parameter.distance(long). */
    private static long axis(long value, long min, long max) {
        long above = value - max;
        long below = min - value;
        return above > 0L ? above : Math.max(below, 0L);
    }

    private static long square(long value) {
        return value * value;
    }

    /** Vanilla's SubTree.search; a leaf returns itself. */
    private int search(int node, long t0, long t1, long t2, long t3, long t4, long t5, int leaf) {
        if (leaves[node] != null) return node;
        long best = leaf < 0 ? Long.MAX_VALUE : distance(leaf, t0, t1, t2, t3, t4, t5);
        int bestLeaf = leaf;
        for (int i = childStart[node]; i < childEnd[node]; i++) {
            int child = children[i];
            long childDistance = distance(child, t0, t1, t2, t3, t4, t5);
            if (best > childDistance) {
                int found = search(child, t0, t1, t2, t3, t4, t5, bestLeaf);
                long foundDistance = child == found ? childDistance : distance(found, t0, t1, t2, t3, t4, t5);
                if (best > foundDistance) {
                    best = foundDistance;
                    bestLeaf = found;
                }
            }
        }
        return bestLeaf;
    }

    private static void collect(Object node, List<Object> nodes, Map<Object, Integer> index) throws ReflectiveOperationException {
        index.put(node, nodes.size());
        nodes.add(node);
        Object[] nodeChildren = childrenOf(node);
        if (nodeChildren != null) {
            for (Object child : nodeChildren) collect(child, nodes, index);
        }
    }

    /** A SubTree's children array, or null for a Leaf. */
    private static Object[] childrenOf(Object node) throws ReflectiveOperationException {
        for (Field field : node.getClass().getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType().isArray()
                    && field.getType() != Climate.Parameter[].class) {
                field.setAccessible(true);
                return (Object[]) field.get(node);
            }
        }
        return null;
    }

    private static Object valueOf(Object leaf) throws ReflectiveOperationException {
        for (Field field : leaf.getClass().getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isArray()) {
                field.setAccessible(true);
                return field.get(leaf);
            }
        }
        throw new NoSuchFieldException("leaf value");
    }

    private static Field field(Object owner, Class<?> type) throws NoSuchFieldException {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        throw new NoSuchFieldException(type.getName());
    }

    /**
     * ParameterList's R-tree. Its class is not public and its field names change with the
     * mappings, so it is the field whose value holds a ThreadLocal (the tree's warm start).
     */
    static Object treeOf(Object list) throws ReflectiveOperationException {
        for (Field field : list.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
            field.setAccessible(true);
            Object value = field.get(list);
            if (value == null) continue;
            for (Field inner : value.getClass().getDeclaredFields()) {
                if (!Modifier.isStatic(inner.getModifiers()) && inner.getType() == ThreadLocal.class) return value;
            }
        }
        throw new NoSuchFieldException("index");
    }

    @SuppressWarnings("unchecked")
    static ThreadLocal<Object> lastResultOf(Object tree) throws ReflectiveOperationException {
        return (ThreadLocal<Object>) field(tree, ThreadLocal.class).get(tree);
    }
}
