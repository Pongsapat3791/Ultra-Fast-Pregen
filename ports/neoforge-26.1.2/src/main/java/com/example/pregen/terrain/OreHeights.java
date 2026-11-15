package com.example.pregen.terrain;

import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Ore attempts above all terrain, answered without Minecraft's probing. OreFeature#place asks the
 * terrain height (OCEAN_FLOOR_WG) of every column of the ore's square, 9 x 9 to 27 x 27 of them,
 * for one at or above the ore's lowest y, and places nothing when there is none: nearly all of
 * iron's and coal's upper attempts in low land, some ten thousand height lookups a chunk. That
 * heightmap stands still while features are placed (from the carvers status on, a block change
 * updates the final heightmaps only), so each chunk's highest value, taken once per features
 * step, answers "none" for a whole square at once. A square that reaches the highest value, or a
 * chunk whose heightmap could still change, is left to Minecraft's own probing.
 */
public final class OreHeights {
    /** Benchmark switch, on unless -Dfastpregen.oreHeights=false. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("fastpregen.oreHeights", "true"));
    /** Ore attempts answered here; for diagnostics. */
    public static final LongAdder ANSWERED = new LongAdder();

    /** A generation region that keeps these for its features step (see WorldGenRegionMixin). */
    public interface Region {
        /** The heights of this region's step, or null when it is not a features step of this mod's batches. */
        OreHeights pregen$oreHeights();
    }

    private final WorldGenLevel level;
    /** The chunks asked so far (an ore's square lies within the 3 x 3 around the step's chunk). */
    private final long[] chunks = new long[16];
    private final int[] highest = new int[16];
    private int count;

    public OreHeights(WorldGenLevel level) {
        this.level = level;
    }

    /**
     * Whether no column of the square from (minX, minZ) to (minX + size, minZ + size) has terrain
     * at or above y: Minecraft's {@code y <= level.getHeight(OCEAN_FLOOR_WG, x, z)} fails for all of them.
     */
    public boolean noneAtOrAbove(int minX, int minZ, int size, int y) {
        for (int chunkZ = minZ >> 4; chunkZ <= minZ + size >> 4; chunkZ++) {
            for (int chunkX = minX >> 4; chunkX <= minX + size >> 4; chunkX++) {
                if (y <= highest(chunkX, chunkZ)) return false;
            }
        }
        ANSWERED.increment();
        return true;
    }

    private int highest(int chunkX, int chunkZ) {
        long key = (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
        for (int i = 0; i < count; i++) {
            if (chunks[i] == key) return highest[i];
        }
        int value = compute(chunkX, chunkZ);
        if (count < chunks.length) {
            chunks[count] = key;
            highest[count++] = value;
        }
        return value;
    }

    /** The highest value the region's getHeight gives in the chunk, or the highest int where it is not settled. */
    private int compute(int chunkX, int chunkZ) {
        ChunkAccess chunk = level.getChunk(chunkX, chunkZ);
        if (chunk.getPersistedStatus().heightmapsAfter().contains(Heightmap.Types.OCEAN_FLOOR_WG)
                || !chunk.hasPrimedHeightmap(Heightmap.Types.OCEAN_FLOOR_WG)) {
            return Integer.MAX_VALUE;
        }
        // WorldGenRegion#getHeight is the chunk's height plus one, which is the heightmap's first free y.
        Heightmap heightmap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        int max = Integer.MIN_VALUE;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                max = Math.max(max, heightmap.getFirstAvailable(x, z));
            }
        }
        return max;
    }
}
