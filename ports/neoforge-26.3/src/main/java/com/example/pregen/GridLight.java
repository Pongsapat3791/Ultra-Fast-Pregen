package com.example.pregen;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * A grid's light worked out on this mod's worker threads instead of the server's one light thread,
 * which took several seconds a batch for it (far longer while the next batch generates). Light
 * reaches at most 15 blocks, so a chunk's light depends only on the blocks within one chunk of it:
 * each tile of the grid's chunks is lit by a light engine of its own (Minecraft's, taking the steps
 * INITIALIZE_LIGHT and LIGHT take) over the tile and the ring of chunks around it, and keeps the
 * light of the tile's own chunks. The server's engine then takes it as a lit chunk's light loaded
 * from disk (see InMemoryChunkGrid#lightInParallel).
 *
 * Dark chunks are in the engines as Minecraft holds the part-generated neighbors of a chunk it
 * lights: their blocks stop and pass light, light reaches into them, but they give none of their
 * own (their sky columns and glowing blocks count only once the server lights them). That is how
 * the chunks outside a run's area take part (see InMemoryChunkGrid#setOutsideUnlit).
 */
final class GridLight {
    /** On unless -Dfastpregen.parallelLight=false. */
    static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("fastpregen.parallelLight", "true"));
    /** A lit chunk given to the server's engine as one step (see LitChunkTaker), on unless -Dfastpregen.lightPerChunk=false. */
    static volatile boolean oneTaskPerChunk = Boolean.parseBoolean(System.getProperty("fastpregen.lightPerChunk", "true"));
    /** Chunks per side of a tile: a tile for every worker several times over; the ring adds half again. */
    private static final int TILE = Math.max(2, Integer.getInteger("fastpregen.lightTile", 8));

    /** One chunk's light by light section, the lowest first; null where the engine keeps no layer. */
    record ChunkLight(ProtoChunk chunk, DataLayer[] block, DataLayer[] sky) {}

    /**
     * lit: the light of every chunk that is not dark. reached: for the chunks asked for, the light the
     * engines hold in them, the brightest of every tile that has the chunk (for a dark chunk that is the
     * light that came in from the lit chunks around it, and nothing else).
     */
    record Result(List<ChunkLight> lit, Map<Long, ChunkLight> reached) {}

    private GridLight() {}

    /** Lights the chunks, all of which lie in the box, on the pool. */
    static List<ChunkLight> compute(ServerLevel level, Long2ObjectMap<ProtoChunk> chunks,
                                    int minX, int minZ, int maxX, int maxZ, ExecutorService pool) {
        return compute(level, chunks, LongSets.EMPTY_SET, LongSets.EMPTY_SET, minX, minZ, maxX, maxZ, pool).lit();
    }

    /**
     * As above. dark: chunks that take part without giving light (see the class). reached: chunks whose
     * light in the engines is wanted beside the lit chunks' own (see Result).
     */
    static Result compute(ServerLevel level, Long2ObjectMap<ProtoChunk> chunks, LongSet dark, LongSet reached,
                          int minX, int minZ, int maxX, int maxZ, ExecutorService pool) {
        List<int[]> tiles = new ArrayList<>();
        for (int z = minZ; z <= maxZ; z += TILE) {
            for (int x = minX; x <= maxX; x += TILE) {
                tiles.add(new int[]{x, z, Math.min(x + TILE - 1, maxX), Math.min(z + TILE - 1, maxZ)});
            }
        }
        // Every chunk's sky light sources (INITIALIZE_LIGHT) before any tile reads its neighbors'.
        List<CompletableFuture<?>> sources = new ArrayList<>(tiles.size());
        for (int[] tile : tiles) {
            sources.add(CompletableFuture.runAsync(() -> forEach(chunks, tile, 0, ProtoChunk::initializeLightSources), pool));
        }
        CompletableFuture.allOf(sources.toArray(new CompletableFuture[0])).join();
        List<CompletableFuture<TileLight>> lit = new ArrayList<>(tiles.size());
        for (int[] tile : tiles) {
            // A tile of dark chunks only lights nothing: what reaches them comes from the tiles of the lit chunks beside them.
            boolean[] any = new boolean[1];
            forEach(chunks, tile, 0, chunk -> any[0] |= !dark.contains(chunk.getPos().pack()));
            if (any[0]) lit.add(CompletableFuture.supplyAsync(() -> lightTile(level, chunks, tile, dark, reached), pool));
        }
        List<ChunkLight> result = new ArrayList<>(chunks.size());
        Map<Long, ChunkLight> into = new HashMap<>();
        for (CompletableFuture<TileLight> tile : lit) {
            TileLight light = tile.join();
            result.addAll(light.lit());
            for (ChunkLight one : light.reached()) into.merge(one.chunk().getPos().pack(), one, GridLight::brighter);
        }
        return new Result(result, into);
    }

    private record TileLight(List<ChunkLight> lit, List<ChunkLight> reached) {}

    private static void forEach(Long2ObjectMap<ProtoChunk> chunks, int[] tile, int ring, Consumer<ProtoChunk> action) {
        for (int z = tile[1] - ring; z <= tile[3] + ring; z++) {
            for (int x = tile[0] - ring; x <= tile[2] + ring; x++) {
                ProtoChunk chunk = chunks.get(ChunkPos.pack(x, z));
                if (chunk != null) action.accept(chunk);
            }
        }
    }

    /** The tile's light from its own engine over the tile and its ring, the sequence the server's engine runs. */
    private static TileLight lightTile(ServerLevel level, Long2ObjectMap<ProtoChunk> chunks, int[] tile,
                                       LongSet dark, LongSet reached) {
        int minX = tile[0] - 1;
        int minZ = tile[1] - 1;
        int maxX = tile[2] + 1;
        int maxZ = tile[3] + 1;
        LevelLightEngine engine = new LevelLightEngine(new LightChunkGetter() {
            @Override
            public LightChunk getChunkForLighting(int x, int z) {
                return x >= minX && x <= maxX && z >= minZ && z <= maxZ ? chunks.get(ChunkPos.pack(x, z)) : null;
            }

            @Override
            public BlockGetter getLevel() {
                return level;
            }
        }, true, level.dimensionType().hasSkyLight());
        List<ProtoChunk> area = new ArrayList<>();
        forEach(chunks, tile, 1, area::add);
        // ThreadedLevelLightEngine#initializeLight of a chunk not lit yet.
        for (ProtoChunk chunk : area) {
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < chunk.getSectionsCount(); i++) {
                if (!sections[i].hasOnlyAir()) {
                    engine.updateSectionStatus(SectionPos.of(chunk.getPos(), level.getSectionYFromSectionIndex(i)), false);
                }
            }
        }
        runUpdates(engine);
        for (ProtoChunk chunk : area) {
            engine.setLightEnabled(chunk.getPos(), false);
            engine.retainData(chunk.getPos(), false);
        }
        // Its lightChunk. A dark chunk stays as initializeLight left it, as the server keeps a chunk it has not lit.
        for (ProtoChunk chunk : area) {
            if (!dark.contains(chunk.getPos().pack())) engine.propagateLightSources(chunk.getPos());
        }
        runUpdates(engine);

        List<ChunkLight> result = new ArrayList<>();
        forEach(chunks, tile, 0, chunk -> {
            if (!dark.contains(chunk.getPos().pack())) result.add(lightOf(engine, chunk));
        });
        List<ChunkLight> into = new ArrayList<>();
        if (!reached.isEmpty()) {
            for (ProtoChunk chunk : area) {
                if (reached.contains(chunk.getPos().pack())) into.add(lightOf(engine, chunk));
            }
        }
        return new TileLight(result, into);
    }

    private static ChunkLight lightOf(LevelLightEngine engine, ProtoChunk chunk) {
        int bottom = engine.getMinLightSection();
        int count = engine.getLightSectionCount();
        DataLayer[] block = new DataLayer[count];
        DataLayer[] sky = new DataLayer[count];
        for (int i = 0; i < count; i++) {
            SectionPos section = SectionPos.of(chunk.getPos(), bottom + i);
            block[i] = engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(section);
            sky[i] = engine.getLayerListener(LightLayer.SKY).getDataLayerData(section);
        }
        return new ChunkLight(chunk, block, sky);
    }

    /** Per block the brighter of two engines' light for one chunk. */
    static ChunkLight brighter(ChunkLight a, ChunkLight b) {
        DataLayer[] block = new DataLayer[a.block().length];
        DataLayer[] sky = new DataLayer[a.sky().length];
        for (int i = 0; i < block.length; i++) {
            block[i] = brighter(a.block()[i], b.block()[i]);
            sky[i] = brighter(a.sky()[i], b.sky()[i]);
        }
        return new ChunkLight(a.chunk(), block, sky);
    }

    static DataLayer brighter(DataLayer a, DataLayer b) {
        if (a == null || a.isEmpty()) return b;
        if (b == null || b.isEmpty()) return a;
        return new DataLayer(brighter(a.getData(), b.getData()));
    }

    /** Two light layers' bytes (two blocks a byte), per block the brighter; a new array. */
    static byte[] brighter(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < out.length; i++) {
            int low = Math.max(a[i] & 0x0F, b[i] & 0x0F);
            int high = Math.max(a[i] & 0xF0, b[i] & 0xF0);
            out[i] = (byte) (high | low);
        }
        return out;
    }

    private static void runUpdates(LevelLightEngine engine) {
        do {
            engine.runLightUpdates();
        } while (engine.hasLightWork());
    }
}
