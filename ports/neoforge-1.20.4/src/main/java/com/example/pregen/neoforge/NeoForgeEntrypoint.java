package com.example.pregen.neoforge;

import com.example.pregen.PreGenMod;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge entry point for Minecraft 1.20.2 to 1.20.4. Commands and everything else come from the
 * shared code and its mixins.
 *
 * <p>NeoForge moved its mod list's classes between these versions (a mod's information is
 * net.minecraftforge.forgespi's on 20.2 and 20.3, net.neoforged.neoforgespi's on 20.4), so the
 * few calls into the loader are made by name: one jar serves the three versions.
 */
@Mod(PreGenMod.MOD_ID)
public final class NeoForgeEntrypoint {
    public NeoForgeEntrypoint() {
        try {
            Class<?> modList = Class.forName("net.neoforged.fml.ModList");
            List<?> loaded = (List<?>) modList.getMethod("getMods").invoke(modList.getMethod("get").invoke(null));
            Class<?> modInfo = modInfoClass();
            Method modId = modInfo.getMethod("getModId");
            Method modVersion = modInfo.getMethod("getVersion");
            Method modProperties = modInfo.getMethod("getModProperties");
            Map<String, String> mods = new TreeMap<>();
            for (Object mod : loaded) mods.put((String) modId.invoke(mod), String.valueOf(modVersion.invoke(mod)));
            Class<?> paths = Class.forName("net.neoforged.fml.loading.FMLPaths");
            Path configDirectory = (Path) paths.getMethod("get").invoke(paths.getField("CONFIGDIR").get(null));
            PreGenMod.init(configDirectory, mods.getOrDefault(PreGenMod.MOD_ID, "unknown"));
            for (Object mod : loaded) readCompatibility((String) modId.invoke(mod), (Map<?, ?>) modProperties.invoke(mod));
            PreGenMod.platform("neoforge", mods);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("Ultra Fast Pregen does not know this version of NeoForge (" + failure + ")", failure);
        }
    }

    private static Class<?> modInfoClass() throws ClassNotFoundException {
        try {
            return Class.forName("net.neoforged.neoforgespi.language.IModInfo");
        } catch (ClassNotFoundException before204) {
            return Class.forName("net.minecraftforge.forgespi.language.IModInfo");
        }
    }

    /**
     * Compatibility another mod declares in its mods.toml:
     * [modproperties.theirmod] ultrafastpregen_compatible_mixins = ["..."], ultrafastpregen_minecraft_terrain = "reason"
     */
    private static void readCompatibility(String modId, Map<?, ?> properties) {
        if (properties == null) return;
        if (properties.get("ultrafastpregen_compatible_mixins") instanceof List<?> names) {
            for (Object name : names) {
                if (name instanceof String mixin) PreGenMod.declareCompatibleMixin(mixin, modId);
            }
        }
        if (properties.get("ultrafastpregen_minecraft_terrain") instanceof String reason) {
            PreGenMod.requireMinecraftTerrain(modId, reason);
        }
    }
}
