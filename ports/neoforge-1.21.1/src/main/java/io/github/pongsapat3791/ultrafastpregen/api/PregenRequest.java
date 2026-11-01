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
    private final int batchSize;
    private final boolean keepExisting;

    private PregenRequest(String dimension, int centerX, int centerZ, int radius, Shape shape, int batchSize, boolean keepExisting) {
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        if (radius < 1 || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("radius must be 1.." + MAX_RADIUS + " blocks, was " + radius);
        }
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        this.shape = shape;
        this.batchSize = batchSize;
        this.keepExisting = keepExisting;
    }

    /**
     * The square of side {@code 2 * radius} blocks centered on block (centerX, centerZ): blocks
     * {@code center - radius} to {@code center + radius - 1} on each axis, and every chunk they touch.
     *
     * @param dimension a dimension id such as "minecraft:overworld", "minecraft:the_nether" or a modded one
     */
    public static PregenRequest square(String dimension, int centerX, int centerZ, int radius) {
        return new PregenRequest(dimension, centerX, centerZ, radius, Shape.SQUARE, 0, false);
    }

    /**
     * The circle of that radius around block (centerX, centerZ): every chunk whose center lies
     * within the radius.
     */
    public static PregenRequest circle(String dimension, int centerX, int centerZ, int radius) {
        return new PregenRequest(dimension, centerX, centerZ, radius, Shape.CIRCLE, 0, false);
    }

    /**
     * This run's batch size in blocks instead of {@code batch_size} from the config, for a run that must
     * be one batch, for example. A batch too large for the free heap still shrinks. Since API version 2.
     */
    public PregenRequest withBatchSize(int blocks) {
        if (blocks < 1) throw new IllegalArgumentException("batch size must be positive, was " + blocks);
        return new PregenRequest(dimension, centerX, centerZ, radius, shape, blocks, keepExisting);
    }

    /**
     * Keeps chunks that exist, whatever {@code skip_existing_chunks} in the config says, for a run that
     * must join chunks written beforehand. Since API version 2.
     */
    public PregenRequest keepingExistingChunks() {
        return new PregenRequest(dimension, centerX, centerZ, radius, shape, batchSize, true);
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

    /** The batch size of {@link #withBatchSize}, or 0 for the config's. */
    public int batchSize() {
        return batchSize;
    }

    /** Whether {@link #keepingExistingChunks} was asked for. */
    public boolean keepsExistingChunks() {
        return keepExisting;
    }

    @Override
    public String toString() {
        return String.format("%s of radius %d around [%d, %d] in %s", shape == Shape.CIRCLE ? "circle" : "square",
                radius, centerX, centerZ, dimension);
    }
}
