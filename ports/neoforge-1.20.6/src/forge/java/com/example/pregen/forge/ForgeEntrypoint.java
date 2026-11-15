package com.example.pregen.forge;

import com.example.pregen.OfficialNames;
import com.example.pregen.PreGenMod;
import io.github.pongsapat3791.ultrafastpregen.api.PregenListener;
import io.github.pongsapat3791.ultrafastpregen.api.PregenStatus;
import io.github.pongsapat3791.ultrafastpregen.api.UltraFastPregenApi;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge entry point for Minecraft 1.20.6, where Forge runs on Mojang's names as NeoForge does (the
 * Forge jar of 1.20 to 1.20.4 runs on Forge's own names). Commands and everything else come from
 * the shared code and its mixins. The few calls into Forge are made by name, so that no Forge file
 * is needed to compile (see shared/forge).
 */
@Mod(PreGenMod.MOD_ID)
public final class ForgeEntrypoint {
    private static final String FORGE = "net.minecraftforge.";

    public ForgeEntrypoint() {
        try {
            Class<?> modList = Class.forName(FORGE + "fml.ModList");
            List<?> loaded = (List<?>) modList.getMethod("getMods").invoke(modList.getMethod("get").invoke(null));
            Class<?> modInfo = Class.forName(FORGE + "forgespi.language.IModInfo");
            Method modId = modInfo.getMethod("getModId");
            Method modVersion = modInfo.getMethod("getVersion");
            Method modProperties = modInfo.getMethod("getModProperties");
            Map<String, String> mods = new TreeMap<>();
            for (Object mod : loaded) mods.put((String) modId.invoke(mod), String.valueOf(modVersion.invoke(mod)));
            Class<?> paths = Class.forName(FORGE + "fml.loading.FMLPaths");
            Path configDirectory = (Path) paths.getMethod("get").invoke(paths.getField("CONFIGDIR").get(null));
            // The family's source reaches 1.20.5's classes by name: here they have Mojang's.
            PreGenMod.setRuntimeNames(new OfficialNames());
            PreGenMod.init(configDirectory, mods.getOrDefault(PreGenMod.MOD_ID, "unknown"));
            for (Object mod : loaded) readCompatibility((String) modId.invoke(mod), (Map<?, ?>) modProperties.invoke(mod));
            PreGenMod.platform("forge", mods);
            askFeatureListsBeforeRuns();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("Ultra Fast Pregen does not know this version of Forge (" + failure + ")", failure);
        }
    }

    /**
     * Forge keeps a generator's sorted feature list in a holder of its own (ClearableLazy, from Minecraft 1.20.6
     * on). When several threads ask it for the first time at once, only the one that computes the list gets it:
     * the others get null. Minecraft's own generation rarely asks from two threads in the same instant; this
     * mod's features run on every core, so in a dimension where nothing was generated since the server started
     * (the Nether, the End, a world of Link) the first batch failed there and was made again on Minecraft's own
     * steps. So every run asks once itself, on its own thread, before any worker does: validate() is Minecraft's
     * own call for exactly that. All dimensions, because a world of Link shares its generator with the dimension
     * it is for. Without it nothing breaks (the batch is made again), so a Forge without these names only logs.
     */
    private static void askFeatureListsBeforeRuns() {
        try {
            Method currentServer = Class.forName(FORGE + "server.ServerLifecycleHooks").getMethod("getCurrentServer");
            UltraFastPregenApi.get().addListener(new PregenListener() {
                @Override
                public void onStart(PregenStatus status) {
                    try {
                        if (!(currentServer.invoke(null) instanceof MinecraftServer server)) return;
                        List<ServerLevel> levels = new ArrayList<>();
                        server.getAllLevels().forEach(levels::add);
                        for (ServerLevel level : levels) level.getChunkSource().getGenerator().validate();
                    } catch (ReflectiveOperationException | RuntimeException failure) {
                        PreGenMod.LOGGER.warn("[PreGen] Could not prepare the feature lists before this run ({}).", failure.toString());
                    }
                }
            });
        } catch (ReflectiveOperationException | RuntimeException failure) {
            PreGenMod.LOGGER.warn("[PreGen] Could not prepare feature lists on this version of Forge ({}).", failure.toString());
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
