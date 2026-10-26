package com.example.pregen;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Minecraft code that structures share between chunks, made safe for this plugin's workers. The mod
 * builds change it with mixins; a plugin cannot, so the same is done from outside. Minecraft runs
 * structure starts and features one chunk at a time, this plugin on every worker thread:
 *
 * - A template's palette caches its blocks per block type in a plain HashMap, which two chunks
 *   placing the same house template filled at once. The server's template store is wrapped so
 *   every template it loads gets a concurrent cache, and those loaded before get one too.
 * - StrongholdPieces lays a stronghold out through static fields; the chunks where a stronghold
 *   can start (its ring positions) create their structure starts one at a time (see
 *   strongholdChunks).
 *
 * When the template store cannot be wrapped (a server whose internals differ), structure starts
 * and features run one chunk at a time instead (see install).
 */
final class WorldgenSafety {
    private static final Field PALETTE_CACHE = paletteCache();

    private WorldgenSafety() {}

    private static Field paletteCache() {
        try {
            Field field = StructureTemplate.Palette.class.getDeclaredField("cache");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /**
     * Gives the server's structure templates thread-safe caches, now and for every template loaded
     * later. Returns null when done, or why structure starts and features must run one chunk at a time.
     */
    static synchronized String install(StructureTemplateManager templates) {
        if (PALETTE_CACHE == null) return "structure template palettes have no block cache field on this server";
        try {
            if (templates.structureRepository instanceof SafeTemplates) return null;
            Field repository = StructureTemplateManager.class.getDeclaredField("structureRepository");
            repository.setAccessible(true);
            SafeTemplates safe = new SafeTemplates();
            for (Map.Entry<Identifier, Optional<StructureTemplate>> entry : templates.structureRepository.entrySet()) {
                safe.put(entry.getKey(), entry.getValue());
            }
            repository.set(templates, safe);
            return null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return "the server's structure templates could not be made thread-safe (" + failure + ")";
        }
    }

    /** The template's palettes with concurrent block caches; already safe ones are left as they are. */
    private static Optional<StructureTemplate> makeSafe(Optional<StructureTemplate> template) {
        if (template == null || template.isEmpty()) return template;
        try {
            List<StructureTemplate.Palette> palettes = template.get().palettes;
            for (StructureTemplate.Palette palette : palettes) {
                Object cache = PALETTE_CACHE.get(palette);
                if (!(cache instanceof ConcurrentHashMap)) {
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> filled = (Map<Object, Object>) cache;
                    PALETTE_CACHE.set(palette, new ConcurrentHashMap<>(filled));
                }
            }
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
        return template;
    }

    /** The server's template store, giving every template it stores a thread-safe palette cache first. */
    private static final class SafeTemplates extends ConcurrentHashMap<Identifier, Optional<StructureTemplate>> {
        @Override
        public Optional<StructureTemplate> put(Identifier key, Optional<StructureTemplate> value) {
            return super.put(key, makeSafe(value));
        }

        @Override
        public Optional<StructureTemplate> putIfAbsent(Identifier key, Optional<StructureTemplate> value) {
            return super.putIfAbsent(key, makeSafe(value));
        }

        @Override
        public Optional<StructureTemplate> computeIfAbsent(Identifier key,
                                                           Function<? super Identifier, ? extends Optional<StructureTemplate>> load) {
            return super.computeIfAbsent(key, id -> makeSafe(load.apply(id)));
        }

        @Override
        public Optional<StructureTemplate> compute(Identifier key,
                                                   BiFunction<? super Identifier, ? super Optional<StructureTemplate>, ? extends Optional<StructureTemplate>> remap) {
            return super.compute(key, (id, old) -> makeSafe(remap.apply(id, old)));
        }
    }

    /**
     * Chunks where a structure placed in concentric rings (strongholds) can start; their structure
     * starts run one at a time (see InMemoryChunkGrid).
     */
    static LongSet strongholdChunks(ServerLevel level) {
        LongSet chunks = new LongOpenHashSet();
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            if (set.value().placement() instanceof ConcentricRingsStructurePlacement rings) {
                List<ChunkPos> positions = state.getRingPositionsFor(rings);
                if (positions != null) {
                    for (ChunkPos pos : positions) chunks.add(pos.pack());
                }
            }
        }
        return chunks;
    }
}
