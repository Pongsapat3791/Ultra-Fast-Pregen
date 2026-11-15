package com.example.pregen;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import org.slf4j.Logger;

/**
 * Hands each chunk a batch saves to the LoD mods that keep their own copy of the world, so the
 * far terrain they draw is there without anyone flying over it: Voxy (its LoD world in
 * singleplayer, where the client runs it) and Distant Horizons. Both are optional and reached by
 * reflection through the calls they offer other mods; when neither is loaded nothing happens.
 *
 * Both queue the chunks and process them on their own threads, Voxy without a limit and DH
 * dropping the update furthest from the player once its queue is full. A batch therefore waits
 * while either queue is long, which bounds memory and loses nothing; the time spent waiting is
 * reported per batch. Chunks the server holds are left out: the server moved their sections into
 * its loaded chunks, and both mods pick up loaded chunks themselves.
 */
final class LodExport {
    private static final Logger LOGGER = PreGenMod.LOGGER;
    /** Sections waiting in Voxy's ingest queue before a batch waits for it. */
    private static final int VOXY_QUEUE_LIMIT = 4096;
    /** Chunks waiting in DH's update queues; DH starts dropping at 500 per level. */
    private static final int DH_QUEUE_LIMIT = 256;
    /** A queue that does not shrink for this long is given up on for the rest of the run. */
    static volatile long stallNanos = 60_000_000_000L;

    private final ServerLevel level;
    private final Voxy voxy;
    private final DistantHorizons dh;
    private long waitNanos;
    /** Set when a mod refused chunks or stopped taking them: it gets no more this run. */
    private boolean voxyStopped;
    private boolean dhStopped;
    private long sentToVoxy;
    private long sentToDh;

    private LodExport(ServerLevel level, Voxy voxy, DistantHorizons dh) {
        this.level = level;
        this.voxy = voxy;
        this.dh = dh;
    }

    /** The LoD mods loaded now; empty when there are none. */
    static LodExport forLevel(ServerLevel level) {
        return new LodExport(level, Voxy.find(), DistantHorizons.find());
    }

    /** "Voxy, Distant Horizons", or null when no LoD mod is loaded. */
    String describe() {
        List<String> names = new ArrayList<>();
        if (voxy != null) names.add("Voxy");
        if (dh != null) names.add("Distant Horizons");
        return names.isEmpty() ? null : String.join(", ", names);
    }

    void finish() {
        List<String> sent = new ArrayList<>();
        if (voxy != null) sent.add(sentToVoxy + " to Voxy");
        if (dh != null) sent.add(sentToDh + " to Distant Horizons");
        LOGGER.info("[PreGen] Saved chunks sent to LoD mods: {} (waited {}s for them in all)", String.join(", ", sent),
                String.format(java.util.Locale.ROOT, "%.1f", waitNanos / 1e9));
    }

    /**
     * Sends the saved chunks of a lit grid, before it is disposed (their light is still in the
     * level's light engine). Returns the seconds spent waiting for the mods' queues.
     */
    double export(InMemoryChunkGrid grid, List<ChunkPos> saved, LongSet heldByServer) {
        boolean toVoxy = voxy != null && !voxyStopped && voxy.ready(level);
        Object dhLevel = dh != null && !dhStopped ? dh.levelFor(level) : null;
        if (!toVoxy && dhLevel == null) return 0;
        long waitedBefore = waitNanos;
        LayerLightEventListener blockLight = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        LayerLightEventListener skyLight = level.getLightEngine().getLayerListener(LightLayer.SKY);
        for (ChunkPos pos : saved) {
            if (heldByServer.contains(pos.pack())) continue;
            ProtoChunk chunk = grid.getChunk(pos.x(), pos.z());
            if (chunk == null) continue;
            if (toVoxy) {
                if (waitFor(voxy::queued, VOXY_QUEUE_LIMIT, "Voxy") && voxy.ingest(level, chunk, blockLight, skyLight)) {
                    sentToVoxy++;
                } else {
                    toVoxy = false;
                    voxyStopped = true;
                }
            }
            if (dhLevel != null) {
                if (waitFor(dh::queued, DH_QUEUE_LIMIT, "Distant Horizons") && dh.overwrite(dhLevel, chunk, level)) {
                    sentToDh++;
                } else {
                    dhLevel = null;
                    dhStopped = true;
                }
            }
            if (!toVoxy && dhLevel == null) break;
        }
        return (waitNanos - waitedBefore) / 1e9;
    }

    private interface Count {
        int get() throws ReflectiveOperationException;
    }

    /** Waits while the queue is over the limit; false when it cannot be read or stopped moving. */
    private boolean waitFor(Count queued, int limit, String mod) {
        try {
            int count = queued.get();
            if (count <= limit) return true;
            long started = System.nanoTime();
            long lastProgress = started;
            int lowest = count;
            while (count > limit) {
                Thread.sleep(5);
                count = queued.get();
                long now = System.nanoTime();
                if (count < lowest) {
                    lowest = count;
                    lastProgress = now;
                } else if (now - lastProgress > stallNanos) {
                    LOGGER.warn("[PreGen] {} stopped taking chunks ({} queued for a minute); not sending it more this run", mod, count);
                    waitNanos += now - started;
                    return false;
                }
            }
            waitNanos += System.nanoTime() - started;
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            LOGGER.warn("[PreGen] Could not read {}'s queue; not sending it more this run", mod, failure);
            return false;
        }
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, LodExport.class.getClassLoader());
    }

    /** Voxy's ingest calls, as Voxy World Gen uses them: whole sections with their light. */
    private static final class Voxy {
        private final Method instance;
        private final Method ingestEnabled;
        private final Method ingestService;
        private final Method taskCount;
        private final Method identifierOf;
        private final Method rawIngest;

        private Voxy(Method instance, Method ingestEnabled, Method ingestService, Method taskCount, Method identifierOf,
                     Method rawIngest) {
            this.instance = instance;
            this.ingestEnabled = ingestEnabled;
            this.ingestService = ingestService;
            this.taskCount = taskCount;
            this.identifierOf = identifierOf;
            this.rawIngest = rawIngest;
        }

        static Voxy find() {
            try {
                Class<?> common = type("me.cortex.voxy.commonImpl.VoxyCommon");
                Class<?> instance = type("me.cortex.voxy.commonImpl.VoxyInstance");
                Class<?> service = type("me.cortex.voxy.common.world.service.VoxelIngestService");
                Class<?> identifier = type("me.cortex.voxy.commonImpl.WorldIdentifier");
                return new Voxy(common.getMethod("getInstance"), instance.getMethod("isIngestEnabled", identifier),
                        instance.getMethod("getIngestService"), service.getMethod("getTaskCount"), identifier.getMethod("of", Level.class),
                        service.getMethod("rawIngest", identifier, LevelChunkSection.class, int.class, int.class, int.class,
                                DataLayer.class, DataLayer.class));
            } catch (ClassNotFoundException absent) {
                return null;
            } catch (ReflectiveOperationException | LinkageError changed) {
                LOGGER.warn("[PreGen] Voxy is loaded but its ingest calls changed; generated chunks are not sent to it", changed);
                return null;
            }
        }

        /**
         * Voxy runs only in a client: on a dedicated server, or with Voxy off, it has no instance.
         * With its ingest off for this world, Voxy takes nothing either.
         */
        boolean ready(Level level) {
            try {
                Object voxy = instance.invoke(null);
                Object world = identifierOf.invoke(null, level);
                return voxy != null && world != null && (boolean) ingestEnabled.invoke(voxy, world);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return false;
            }
        }

        int queued() throws ReflectiveOperationException {
            Object voxy = instance.invoke(null);
            return voxy == null ? 0 : (int) taskCount.invoke(ingestService.invoke(voxy));
        }

        /**
         * Queues the chunk's sections. Voxy answers false for a section it skips, which is not an
         * error; only a failed call stops sending.
         */
        boolean ingest(Level level, ProtoChunk chunk, LayerLightEventListener blockLight, LayerLightEventListener skyLight) {
            try {
                Object world = identifierOf.invoke(null, level);
                LevelChunkSection[] sections = chunk.getSections();
                ChunkPos pos = chunk.getPos();
                for (int index = 0; index < sections.length; index++) {
                    LevelChunkSection section = sections[index];
                    if (section.hasOnlyAir()) continue;
                    int sectionY = chunk.getSectionYFromSectionIndex(index);
                    SectionPos at = SectionPos.of(pos.x(), sectionY, pos.z());
                    rawIngest.invoke(null, world, section, pos.x(), sectionY, pos.z(),
                            blockLight.getDataLayerData(at), skyLight.getDataLayerData(at));
                }
                return true;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                LOGGER.warn("[PreGen] Sending a chunk to Voxy failed; not sending it more this run", failure);
                return false;
            }
        }
    }

    /** Distant Horizons' API: overwriteChunkDataAsync, the call meant for chunks other mods generate. */
    private static final class DistantHorizons {
        private final Field worldProxy;
        private final Field terrainRepo;
        private final Method worldLoaded;
        private final Method levels;
        private final Method wrappedObject;
        private final Method overwrite;
        private final Field resultSuccess;
        private final Field resultMessage;
        private final Method queuedCount;
        private final Object updateManager;

        private DistantHorizons(Class<?> delayed, Class<?> proxy, Class<?> unsafe, Class<?> repo, Class<?> levelType,
                                Class<?> result, Method queuedCount, Object updateManager) throws ReflectiveOperationException {
            worldProxy = delayed.getField("worldProxy");
            terrainRepo = delayed.getField("terrainRepo");
            worldLoaded = proxy.getMethod("worldLoaded");
            levels = proxy.getMethod("getAllLoadedLevelWrappers");
            wrappedObject = unsafe.getMethod("getWrappedMcObject");
            overwrite = repo.getMethod("overwriteChunkDataAsync", levelType, Object[].class);
            resultSuccess = result.getField("success");
            resultMessage = result.getField("message");
            this.queuedCount = queuedCount;
            this.updateManager = updateManager;
        }

        static DistantHorizons find() {
            try {
                Class<?> delayed = type("com.seibel.distanthorizons.api.DhApi$Delayed");
                Method queuedCount = null;
                Object updateManager = null;
                try {
                    // Not part of DH's API; without it a batch cannot wait for DH and some updates may drop.
                    Class<?> shared = type("com.seibel.distanthorizons.core.api.internal.SharedApi");
                    updateManager = shared.getField("WORLD_CHUNK_UPDATE_MANAGER").get(null);
                    queuedCount = updateManager.getClass().getMethod("getTotalQueuedCount");
                } catch (ReflectiveOperationException | LinkageError absent) {
                    LOGGER.warn("[PreGen] Distant Horizons' update queue is not readable; chunks are sent without waiting for it");
                }
                return new DistantHorizons(delayed,
                        type("com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy"),
                        type("com.seibel.distanthorizons.api.interfaces.IDhApiUnsafeWrapper"),
                        type("com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo"),
                        type("com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper"),
                        type("com.seibel.distanthorizons.api.objects.DhApiResult"),
                        queuedCount, updateManager);
            } catch (ClassNotFoundException absent) {
                return null;
            } catch (ReflectiveOperationException | LinkageError changed) {
                LOGGER.warn("[PreGen] Distant Horizons is loaded but its API changed; generated chunks are not sent to it", changed);
                return null;
            }
        }

        /** DH's level for this server level, or null while DH has no world loaded. */
        Object levelFor(ServerLevel level) {
            try {
                Object proxy = worldProxy.get(null);
                if (proxy == null || !(boolean) worldLoaded.invoke(proxy)) return null;
                for (Object wrapper : (Iterable<?>) levels.invoke(proxy)) {
                    if (wrappedObject.invoke(wrapper) == level) return wrapper;
                }
                return null;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return null;
            }
        }

        int queued() throws ReflectiveOperationException {
            return queuedCount == null ? 0 : (int) queuedCount.invoke(updateManager);
        }

        boolean overwrite(Object dhLevel, ProtoChunk chunk, ServerLevel level) {
            try {
                Object result = overwrite.invoke(terrainRepo.get(null), dhLevel, new Object[]{chunk, level});
                if ((boolean) resultSuccess.get(result)) return true;
                LOGGER.warn("[PreGen] Distant Horizons refused a chunk ({}); not sending it more this run", resultMessage.get(result));
            } catch (ReflectiveOperationException | RuntimeException failure) {
                LOGGER.warn("[PreGen] Sending a chunk to Distant Horizons failed; not sending it more this run", failure);
            }
            return false;
        }
    }
}
