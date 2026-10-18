package io.github.pongsapat3791.ultrafastpregen.api;

import java.util.Objects;

/**
 * The area of a run: a square or a circle of blocks around a center, in one dimension.
 *
 * <pre>{@code
 * PregenRequest.square("minecraft:overworld", 0, 0, 2000)   // blocks -2000..1999 on both axes
 * PregenRequest.circle("minecraft:overworld", 0, 0, 2000)   // chunks whose center is within 2000 blocks of 0, 0
 * }</pre>
 */
public final class PregenRequest {
    public enum Shape { SQUARE, CIRCLE }

    /** Largest radius: an area 100,000 blocks across, as {@code /pregen start 100}. */
    public static final int MAX_RADIUS = 50_000;

    private final String dimension;
    private final int centerX;
    private final int centerZ;
    private final int radius;
    private final Shape shape;

    private PregenRequest(String dimension, int centerX, int centerZ, int radius, Shape shape) {
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        if (radius < 1 || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("radius must be 1.." + MAX_RADIUS + " blocks, was " + radius);
        }
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        this.shape = shape;
    }

    /**
     * The square of side {@code 2 * radius} blocks centered on block (centerX, centerZ): blocks
     * {@code center - radius} to {@code center + radius - 1} on each axis, and every chunk they touch.
     *
     * @param dimension a dimension id such as "minecraft:overworld", "minecraft:the_nether" or a modded one
     */
    public static PregenRequest square(String dimension, int centerX, int centerZ, int radius) {
        return new PregenRequest(dimension, centerX, centerZ, radius, Shape.SQUARE);
    }

    /**
     * The circle of that radius around block (centerX, centerZ): every chunk whose center lies
     * within the radius.
     */
    public static PregenRequest circle(String dimension, int centerX, int centerZ, int radius) {
        return new PregenRequest(dimension, centerX, centerZ, radius, Shape.CIRCLE);
    }

    public String dimension() {
        return dimension;
    }

    public int centerX() {
        return centerX;
    }

    public int centerZ() {
        return centerZ;
    }

    /** Half the side of the square, or the circle's radius, in blocks. */
    public int radius() {
        return radius;
    }

    public Shape shape() {
        return shape;
    }

    @Override
    public String toString() {
        return String.format("%s of radius %d around [%d, %d] in %s", shape == Shape.CIRCLE ? "circle" : "square",
                radius, centerX, centerZ, dimension);
    }
}
