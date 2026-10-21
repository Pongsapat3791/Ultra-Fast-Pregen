package com.example.pregen;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.ArrayList;
import java.util.List;
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
 */
final class GridLight {
    /** On unless -Dfastpregen.parallelLight=false. */
    static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("fastpregen.parallelLight", "true"));
    /** Chunks per side of a tile: a tile for every worker several times over; the ring adds half again. */
    private static final int TILE = 8;

    /** One chunk's light by light section, the lowest first; null where the engine keeps no layer. */
    record ChunkLight(ProtoChunk chunk, DataLayer[] block, DataLayer[] sky) {}

    private GridLight() {}

    /** Lights the chunks, all of which lie in the box, on the pool. */
    static List<ChunkLight> compute(ServerLevel level, Long2ObjectMap<ProtoChunk> chunks,
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
        List<CompletableFuture<List<ChunkLight>>> lit = new ArrayList<>(tiles.size());
        for (int[] tile : tiles) lit.add(CompletableFuture.supplyAsync(() -> lightTile(level, chunks, tile), pool));
        List<ChunkLight> result = new ArrayList<>(chunks.size());
        for (CompletableFuture<List<ChunkLight>> tile : lit) result.addAll(tile.join());
        return result;
    }

    private static void forEach(Long2ObjectMap<ProtoChunk> chunks, int[] tile, int ring, Consumer<ProtoChunk> action) {
        for (int z = tile[1] - ring; z <= tile[3] + ring; z++) {
            for (int x = tile[0] - ring; x <= tile[2] + ring; x++) {
                ProtoChunk chunk = chunks.get(ChunkPos.pack(x, z));
                if (chunk != null) action.accept(chunk);
            }
        }
    }

    /** The tile's light from its own engine over the tile and its ring, the sequence the server's engine runs. */
    private static List<ChunkLight> lightTile(ServerLevel level, Long2ObjectMap<ProtoChunk> chunks, int[] tile) {
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
        // Its lightChunk.
        for (ProtoChunk chunk : area) engine.propagateLightSources(chunk.getPos());
        runUpdates(engine);

        int bottom = engine.getMinLightSection();
        int count = engine.getLightSectionCount();
        List<ChunkLight> result = new ArrayList<>();
        forEach(chunks, tile, 0, chunk -> {
            DataLayer[] block = new DataLayer[count];
            DataLayer[] sky = new DataLayer[count];
            for (int i = 0; i < count; i++) {
                SectionPos section = SectionPos.of(chunk.getPos(), bottom + i);
                block[i] = engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(section);
                sky[i] = engine.getLayerListener(LightLayer.SKY).getDataLayerData(section);
            }
            result.add(new ChunkLight(chunk, block, sky));
        });
        return result;
    }

    private static void runUpdates(LevelLightEngine engine) {
        do {
            engine.runLightUpdates();
        } while (engine.hasLightWork());
    }
}
