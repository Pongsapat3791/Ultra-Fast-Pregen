package com.example.pregen;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.api.interfaces.config.both.IDhApiWorldGenerationConfig;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;

/**
 * State shared by the Voxy and Distant Horizons stand-ins in this source set (me.cortex.voxy.*,
 * com.seibel.distanthorizons.*). They carry only the calls LodExport makes, with the same names
 * and signatures as the real mods, so the check exercises LodExport's reflection and back-pressure
 * without either mod installed. They are test classes and never ship in the mod jar.
 */
public final class LodStubs {
    /** A section handed to Voxy. */
    public record Section(Level level, int x, int y, int z, DataLayer blockLight, DataLayer skyLight) {}

    /** Voxy has an instance (a client with Voxy on). */
    public static volatile boolean voxyRunning;
    public static volatile boolean voxyIngestEnabled;
    public static volatile IntSupplier voxyQueue = () -> 0;
    public static final List<Section> voxySections = new CopyOnWriteArrayList<>();

    /** Distant Horizons has a world loaded, whose level wraps {@link #dhLevel}. */
    public static volatile boolean dhLoaded;
    public static volatile Object dhLevel;
    public static volatile boolean dhRefuses;
    public static volatile IntSupplier dhQueue = () -> 0;
    public static final List<Object[]> dhOverwrites = new CopyOnWriteArrayList<>();
    public static final List<String> dhGenerationSets = new CopyOnWriteArrayList<>();
    public static final AtomicInteger dhGenerationCleared = new AtomicInteger();

    static {
        DhApi.Delayed.worldProxy = new IDhApiWorldProxy() {
            @Override
            public boolean worldLoaded() {
                return dhLoaded;
            }

            @Override
            public Iterable<IDhApiLevelWrapper> getAllLoadedLevelWrappers() {
                Object wrapped = dhLevel;
                return dhLoaded && wrapped != null ? List.of((IDhApiLevelWrapper) () -> wrapped) : List.of();
            }
        };
        DhApi.Delayed.terrainRepo = (IDhApiTerrainDataRepo) (level, chunkObjectArray) -> {
            dhOverwrites.add(chunkObjectArray);
            return dhRefuses ? DhApiResult.<Void>createFail("refused by the test") : DhApiResult.<Void>createSuccess();
        };
        IDhApiConfigValue<Boolean> distantGeneration = new IDhApiConfigValue<>() {
            @Override
            public boolean setValue(Boolean value, String source) {
                dhGenerationSets.add(value + "/" + source);
                return true;
            }

            @Override
            public boolean clearValue() {
                dhGenerationCleared.incrementAndGet();
                return true;
            }
        };
        IDhApiWorldGenerationConfig generation = () -> distantGeneration;
        DhApi.Delayed.configs = (IDhApiConfig) () -> generation;
    }

    private LodStubs() {}

    /** Everything off and forgotten, between scenarios. */
    public static void reset() {
        voxyRunning = false;
        voxyIngestEnabled = false;
        voxyQueue = () -> 0;
        voxySections.clear();
        dhLoaded = false;
        dhLevel = null;
        dhRefuses = false;
        dhQueue = () -> 0;
        dhOverwrites.clear();
        dhGenerationSets.clear();
        dhGenerationCleared.set(0);
    }
}
