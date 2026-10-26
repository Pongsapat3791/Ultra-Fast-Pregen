package com.example.pregen;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.lighting.SkyLightEngine;
import net.minecraft.world.level.material.FluidState;

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
    /** Chunks per side of a tile: a tile for every worker several times over; the ring adds half again. */
    private static final int TILE = 8;

    /** One chunk's light by light section, the lowest first; null where the engine keeps no layer. */
    record ChunkLight(ProtoChunk chunk, DataLayer[] block, DataLayer[] sky) {}

    private GridLight() {}

    /**
     * Lights the chunks, all of which lie in the box, on the pool, and returns the light of those
     * {@code keep} selects; the others only pass light on to them.
     */
    static List<ChunkLight> compute(ServerLevel level, Long2ObjectMap<ProtoChunk> chunks, LongPredicate keep,
                                    int minX, int minZ, int maxX, int maxZ, ExecutorService pool) {
        List<int[]> tiles = new ArrayList<>();
        for (int z = minZ; z <= maxZ; z += TILE) {
            for (int x = minX; x <= maxX; x += TILE) {
                tiles.add(new int[]{x, z, Math.min(x + TILE - 1, maxX), Math.min(z + TILE - 1, maxZ)});
            }
        }
        Long2ObjectMap<LightChunk> forLighting = forLighting(level, chunks);
        // Every chunk's sky light sources (INITIALIZE_LIGHT) before any tile reads its neighbors'.
        List<CompletableFuture<?>> sources = new ArrayList<>(tiles.size());
        for (int[] tile : tiles) {
            sources.add(CompletableFuture.runAsync(() -> forEach(chunks, tile, 0, chunk -> {
                if (forLighting.get(chunk.getPos().pack()) instanceof SourcedChunk sourced) {
                    sourced.sources().fillFrom(chunk);
                } else {
                    chunk.initializeLightSources();
                }
            }), pool));
        }
        CompletableFuture.allOf(sources.toArray(new CompletableFuture[0])).join();
        List<CompletableFuture<List<ChunkLight>>> lit = new ArrayList<>(tiles.size());
        for (int[] tile : tiles) lit.add(CompletableFuture.supplyAsync(() -> lightTile(level, chunks, forLighting, keep, tile), pool));
        List<ChunkLight> result = new ArrayList<>(chunks.size());
        for (CompletableFuture<List<ChunkLight>> tile : lit) result.addAll(tile.join());
        return result;
    }

    /**
     * The chunks as the light engines read them. Paper's chunks keep no sky light sources (its own
     * light engine finds them by itself), and Minecraft's sky light engine takes a chunk without
     * them for an empty one, with sky light 15 all the way down: such chunks get sources here.
     */
    private static Long2ObjectMap<LightChunk> forLighting(ServerLevel level, Long2ObjectMap<ProtoChunk> chunks) {
        Long2ObjectMap<LightChunk> result = new Long2ObjectOpenHashMap<>(chunks.size());
        for (Long2ObjectMap.Entry<ProtoChunk> entry : chunks.long2ObjectEntrySet()) {
            ProtoChunk chunk = entry.getValue();
            result.put(entry.getLongKey(), chunk.getSkyLightSources() != null ? chunk
                    : new SourcedChunk(chunk, new ChunkSkyLightSources(level)));
        }
        return result;
    }

    /** A chunk with sky light sources of its own, for the light engines (see forLighting). */
    private record SourcedChunk(ProtoChunk chunk, ChunkSkyLightSources sources) implements LightChunk {
        @Override
        public void findBlockLightSources(BiConsumer<BlockPos, BlockState> consumer) {
            chunk.findBlockLightSources(consumer);
        }

        @Override
        public ChunkSkyLightSources getSkyLightSources() {
            return sources;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return chunk.getBlockEntity(pos);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return chunk.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return chunk.getFluidState(pos);
        }

        @Override
        public int getHeight() {
            return chunk.getHeight();
        }

        @Override
        public int getMinY() {
            return chunk.getMinY();
        }
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
    private static List<ChunkLight> lightTile(ServerLevel level, Long2ObjectMap<ProtoChunk> chunks,
                                              Long2ObjectMap<LightChunk> forLighting, LongPredicate keep, int[] tile) {
        int minX = tile[0] - 1;
        int minZ = tile[1] - 1;
        int maxX = tile[2] + 1;
        int maxZ = tile[3] + 1;
        Engines engine = new Engines(new LightChunkGetter() {
            @Override
            public LightChunk getChunkForLighting(int x, int z) {
                return x >= minX && x <= maxX && z >= minZ && z <= maxZ ? forLighting.get(ChunkPos.pack(x, z)) : null;
            }

            @Override
            public BlockGetter getLevel() {
                return level;
            }
        }, level.dimensionType().hasSkyLight());
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
        engine.runUpdates();
        for (ProtoChunk chunk : area) {
            engine.setLightEnabled(chunk.getPos(), false);
            engine.retainData(chunk.getPos(), false);
        }
        // Its lightChunk.
        for (ProtoChunk chunk : area) engine.propagateLightSources(chunk.getPos());
        engine.runUpdates();

        // LevelLightEngine's light sections: one below and one above the level's.
        int bottom = level.getMinSectionY() - 1;
        int count = level.getSectionsCount() + 2;
        List<ChunkLight> result = new ArrayList<>();
        forEach(chunks, tile, 0, chunk -> {
            if (!keep.test(chunk.getPos().pack())) return;
            DataLayer[] block = new DataLayer[count];
            DataLayer[] sky = new DataLayer[count];
            for (int i = 0; i < count; i++) {
                SectionPos section = SectionPos.of(chunk.getPos(), bottom + i);
                block[i] = engine.block.getDataLayerData(section);
                sky[i] = engine.sky != null ? engine.sky.getDataLayerData(section) : null;
            }
            result.add(new ChunkLight(chunk, block, sky));
        });
        return result;
    }

    /**
     * Minecraft's own block and sky light engines, driven as LevelLightEngine drives them. Paper
     * replaces what LevelLightEngine runs with its own light engine (Starlight), which keeps light
     * in the chunks themselves; these keep it to themselves, so tiles never share it.
     */
    private static final class Engines {
        final BlockLightEngine block;
        final SkyLightEngine sky;

        Engines(LightChunkGetter chunks, boolean hasSkyLight) {
            block = new BlockLightEngine(chunks);
            sky = hasSkyLight ? new SkyLightEngine(chunks) : null;
        }

        void updateSectionStatus(SectionPos pos, boolean isEmpty) {
            block.updateSectionStatus(pos, isEmpty);
            if (sky != null) sky.updateSectionStatus(pos, isEmpty);
        }

        void setLightEnabled(ChunkPos pos, boolean enable) {
            block.setLightEnabled(pos, enable);
            if (sky != null) sky.setLightEnabled(pos, enable);
        }

        void retainData(ChunkPos pos, boolean retain) {
            block.retainData(pos, retain);
            if (sky != null) sky.retainData(pos, retain);
        }

        void propagateLightSources(ChunkPos pos) {
            block.propagateLightSources(pos);
            if (sky != null) sky.propagateLightSources(pos);
        }

        /** LevelLightEngine#runLightUpdates until no work is left. */
        void runUpdates() {
            do {
                block.runLightUpdates();
                if (sky != null) sky.runLightUpdates();
            } while (block.hasLightWork() || sky != null && sky.hasLightWork());
        }
    }
}
