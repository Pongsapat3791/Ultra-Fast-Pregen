package com.example.pregen.mixin;

import com.example.pregen.LitChunkTaker;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.objects.ObjectList;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * A chunk lit on this mod's workers, given to the server's light engine as two tasks instead of
 * about fifty. Minecraft queues a lit chunk's light one task per section and layer at the highest
 * priority, so a whole batch's light sat in the engine's queue before any chunk was started, and
 * the engine walks that queue (and copies its section map) at every update, one per thousand
 * tasks: its one thread spent most of a batch's light time walking. Here the same calls on the
 * engine, in the same order for the chunk, run as one task before the update (keep the data,
 * queue the layers, mark the sections that have blocks) and one after it (light on, data no longer
 * kept, light correct), at the priority Minecraft gives a chunk's own light steps.
 */
@Mixin(ThreadedLevelLightEngine.class)
public abstract class ThreadedLevelLightEngineMixin extends LevelLightEngine implements LitChunkTaker {
    @Shadow @Final private ChunkMap chunkMap;
    @Shadow @Final private ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> sorterMailbox;
    /** Pairs of a task type and a task; the type is private to the engine. */
    @SuppressWarnings("rawtypes")
    @Shadow @Final private ObjectList lightTasks;

    /** The engine's task types, before and after an update, or null when they are not as in Minecraft. */
    @Unique private static final Object[] pregen$TASK_TYPES = pregen$taskTypes();

    private ThreadedLevelLightEngineMixin(LightChunkGetter chunks, boolean blockLight, boolean skyLight) {
        super(chunks, blockLight, skyLight);
    }

    @Shadow
    private void runUpdate() {
        throw new AssertionError();
    }

    @Unique
    private static Object[] pregen$taskTypes() {
        try {
            for (Class<?> inner : ThreadedLevelLightEngine.class.getDeclaredClasses()) {
                Object[] constants = inner.isEnum() ? inner.getEnumConstants() : null;
                if (constants != null && constants.length == 2 && "PRE_UPDATE".equals(((Enum<?>) constants[0]).name())
                        && "POST_UPDATE".equals(((Enum<?>) constants[1]).name())) {
                    return constants;
                }
            }
        } catch (RuntimeException | LinkageError unknown) {
            // Not Minecraft's task types: chunks are given the usual way.
        }
        return null;
    }

    /** ThreadedLevelLightEngine#addTask, with the type as found above. */
    @Unique
    @SuppressWarnings("unchecked")
    private void pregen$addTask(long chunk, IntSupplier level, Object type, Runnable task) {
        sorterMailbox.tell(ChunkTaskPriorityQueueSorter.message(() -> {
            lightTasks.add(Pair.of(type, task));
            if (lightTasks.size() >= 1000) runUpdate();
        }, chunk, level));
    }

    @Override
    public CompletableFuture<?> pregen$takeLitChunk(ChunkAccess chunk, DataLayer[] block, DataLayer[] sky) {
        Object[] types = pregen$TASK_TYPES;
        if (types == null) return null;
        ChunkPos pos = chunk.getPos();
        long packed = pos.toLong();
        IntSupplier level = ((ChunkMapAccessor) chunkMap).pregen$getChunkQueueLevel(packed);
        int bottom = getMinLightSection();
        chunk.setLightCorrect(false);
        pregen$addTask(packed, level, types[0], () -> {
            super.retainData(pos, true);
            for (int i = 0; i < sky.length; i++) {
                SectionPos section = SectionPos.of(pos, bottom + i);
                // An empty block layer is what the engine starts any section with.
                DataLayer blockLayer = block[i];
                if (blockLayer != null && !blockLayer.isEmpty()) super.queueSectionData(LightLayer.BLOCK, section, blockLayer);
                if (sky[i] != null) super.queueSectionData(LightLayer.SKY, section, sky[i]);
            }
            LevelChunkSection[] sections = chunk.getSections();
            for (int index = 0; index < chunk.getSectionsCount(); index++) {
                if (!sections[index].hasOnlyAir()) {
                    super.updateSectionStatus(SectionPos.of(pos, levelHeightAccessor.getSectionYFromSectionIndex(index)), false);
                }
            }
        });
        CompletableFuture<Void> taken = new CompletableFuture<>();
        pregen$addTask(packed, level, types[1], () -> {
            try {
                super.setLightEnabled(pos, true);
                super.retainData(pos, false);
                chunk.setLightCorrect(true);
                taken.complete(null);
            } catch (Throwable failure) {
                taken.completeExceptionally(failure);
                throw failure;
            }
        });
        return taken;
    }
}
