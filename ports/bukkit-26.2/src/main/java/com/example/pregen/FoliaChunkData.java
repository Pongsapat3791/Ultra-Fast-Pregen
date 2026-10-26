package com.example.pregen;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.BelowZeroRetrogen;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * A batch chunk's saved data on Folia. Folia's SerializableChunkData#copyOf reads the game time of
 * the region the calling thread ticks, and this plugin's workers tick none; this gathers the same
 * data as that copyOf does, with the level's own game time (a proto chunk's scheduled ticks do not
 * depend on it). Its light comes from the batch (see InMemoryChunkGrid#withLight), so sections are
 * taken without light.
 */
final class FoliaChunkData {
    /** Paper's SerializableChunkData adds the chunk's plugin data (persistent data container) as a last component. */
    private static final MethodHandle CREATE = constructor();
    private static final MethodHandle PACK_STRUCTURES = packStructures();

    private FoliaChunkData() {}

    private static MethodHandle constructor() {
        try {
            return MethodHandles.publicLookup().findConstructor(SerializableChunkData.class, MethodType.methodType(void.class,
                    PalettedContainerFactory.class, ChunkPos.class, int.class, long.class, long.class, ChunkStatus.class,
                    BlendingData.Packed.class, BelowZeroRetrogen.class, UpgradeData.class, long[].class, Map.class,
                    ChunkAccess.PackedTicks.class, ShortList[].class, boolean.class, List.class, List.class, List.class,
                    CompoundTag.class, Tag.class));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("This server's SerializableChunkData has an unexpected shape", failure);
        }
    }

    private static MethodHandle packStructures() {
        try {
            return MethodHandles.privateLookupIn(SerializableChunkData.class, MethodHandles.lookup()).findStatic(
                    SerializableChunkData.class, "packStructureData", MethodType.methodType(CompoundTag.class,
                            StructurePieceSerializationContext.class, ChunkPos.class, Map.class, Map.class));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("This server's SerializableChunkData has no packStructureData", failure);
        }
    }

    /** The chunk's data as SerializableChunkData#copyOf gathers it, written as a region file stores it. */
    static CompoundTag write(ServerLevel level, ProtoChunk chunk) {
        ChunkPos pos = chunk.getPos();
        List<SerializableChunkData.SectionData> sections = new ArrayList<>();
        LevelChunkSection[] chunkSections = chunk.getSections();
        for (int i = 0; i < chunkSections.length; i++) {
            sections.add(new SerializableChunkData.SectionData(chunk.getSectionYFromSectionIndex(i), chunkSections[i].copy(), null, null));
        }
        List<CompoundTag> blockEntities = new ArrayList<>(chunk.getBlockEntitiesPos().size());
        for (BlockPos blockPos : chunk.getBlockEntitiesPos()) {
            CompoundTag blockEntity = chunk.getBlockEntityNbtForSaving(blockPos, level.registryAccess());
            if (blockEntity != null) blockEntities.add(blockEntity);
        }
        List<CompoundTag> entities = new ArrayList<>(chunk.getEntities());
        CarvingMask carvingMask = chunk.getCarvingMask();
        Map<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
        for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            if (chunk.getPersistedStatus().heightmapsAfter().contains(entry.getKey())) {
                heightmaps.put(entry.getKey(), entry.getValue().getRawData().clone());
            }
        }
        ShortList[] postProcessing = new ShortList[chunk.getPostProcessing().length];
        for (int i = 0; i < postProcessing.length; i++) {
            ShortList list = chunk.getPostProcessing()[i];
            postProcessing[i] = list != null && !list.isEmpty() ? new ShortArrayList(list) : null;
        }
        long gameTime = level.getGameTime();
        BlendingData blending = chunk.getBlendingData();
        try {
            Map<Structure, StructureStart> starts = chunk.getAllStarts();
            Map<Structure, LongSet> references = chunk.getAllReferences();
            CompoundTag structures = (CompoundTag) PACK_STRUCTURES.invoke(StructurePieceSerializationContext.fromLevel(level), pos,
                    starts, references);
            SerializableChunkData data = (SerializableChunkData) CREATE.invoke(level.palettedContainerFactory(), pos,
                    chunk.getMinSectionY(), gameTime, chunk.getInhabitedTime(), chunk.getPersistedStatus(),
                    blending != null ? blending.pack() : null, chunk.getBelowZeroRetrogen(), chunk.getUpgradeData().copy(),
                    carvingMask != null ? carvingMask.toArray() : null, heightmaps, chunk.getTicksForSerialization(gameTime),
                    postProcessing, chunk.isLightCorrect(), sections, entities, blockEntities, structures, (Tag) null);
            return data.write();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }
}
