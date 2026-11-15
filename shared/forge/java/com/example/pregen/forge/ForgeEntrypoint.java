package com.example.pregen.forge;

import com.example.pregen.PreGenCommand;
import com.example.pregen.PreGenMod;
import com.mojang.brigadier.CommandDispatcher;
import io.github.pongsapat3791.ultrafastpregen.api.PregenListener;
import io.github.pongsapat3791.ultrafastpregen.api.PregenStatus;
import io.github.pongsapat3791.ultrafastpregen.api.UltraFastPregenApi;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge entry point for Minecraft 1.20.6 and later, where Forge runs on Mojang's names as NeoForge
 * does: registers /pregen; everything else comes from the engine and its mixins.
 *
 * <p>One class for every Forge version from there on. Forge changed its own interface twice in
 * that time (the event bus in 1.21.6, the mod list in 26.1), so the few calls into Forge are made
 * by name: which spelling exists is looked up when the mod loads, and a spelling that is nowhere
 * stops the start with a message that names it.
 */
@Mod(PreGenMod.MOD_ID)
public final class ForgeEntrypoint {
    private static final String FORGE = "net.minecraftforge.";

    public ForgeEntrypoint() {
        try {
            Map<String, String> mods = new TreeMap<>();
            Class<?> modInfo = Class.forName(FORGE + "forgespi.language.IModInfo");
            Method modId = modInfo.getMethod("getModId");
            Method modVersion = modInfo.getMethod("getVersion");
            Method modProperties = modInfo.getMethod("getModProperties");
            for (Object mod : mods()) {
                String id = (String) modId.invoke(mod);
                mods.put(id, String.valueOf(modVersion.invoke(mod)));
            }
            PreGenMod.init(configDirectory(), mods.getOrDefault(PreGenMod.MOD_ID, "unknown"));
            for (Object mod : mods()) {
                readCompatibility((String) modId.invoke(mod), (Map<?, ?>) modProperties.invoke(mod));
            }
            PreGenMod.platform("forge", mods);
            askFeatureListsBeforeRuns();
            onRegisterCommands();
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

    private static Path configDirectory() throws ReflectiveOperationException {
        Class<?> paths = Class.forName(FORGE + "fml.loading.FMLPaths");
        Object configDirectory = paths.getField("CONFIGDIR").get(null);
        return (Path) paths.getMethod("get").invoke(configDirectory);
    }

    /** ModList.get().getMods(), or ModList.getMods() from Minecraft 26.1 on. */
    private static List<?> mods() throws ReflectiveOperationException {
        Class<?> modList = Class.forName(FORGE + "fml.ModList");
        Method getMods = modList.getMethod("getMods");
        Object list = Modifier.isStatic(getMods.getModifiers()) ? null : modList.getMethod("get").invoke(null);
        return (List<?>) getMods.invoke(list);
    }

    /** RegisterCommandsEvent.BUS from Minecraft 1.21.6 on, MinecraftForge.EVENT_BUS before. */
    private static void onRegisterCommands() throws ReflectiveOperationException {
        Class<?> event = Class.forName(FORGE + "event.RegisterCommandsEvent");
        Method dispatcher = event.getMethod("getDispatcher");
        Method buildContext = event.getMethod("getBuildContext");
        Method selection = event.getMethod("getCommandSelection");
        Consumer<Object> listener = fired -> {
            try {
                @SuppressWarnings("unchecked")
                CommandDispatcher<CommandSourceStack> commands = (CommandDispatcher<CommandSourceStack>) dispatcher.invoke(fired);
                PreGenCommand.register(commands, (CommandBuildContext) buildContext.invoke(fired),
                        (Commands.CommandSelection) selection.invoke(fired));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Ultra Fast Pregen could not register /pregen", failure);
            }
        };
        Field ownBus = null;
        try {
            ownBus = event.getField("BUS");
        } catch (NoSuchFieldException olderForge) {
            // Before the event bus of 1.21.6: one bus for every event.
        }
        if (ownBus != null) {
            Class.forName(FORGE + "eventbus.api.bus.EventBus").getMethod("addListener", Consumer.class)
                    .invoke(ownBus.get(null), listener);
            return;
        }
        Object bus = Class.forName(FORGE + "common.MinecraftForge").getField("EVENT_BUS").get(null);
        Class<?> priority = Class.forName(FORGE + "eventbus.api.EventPriority");
        Object normal = priority.getField("NORMAL").get(null);
        // The overload that names the event's class: the bus cannot read it from a listener of Object.
        Class.forName(FORGE + "eventbus.api.IEventBus")
                .getMethod("addListener", priority, boolean.class, Class.class, Consumer.class)
                .invoke(bus, normal, false, event, listener);
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
