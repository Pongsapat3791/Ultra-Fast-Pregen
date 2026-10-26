package com.example.pregen.terrain;

import com.example.pregen.mixin.BiomeManagerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;

/**
 * Minecraft's biome lookup for the features step of this mod's batches, with each biome cell
 * read once. BiomeManager#getBiome hashes all eight cells around the block to pick the nearest,
 * then reads that cell through the generation region; features look biomes up for every
 * placement they try (biome filters, snow and ice, trees). Here the cells are kept for the step,
 * a block whose eight cells hold one biome gets it directly, and elsewhere Minecraft's own hash
 * picks the cell. Biomes do not change while features are placed.
 */
public final class FeatureBiomes extends BiomeManager {
    /** Benchmark switch, on unless -Dfastpregen.featureBiomes=false. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("fastpregen.featureBiomes", "true"));

    private final NoiseBiomeSource source;
    private final long zoomSeed;
    /** Cells read so far: open addressing on packed cell coordinates (a null value is a free slot). */
    private long[] keys = new long[256];
    private Object[] values = new Object[256];
    private int size;

    public FeatureBiomes(NoiseBiomeSource source, long zoomSeed) {
        super(source, zoomSeed);
        this.source = source;
        this.zoomSeed = zoomSeed;
    }

    @SuppressWarnings("unchecked")
    private Holder<Biome> cell(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        int mask = keys.length - 1;
        int slot = (int) (key * 0x9E3779B97F4A7C15L >>> 40) & mask;
        while (values[slot] != null) {
            if (keys[slot] == key) return (Holder<Biome>) values[slot];
            slot = (slot + 1) & mask;
        }
        Holder<Biome> biome = source.getNoiseBiome(x, y, z);
        keys[slot] = key;
        values[slot] = biome;
        if (++size * 4 > keys.length * 3) grow();
        return biome;
    }

    private void grow() {
        long[] oldKeys = keys;
        Object[] oldValues = values;
        keys = new long[oldKeys.length * 2];
        values = new Object[oldValues.length * 2];
        int mask = keys.length - 1;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldValues[i] == null) continue;
            int slot = (int) (oldKeys[i] * 0x9E3779B97F4A7C15L >>> 40) & mask;
            while (values[slot] != null) slot = (slot + 1) & mask;
            keys[slot] = oldKeys[i];
            values[slot] = oldValues[i];
        }
    }

    @Override
    public Holder<Biome> getBiome(BlockPos pos) {
        int absX = pos.getX() - 2;
        int absY = pos.getY() - 2;
        int absZ = pos.getZ() - 2;
        int parentX = absX >> 2;
        int parentY = absY >> 2;
        int parentZ = absZ >> 2;
        Holder<Biome> biome = cell(parentX, parentY, parentZ);
        if (biome == cell(parentX, parentY, parentZ + 1) && biome == cell(parentX, parentY + 1, parentZ)
                && biome == cell(parentX, parentY + 1, parentZ + 1) && biome == cell(parentX + 1, parentY, parentZ)
                && biome == cell(parentX + 1, parentY, parentZ + 1) && biome == cell(parentX + 1, parentY + 1, parentZ)
                && biome == cell(parentX + 1, parentY + 1, parentZ + 1)) {
            return biome;
        }
        // The corner Minecraft's hash puts nearest, as getBiome picks it.
        double fractionX = (absX & 3) / 4.0;
        double fractionY = (absY & 3) / 4.0;
        double fractionZ = (absZ & 3) / 4.0;
        int nearest = 0;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            boolean xEven = (i & 4) == 0;
            boolean yEven = (i & 2) == 0;
            boolean zEven = (i & 1) == 0;
            double distance = BiomeManagerAccessor.pregen$fiddledDistance(zoomSeed,
                    xEven ? parentX : parentX + 1, yEven ? parentY : parentY + 1, zEven ? parentZ : parentZ + 1,
                    xEven ? fractionX : fractionX - 1.0, yEven ? fractionY : fractionY - 1.0, zEven ? fractionZ : fractionZ - 1.0);
            if (nearestDistance > distance) {
                nearest = i;
                nearestDistance = distance;
            }
        }
        return cell((nearest & 4) == 0 ? parentX : parentX + 1, (nearest & 2) == 0 ? parentY : parentY + 1,
                (nearest & 1) == 0 ? parentZ : parentZ + 1);
    }
}
