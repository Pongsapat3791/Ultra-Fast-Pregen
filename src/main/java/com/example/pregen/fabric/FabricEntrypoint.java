package com.example.pregen.fabric;

import com.example.pregen.PreGenMod;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;

/** Fabric entry point; the Forge build leaves this package out. */
public class FabricEntrypoint implements ModInitializer {
    @Override
    public void onInitialize() {
        FabricLoader loader = FabricLoader.getInstance();
        MappingResolver names = loader.getMappingResolver();
        PreGenMod.setRuntimeNames(new PreGenMod.RuntimeNames() {
            @Override
            public String className(String intermediary) {
                return names.mapClassName("intermediary", intermediary);
            }

            @Override
            public String methodName(String owner, String name, String descriptor) {
                return names.mapMethodName("intermediary", owner, name, descriptor);
            }
        });
        String version = loader.getModContainer(PreGenMod.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        PreGenMod.init(loader.getConfigDir(), version);
        for (ModContainer mod : loader.getAllMods()) {
            readCompatibility(mod);
        }
    }

    /**
     * Compatibility another mod declares in its fabric.mod.json:
     * "custom": {"ultrafastpregen": {"compatible_mixins": ["..."], "minecraft_terrain": "reason"}}
     */
    private static void readCompatibility(ModContainer mod) {
        CustomValue declared = mod.getMetadata().getCustomValue(PreGenMod.MOD_ID);
        if (declared == null || declared.getType() != CustomValue.CvType.OBJECT) return;
        String modId = mod.getMetadata().getId();
        CustomValue mixins = declared.getAsObject().get("compatible_mixins");
        if (mixins != null && mixins.getType() == CustomValue.CvType.ARRAY) {
            for (CustomValue mixin : mixins.getAsArray()) {
                if (mixin.getType() == CustomValue.CvType.STRING) PreGenMod.declareCompatibleMixin(mixin.getAsString(), modId);
            }
        }
        CustomValue terrain = declared.getAsObject().get("minecraft_terrain");
        if (terrain != null && terrain.getType() == CustomValue.CvType.STRING) {
            PreGenMod.requireMinecraftTerrain(modId, terrain.getAsString());
        }
    }
}
