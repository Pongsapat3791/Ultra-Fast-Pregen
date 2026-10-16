package com.example.pregen.terrain;

import com.example.pregen.mixin.Ap2Accessor;
import com.example.pregen.mixin.BeardifierAccessor;
import com.example.pregen.mixin.BlendDensityAccessor;
import com.example.pregen.mixin.ClampAccessor;
import com.example.pregen.mixin.MappedAccessor;
import com.example.pregen.mixin.MulOrAddAccessor;
import com.example.pregen.mixin.NoiseInterpolatorAccessor;
import com.example.pregen.mixin.RangeChoiceAccessor;
import com.example.pregen.mixin.YClampedGradientAccessor;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

/**
 * Interval bounds of a NoiseChunk's per-block density over one interpolation cell, computed from
 * the cell's eight interpolation corners instead of from its blocks.
 *
 * Every block's density is trilinear interpolation of those corners, a convex combination, so it
 * lies between their minimum and maximum (plus floating-point rounding, covered by a margin).
 * The functions applied after interpolation are bounded with interval arithmetic. A function
 * this class does not know is bounded by its own declared range, and a function from another
 * mod, or one with an unbounded range, makes the cell undecidable, so it is generated normally.
 */
public final class DensityBounds {
    /** Relative slack for the rounding of interpolation and of the interval arithmetic. */
    private static final double ROUNDING = 1.0e-9;
    /** Densities this close to zero are never decided from bounds. */
    public static final double DECISION_MARGIN = 1.0e-6;
    /** Chunks compiled with structure pieces or junctions nearby; for diagnostics. */
    public static final java.util.concurrent.atomic.LongAdder STRUCTURE_CHUNKS = new java.util.concurrent.atomic.LongAdder();

    private static final KeyDispatchDataCodec<?> ADD;
    private static final KeyDispatchDataCodec<?> MUL;
    private static final KeyDispatchDataCodec<?> MIN;
    private static final KeyDispatchDataCodec<?> MAX;
    private static final KeyDispatchDataCodec<?> ABS;
    private static final KeyDispatchDataCodec<?> SQUARE;
    private static final KeyDispatchDataCodec<?> CUBE;
    private static final KeyDispatchDataCodec<?> HALF_NEGATIVE;
    private static final KeyDispatchDataCodec<?> QUARTER_NEGATIVE;
    private static final KeyDispatchDataCodec<?> SQUEEZE;
    private static final Class<?> AP2;
    private static final Class<?> MUL_OR_ADD;
    private static final Class<?> MAPPED;
    private static final Class<?> CLAMP;
    private static final Class<?> RANGE_CHOICE;
    private static final Class<?> CONSTANT;
    private static final Class<?> Y_GRADIENT;
    private static final Class<?> BLEND_DENSITY;

    static {
        // Classified by class and codec of instances made through the public factories, which
        // holds with any mappings, since several of these types are not public.
        DensityFunction a = DensityFunctions.yClampedGradient(0, 1, 0.0, 1.0);
        DensityFunction b = DensityFunctions.yClampedGradient(0, 1, 1.0, 0.0);
        DensityFunction sum = DensityFunctions.add(a, b);
        ADD = sum.codec();
        MUL = DensityFunctions.mul(a, b).codec();
        MIN = DensityFunctions.min(a, b).codec();
        MAX = DensityFunctions.max(a, b).codec();
        ABS = a.abs().codec();
        SQUARE = a.square().codec();
        CUBE = a.cube().codec();
        HALF_NEGATIVE = a.halfNegative().codec();
        QUARTER_NEGATIVE = a.quarterNegative().codec();
        SQUEEZE = a.squeeze().codec();
        AP2 = sum.getClass();
        MUL_OR_ADD = DensityFunctions.mul(DensityFunctions.constant(2.0), a).getClass();
        MAPPED = a.abs().getClass();
        CLAMP = a.clamp(0.0, 1.0).getClass();
        RANGE_CHOICE = DensityFunctions.rangeChoice(a, 0.0, 1.0, a, b).getClass();
        CONSTANT = DensityFunctions.constant(1.0).getClass();
        Y_GRADIENT = a.getClass();
        BLEND_DENSITY = DensityFunctions.blendDensity(a).getClass();
    }

    /** The cell being bounded: corner indices in the interpolator slices and its block box. */
    static final class Cell {
        int cellY;
        int cellZ;
        int minX;
        int maxX;
        int minY;
        int maxY;
        int minZ;
        int maxZ;
    }

    private abstract static class Node {
        double lo;
        double hi;

        abstract void eval(Cell cell);

        final void unknown() {
            lo = Double.NEGATIVE_INFINITY;
            hi = Double.POSITIVE_INFINITY;
        }

        /** Widens by the rounding slack; anything not finite becomes unknown. */
        final void settle(double low, double high) {
            if (!(low <= high) || Double.isInfinite(low) || Double.isInfinite(high)) {
                unknown();
                return;
            }
            lo = low - ROUNDING * (Math.abs(low) + 1.0);
            hi = high + ROUNDING * (Math.abs(high) + 1.0);
        }
    }

    private static final class Fixed extends Node {
        Fixed(double low, double high) {
            if (!(low <= high) || Double.isInfinite(low) || Double.isInfinite(high)) {
                unknown();
            } else {
                lo = low;
                hi = high;
            }
        }

        @Override
        void eval(Cell cell) {}
    }

    private static final class Interpolated extends Node {
        private final NoiseInterpolatorAccessor interpolator;

        Interpolated(NoiseChunk.NoiseInterpolator interpolator) {
            this.interpolator = (NoiseInterpolatorAccessor) (Object) interpolator;
        }

        @Override
        void eval(Cell cell) {
            double[][] slice0 = interpolator.pregen$getSlice0();
            double[][] slice1 = interpolator.pregen$getSlice1();
            int y = cell.cellY;
            int z = cell.cellZ;
            double[] a = slice0[z];
            double[] b = slice0[z + 1];
            double[] c = slice1[z];
            double[] d = slice1[z + 1];
            double low = Math.min(Math.min(Math.min(a[y], a[y + 1]), Math.min(b[y], b[y + 1])),
                    Math.min(Math.min(c[y], c[y + 1]), Math.min(d[y], d[y + 1])));
            double high = Math.max(Math.max(Math.max(a[y], a[y + 1]), Math.max(b[y], b[y + 1])),
                    Math.max(Math.max(c[y], c[y + 1]), Math.max(d[y], d[y + 1])));
            settle(low, high);
        }
    }

    private static final class Binary extends Node {
        private static final int ADD_OP = 0;
        private static final int MUL_OP = 1;
        private static final int MIN_OP = 2;
        private static final int MAX_OP = 3;
        private final int op;
        private final Node first;
        private final Node second;

        Binary(int op, Node first, Node second) {
            this.op = op;
            this.first = first;
            this.second = second;
        }

        @Override
        void eval(Cell cell) {
            first.eval(cell);
            second.eval(cell);
            double a = first.lo;
            double b = first.hi;
            double c = second.lo;
            double d = second.hi;
            switch (op) {
                case ADD_OP -> settle(a + c, b + d);
                case MUL_OP -> {
                    double p1 = a * c;
                    double p2 = a * d;
                    double p3 = b * c;
                    double p4 = b * d;
                    settle(Math.min(Math.min(p1, p2), Math.min(p3, p4)), Math.max(Math.max(p1, p2), Math.max(p3, p4)));
                }
                case MIN_OP -> settle(Math.min(a, c), Math.min(b, d));
                default -> settle(Math.max(a, c), Math.max(b, d));
            }
        }
    }

    private static final class Mapped extends Node {
        private static final int ABS_OP = 0;
        private static final int SQUARE_OP = 1;
        private static final int CUBE_OP = 2;
        private static final int HALF_NEGATIVE_OP = 3;
        private static final int QUARTER_NEGATIVE_OP = 4;
        private static final int SQUEEZE_OP = 5;
        private final int op;
        private final Node input;

        Mapped(int op, Node input) {
            this.op = op;
            this.input = input;
        }

        /** Vanilla's DensityFunctions.Mapped.transform. */
        private static double transform(int op, double d) {
            return switch (op) {
                case ABS_OP -> Math.abs(d);
                case SQUARE_OP -> d * d;
                case CUBE_OP -> d * d * d;
                case HALF_NEGATIVE_OP -> d > 0.0 ? d : d * 0.5;
                case QUARTER_NEGATIVE_OP -> d > 0.0 ? d : d * 0.25;
                default -> {
                    double e = Mth.clamp(d, -1.0, 1.0);
                    yield e / 2.0 - e * e * e / 24.0;
                }
            };
        }

        @Override
        void eval(Cell cell) {
            input.eval(cell);
            double a = input.lo;
            double b = input.hi;
            if (op == ABS_OP || op == SQUARE_OP) {
                // Even functions: the minimum is at zero when the interval contains it.
                double low = a >= 0.0 ? transform(op, a) : b <= 0.0 ? transform(op, b) : 0.0;
                settle(low, Math.max(transform(op, a), transform(op, b)));
            } else {
                // The others are non-decreasing.
                settle(transform(op, a), transform(op, b));
            }
        }
    }

    private static final class Clamp extends Node {
        private final Node input;
        private final double min;
        private final double max;

        Clamp(Node input, double min, double max) {
            this.input = input;
            this.min = min;
            this.max = max;
        }

        @Override
        void eval(Cell cell) {
            input.eval(cell);
            settle(Mth.clamp(input.lo, min, max), Mth.clamp(input.hi, min, max));
        }
    }

    private static final class RangeChoice extends Node {
        private final Node input;
        private final double minInclusive;
        private final double maxExclusive;
        private final Node inRange;
        private final Node outOfRange;

        RangeChoice(Node input, double minInclusive, double maxExclusive, Node inRange, Node outOfRange) {
            this.input = input;
            this.minInclusive = minInclusive;
            this.maxExclusive = maxExclusive;
            this.inRange = inRange;
            this.outOfRange = outOfRange;
        }

        @Override
        void eval(Cell cell) {
            input.eval(cell);
            if (input.lo >= minInclusive && input.hi < maxExclusive) {
                inRange.eval(cell);
                lo = inRange.lo;
                hi = inRange.hi;
            } else if (input.hi < minInclusive || input.lo >= maxExclusive) {
                outOfRange.eval(cell);
                lo = outOfRange.lo;
                hi = outOfRange.hi;
            } else {
                inRange.eval(cell);
                outOfRange.eval(cell);
                settle(Math.min(inRange.lo, outOfRange.lo), Math.max(inRange.hi, outOfRange.hi));
            }
        }
    }

    private static final class YGradient extends Node {
        private final int fromY;
        private final int toY;
        private final double fromValue;
        private final double toValue;

        YGradient(YClampedGradientAccessor gradient) {
            fromY = gradient.pregen$getFromY();
            toY = gradient.pregen$getToY();
            fromValue = gradient.pregen$getFromValue();
            toValue = gradient.pregen$getToValue();
        }

        @Override
        void eval(Cell cell) {
            // Vanilla's clampedMap is monotonic in y, so the extremes are at the cell's ends.
            double bottom = Mth.clampedMap(cell.minY, fromY, toY, fromValue, toValue);
            double top = Mth.clampedMap(cell.maxY, fromY, toY, fromValue, toValue);
            settle(Math.min(bottom, top), Math.max(bottom, top));
        }
    }

    /**
     * Vanilla's Beardifier adds nothing (exactly 0.0) to a block 12 or more blocks away from
     * a piece or junction: horizontally, or vertically from the piece's adjusted ground level
     * and box (its bury, beard-thin and beard-box kernels all end there). A cell is
     * undecidable only inside that reach of some piece or junction.
     */
    private static final class Beard extends Node {
        private static final int REACH = 16;
        private final int[] boxes;

        Beard(int[] boxes) {
            this.boxes = boxes;
        }

        @Override
        void eval(Cell cell) {
            for (int i = 0; i < boxes.length; i += 6) {
                if (cell.maxX >= boxes[i] && cell.minX <= boxes[i + 1]
                        && cell.maxY >= boxes[i + 2] && cell.minY <= boxes[i + 3]
                        && cell.maxZ >= boxes[i + 4] && cell.minZ <= boxes[i + 5]) {
                    unknown();
                    return;
                }
            }
            lo = 0.0;
            hi = 0.0;
        }

        static Node of(Beardifier beardifier) {
            BeardifierAccessor accessor = (BeardifierAccessor) beardifier;
            List<Integer> boxes = new ArrayList<>();
            ObjectListIterator<Beardifier.Rigid> pieces = accessor.pregen$getPieceIterator();
            pieces.back(Integer.MAX_VALUE);
            while (pieces.hasNext()) {
                Beardifier.Rigid rigid = pieces.next();
                if (rigid.terrainAdjustment() == TerrainAdjustment.NONE) continue;
                BoundingBox box = rigid.box();
                int ground = box.minY() + rigid.groundLevelDelta();
                add(boxes, box.minX(), box.maxX(), Math.min(ground, box.minY()), Math.max(ground, box.maxY()),
                        box.minZ(), box.maxZ());
            }
            pieces.back(Integer.MAX_VALUE);
            ObjectListIterator<JigsawJunction> junctions = accessor.pregen$getJunctionIterator();
            junctions.back(Integer.MAX_VALUE);
            while (junctions.hasNext()) {
                JigsawJunction junction = junctions.next();
                add(boxes, junction.getSourceX(), junction.getSourceX(), junction.getSourceGroundY(),
                        junction.getSourceGroundY(), junction.getSourceZ(), junction.getSourceZ());
            }
            junctions.back(Integer.MAX_VALUE);
            if (!boxes.isEmpty()) STRUCTURE_CHUNKS.increment();
            return new Beard(boxes.stream().mapToInt(Integer::intValue).toArray());
        }

        private static void add(List<Integer> boxes, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            boxes.add(minX - REACH);
            boxes.add(maxX + REACH);
            boxes.add(minY - REACH);
            boxes.add(maxY + REACH);
            boxes.add(minZ - REACH);
            boxes.add(maxZ + REACH);
        }
    }

    private final Node root;
    private final Cell cell = new Cell();
    private double lo;
    private double hi;

    private DensityBounds(Node root) {
        this.root = root;
    }

    /** Bounds of {@code density} as evaluated per block inside the given NoiseChunk. */
    public static DensityBounds compile(DensityFunction density, Blender blender) {
        return new DensityBounds(build(density, blender, 0));
    }

    /** False when no cell can ever be decided, e.g. a foreign function at the root. */
    public boolean decidable() {
        return !(root instanceof Fixed fixed) || fixed.lo <= fixed.hi && !Double.isInfinite(fixed.lo);
    }

    private static Node build(DensityFunction f, Blender blender, int depth) {
        if (depth > 64) return generic(f);
        Class<?> type = f.getClass();
        if (f instanceof NoiseChunk.NoiseInterpolator interpolator) {
            return new Interpolated(interpolator);
        }
        if (type == CONSTANT) {
            return new Fixed(f.minValue(), f.maxValue());
        }
        if (type == AP2) {
            Ap2Accessor ap2 = (Ap2Accessor) f;
            int op = opOf(f.codec());
            if (op < 0) return generic(f);
            return new Binary(op, build(ap2.pregen$getArgument1(), blender, depth + 1),
                    build(ap2.pregen$getArgument2(), blender, depth + 1));
        }
        if (type == MUL_OR_ADD) {
            MulOrAddAccessor mulOrAdd = (MulOrAddAccessor) f;
            int op = opOf(f.codec());
            if (op != Binary.ADD_OP && op != Binary.MUL_OP) return generic(f);
            double argument = mulOrAdd.pregen$getArgument();
            return new Binary(op, build(mulOrAdd.pregen$getInput(), blender, depth + 1), new Fixed(argument, argument));
        }
        if (type == MAPPED) {
            KeyDispatchDataCodec<?> codec = f.codec();
            int op = codec == ABS ? Mapped.ABS_OP : codec == SQUARE ? Mapped.SQUARE_OP : codec == CUBE ? Mapped.CUBE_OP
                    : codec == HALF_NEGATIVE ? Mapped.HALF_NEGATIVE_OP : codec == QUARTER_NEGATIVE ? Mapped.QUARTER_NEGATIVE_OP
                    : codec == SQUEEZE ? Mapped.SQUEEZE_OP : -1;
            if (op < 0) return generic(f);
            return new Mapped(op, build(((MappedAccessor) f).pregen$getInput(), blender, depth + 1));
        }
        if (type == CLAMP) {
            return new Clamp(build(((ClampAccessor) f).pregen$getInput(), blender, depth + 1), f.minValue(), f.maxValue());
        }
        if (type == RANGE_CHOICE) {
            RangeChoiceAccessor choice = (RangeChoiceAccessor) f;
            return new RangeChoice(build(choice.pregen$getInput(), blender, depth + 1),
                    choice.pregen$getMinInclusive(), choice.pregen$getMaxExclusive(),
                    build(choice.pregen$getWhenInRange(), blender, depth + 1),
                    build(choice.pregen$getWhenOutOfRange(), blender, depth + 1));
        }
        if (type == Y_GRADIENT) {
            return new YGradient((YClampedGradientAccessor) f);
        }
        if (type == BLEND_DENSITY) {
            // The empty blender returns the density unchanged; real blending is unbounded.
            return blender == Blender.empty()
                    ? build(((BlendDensityAccessor) f).pregen$getInput(), blender, depth + 1)
                    : generic(f);
        }
        if (type == Beardifier.class) {
            return Beard.of((Beardifier) f);
        }
        return generic(f);
    }

    /** True for vanilla's add(finalDensity, beardifier), the aquifer's per-block density. */
    public static boolean isSumWith(DensityFunction density, DensityFunction beardifier) {
        return density.getClass() == AP2 && density.codec() == ADD
                && ((Ap2Accessor) density).pregen$getArgument2() == beardifier;
    }

    private static int opOf(KeyDispatchDataCodec<?> codec) {
        return codec == ADD ? Binary.ADD_OP : codec == MUL ? Binary.MUL_OP
                : codec == MIN ? Binary.MIN_OP : codec == MAX ? Binary.MAX_OP : -1;
    }

    /** Minecraft's own functions declare a sound range; another mod's might not. */
    private static Node generic(DensityFunction f) {
        if (!f.getClass().getName().startsWith("net.minecraft.")) {
            return new Fixed(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        }
        return new Fixed(f.minValue(), f.maxValue());
    }

    /**
     * Bounds the density over the cell whose corners sit at index cellY, cellZ of the current
     * slices and whose blocks span the given box.
     */
    public void evaluate(int cellY, int cellZ, int minX, int minY, int minZ, int width, int height) {
        cell.cellY = cellY;
        cell.cellZ = cellZ;
        cell.minX = minX;
        cell.maxX = minX + width - 1;
        cell.minY = minY;
        cell.maxY = minY + height - 1;
        cell.minZ = minZ;
        cell.maxZ = minZ + width - 1;
        root.eval(cell);
        lo = root.lo;
        hi = root.hi;
    }

    /** Every block of the last evaluated cell has density at most zero. */
    public boolean allNonPositive() {
        return hi < -DECISION_MARGIN;
    }

    /** Largest magnitude over the last evaluated cell; infinite when unknown. */
    public double maxAbs() {
        return Math.max(Math.abs(lo), Math.abs(hi));
    }

    /** Every block of the last evaluated cell has density above zero. */
    public boolean allPositive() {
        return lo > DECISION_MARGIN;
    }
}
