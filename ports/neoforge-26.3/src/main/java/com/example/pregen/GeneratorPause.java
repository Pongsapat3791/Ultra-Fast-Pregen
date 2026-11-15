package com.example.pregen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;

/**
 * While a run lasts, other mods that generate chunks on their own in the background wait: they
 * would take CPU time from the run and finish chunks in its area meanwhile. Distant Horizons'
 * distant generation is switched off through its config API (as DH does itself while Chunky runs);
 * Voxy World Gen gets a pause check through the one it offers. Both are reached by reflection and
 * restored when the run ends; with neither loaded nothing happens.
 */
final class GeneratorPause {
    private static final Logger LOGGER = PreGenMod.LOGGER;
    private static final BooleanSupplier PAUSED = () -> true;

    private Object dhSetting;
    private Method dhClear;
    private Object voxyWorldGen;
    private Method voxyWorldGenSet;
    private BooleanSupplier voxyWorldGenBefore;

    /** Pauses what is loaded; the names of what was paused, or an empty list. */
    List<String> pause() {
        List<String> paused = new ArrayList<>();
        if (pauseDistantHorizons()) paused.add("Distant Horizons' world generation");
        if (pauseVoxyWorldGen()) paused.add("Voxy World Gen");
        return paused;
    }

    void resume() {
        if (dhSetting != null) {
            try {
                dhClear.invoke(dhSetting);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                LOGGER.warn("[PreGen] Could not resume Distant Horizons' world generation", failure);
            }
            dhSetting = null;
        }
        if (voxyWorldGen != null) {
            try {
                // Unless it was replaced meanwhile (Voxy World Gen's client sets its own on joining a world).
                if (currentVoxyPauseCheck() == PAUSED) voxyWorldGenSet.invoke(voxyWorldGen, voxyWorldGenBefore);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                LOGGER.warn("[PreGen] Could not resume Voxy World Gen", failure);
            }
            voxyWorldGen = null;
        }
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, GeneratorPause.class.getClassLoader());
    }

    private boolean pauseDistantHorizons() {
        try {
            Object configs = type("com.seibel.distanthorizons.api.DhApi$Delayed").getField("configs").get(null);
            if (configs == null) return false;
            Object generation = type("com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig")
                    .getMethod("worldGenerator").invoke(configs);
            Object setting = type("com.seibel.distanthorizons.api.interfaces.config.both.IDhApiWorldGenerationConfig")
                    .getMethod("enableDistantWorldGeneration").invoke(generation);
            Class<?> value = type("com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue");
            if (!(boolean) value.getMethod("setValue", Object.class, String.class).invoke(setting, false, PreGenMod.NAME)) {
                return false;
            }
            dhSetting = setting;
            dhClear = value.getMethod("clearValue");
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            LOGGER.warn("[PreGen] Could not pause Distant Horizons' world generation", failure);
            return false;
        }
    }

    private boolean pauseVoxyWorldGen() {
        try {
            Class<?> manager = type("com.ethan.voxyworldgenv2.core.ChunkGenerationManager");
            Object instance = manager.getMethod("getInstance").invoke(null);
            Field check = manager.getDeclaredField("pauseCheck");
            check.setAccessible(true);
            Method set = manager.getMethod("setPauseCheck", BooleanSupplier.class);
            voxyWorldGen = instance;
            voxyWorldGenSet = set;
            voxyWorldGenBefore = (BooleanSupplier) check.get(instance);
            set.invoke(instance, PAUSED);
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            voxyWorldGen = null;
            LOGGER.warn("[PreGen] Could not pause Voxy World Gen", failure);
            return false;
        }
    }

    private BooleanSupplier currentVoxyPauseCheck() throws ReflectiveOperationException {
        Field check = voxyWorldGen.getClass().getDeclaredField("pauseCheck");
        check.setAccessible(true);
        return (BooleanSupplier) check.get(voxyWorldGen);
    }
}
