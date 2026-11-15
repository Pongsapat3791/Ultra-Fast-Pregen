package com.example.pregen.terrain;

/** Enables chunk-local terrain optimizations only for this mod's generation workers. */
public final class TerrainGenerationContext implements AutoCloseable {
    private static final ThreadLocal<TerrainGenerationContext> ACTIVE = new ThreadLocal<>();
    private final TerrainGenerationContext previous;
    private final boolean fastNoise;

    private TerrainGenerationContext(boolean fastNoise) {
        previous = ACTIVE.get();
        this.fastNoise = fastNoise;
        ACTIVE.set(this);
    }

    public static TerrainGenerationContext enter() {
        return new TerrainGenerationContext(true);
    }

    public static TerrainGenerationContext enter(boolean fastNoise) {
        return new TerrainGenerationContext(fastNoise);
    }

    public static boolean isActive() {
        return ACTIVE.get() != null;
    }

    public static boolean isFastNoiseEnabled() {
        TerrainGenerationContext context = ACTIVE.get();
        return context != null && context.fastNoise;
    }

    @Override
    public void close() {
        if (previous != null) ACTIVE.set(previous);
        else ACTIVE.remove();
    }
}
