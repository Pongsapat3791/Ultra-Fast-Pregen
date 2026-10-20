package com.example.pregen.terrain;

import com.example.pregen.mixin.BiomeManagerAccessor;
import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * The biomes one chunk's surface step can see (ModernFix's optimize_surface_rules does much the
 * same on Minecraft's own path). Minecraft finds the biome of a block by picking, with a hash of
 * each corner, one of the eight noise-biome cells (4 blocks wide) around it and reading that
 * cell's biome. For the blocks of one chunk those cells lie in the chunk or one cell around it,
 * so within its 3x3 neighbors, which the surface step has with their biomes. Read once per chunk:
 *  - a lookup compares its eight cells and runs Minecraft's hash only where they differ; where
 *    all cells around the chunk hold one biome, every lookup is that biome;
 *  - a biome test of the surface rules that holds for all of these biomes always passes in the
 *    chunk, one that holds for none never does (see SurfaceRulePruning).
 * One per chunk's surface step (about 15 KB, garbage once the step ends).
 */
public final class SurfaceBiomes implements Function<BlockPos, Holder<Biome>> {
    /** Benchmark switch, on unless -Dfastpregen.surfaceShortcuts=false. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("fastpregen.surfaceShortcuts", "true"));
    /** Chunks whose surface step used their biomes read here (for the regression check). */
    public static final LongAdder READ_CHUNKS = new LongAdder();

    /** The surface step begun on each thread, until its rules' context takes it. */
    private static final ThreadLocal<Pending> PENDING = ThreadLocal.withInitial(Pending::new);
    /** Cells per row: the chunk's four and one on each side. */
    private static final int ROW = 6;
    private static final int LAYER = ROW * ROW;

    private static final class Pending {
        BiomeManager manager;
        ChunkAccess chunk;
    }

    private BiomeManager manager;
    private long zoomSeed;
    /** The cell coordinates of cells[0], and how many layers of cells there are. */
    private int originX;
    private int originY;
    private int originZ;
    private int layers;
    private Holder<Biome>[] cells;
    /** The distinct biomes of the cells. */
    @SuppressWarnings("unchecked")
    private Holder<Biome>[] biomes = new Holder[8];
    private int biomeCount;
    /** The cells' only biome, or null. */
    private Holder<Biome> single;

    private SurfaceBiomes() {}

    /** At the start of Minecraft's surface step for the chunk, on this mod's terrain workers. */
    public static void beginSurface(BiomeManager manager, ChunkAccess chunk) {
        Pending pending = PENDING.get();
        pending.manager = manager;
        pending.chunk = chunk;
    }

    /** At the end of that step; its rules' context took the biomes at the start. */
    public static void endSurface() {
        Pending pending = PENDING.get();
        pending.manager = null;
        pending.chunk = null;
    }

    /**
     * For the rules' context of the chunk whose surface step began on this thread: its biomes,
     * or null (another context, or neighbors without biomes; Minecraft's own lookups then).
     */
    public static SurfaceBiomes forContext(ChunkAccess chunk) {
        Pending pending = PENDING.get();
        BiomeManager manager = pending.manager;
        if (manager == null || pending.chunk != chunk) return null;
        pending.manager = null;
        pending.chunk = null;
        SurfaceBiomes surface = new SurfaceBiomes();
        return surface.read(manager, chunk) ? surface : null;
    }

    private boolean read(BiomeManager manager, ChunkAccess chunk) {
        BiomeManagerAccessor access = (BiomeManagerAccessor) manager;
        if (!(access.pregen$getNoiseBiomeSource() instanceof BiomeChunkSource source)) return false;
        ChunkPos pos = chunk.getPos();
        // Blocks minY to maxY look at the cells from (minY - 2) >> 2 to ((maxY - 2) >> 2) + 1.
        int minY = chunk.getMinY();
        int maxY = minY + chunk.getHeight() - 1;
        originX = (pos.x() << 2) - 1;
        originZ = (pos.z() << 2) - 1;
        originY = (minY - 2) >> 2;
        layers = ((maxY - 2) >> 2) + 2 - originY;
        ChunkAccess[] neighbors = new ChunkAccess[9];
        try {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    ChunkAccess neighbor = source.pregen$getBiomeChunk(pos.x() + dx, pos.z() + dz);
                    if (neighbor == null) return false;
                    neighbors[(dz + 1) * 3 + dx + 1] = neighbor;
                }
            }
            @SuppressWarnings("unchecked")
            Holder<Biome>[] cells = new Holder[LAYER * layers];
            this.cells = cells;
            Holder<Biome> last = null;
            int index = 0;
            for (int y = 0; y < layers; y++) {
                int cellY = originY + y;
                for (int z = 0; z < ROW; z++) {
                    int cellZ = originZ + z;
                    // Rows 0, 1 to 4 and 5 lie in the chunks to the north, the chunk's own, to the south.
                    int row = (z == 0 ? 0 : z == ROW - 1 ? 2 : 1) * 3;
                    for (int x = 0; x < ROW; x++) {
                        ChunkAccess neighbor = neighbors[row + (x == 0 ? 0 : x == ROW - 1 ? 2 : 1)];
                        Holder<Biome> biome = neighbor.getNoiseBiome(originX + x, cellY, cellZ);
                        cells[index++] = biome;
                        if (biome != last) {
                            addBiome(biome);
                            last = biome;
                        }
                    }
                }
            }
        } catch (RuntimeException unavailable) {
            // Minecraft's lookups would meet the same; they run, and fail, as they would.
            return false;
        }
        this.manager = manager;
        this.zoomSeed = access.pregen$getBiomeZoomSeed();
        single = biomeCount == 1 ? biomes[0] : null;
        READ_CHUNKS.increment();
        return true;
    }

    private void addBiome(Holder<Biome> biome) {
        for (int i = 0; i < biomeCount; i++) {
            if (biomes[i] == biome) return;
        }
        if (biomeCount == biomes.length) biomes = Arrays.copyOf(biomes, biomeCount * 2);
        biomes[biomeCount++] = biome;
    }

    /**
     * How a biome condition comes out in this chunk: 1 when it holds for every biome the chunk's
     * lookups can return, -1 for none, 0 when it depends on the block.
     */
    public int decide(SurfaceRulePruning.BiomeTest test) {
        boolean any = false;
        boolean all = true;
        for (int i = 0; i < biomeCount; i++) {
            if (test.pregen$matches(biomes[i])) any = true;
            else all = false;
        }
        return all ? 1 : any ? 0 : -1;
    }

    /** Minecraft's BiomeManager#getBiome, from the cells read for the chunk. */
    @Override
    public Holder<Biome> apply(BlockPos pos) {
        Holder<Biome> only = single;
        if (only != null) return only;
        int absX = pos.getX() - 2;
        int absY = pos.getY() - 2;
        int absZ = pos.getZ() - 2;
        int parentX = absX >> 2;
        int parentY = absY >> 2;
        int parentZ = absZ >> 2;
        int x = parentX - originX;
        int y = parentY - originY;
        int z = parentZ - originZ;
        if (x < 0 || x >= ROW - 1 || z < 0 || z >= ROW - 1 || y < 0 || y >= layers - 1) {
            return manager.getBiome(pos);  // not a block of the chunk
        }
        Holder<Biome>[] cells = this.cells;
        int base = (y * ROW + z) * ROW + x;
        Holder<Biome> biome = cells[base];
        if (biome == cells[base + 1] && biome == cells[base + ROW] && biome == cells[base + ROW + 1]
                && biome == cells[base + LAYER] && biome == cells[base + LAYER + 1]
                && biome == cells[base + LAYER + ROW] && biome == cells[base + LAYER + ROW + 1]) {
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
        return cells[base + ((nearest & 4) == 0 ? 0 : 1) + ((nearest & 2) == 0 ? 0 : LAYER) + ((nearest & 1) == 0 ? 0 : ROW)];
    }
}
