package com.example.pregen.mixin;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/**
 * One jar for Minecraft 26.1 to 26.2 (the NeoForge jar from 26.1.2, see its gradle.properties):
 * where 26.2 changed what a mixin hooks, the mixin of the running version applies. 26.2 walks
 * DensityFunction#mapAll through one local class (which tells the versions apart), keeps the
 * surface rules' biome in a field instead of a supplier per block, tests biomes against a HolderSet
 * and passes buildSurface the possible biomes. The rest of the code reads the version from
 * MinecraftVersion.
 */
public final class PregenMixinPlugin implements IMixinConfigPlugin {
    /** The class that tells the versions apart, read without loading it (see MinecraftVersion). */
    static final String RECURSIVE_VISITOR = "net/minecraft/world/level/levelgen/DensityFunction$1RecursiveVisitor";
    private static final Set<String> BEFORE_26_2 = Set.of("HolderHolderMixin", "SplineFunctionMixin",
            "SplineCoordinateMixin", "BiomeConditionSource261Mixin", "SurfaceRulesContext261Mixin", "SurfaceSystem261Mixin");
    private static final Set<String> FROM_26_2 = Set.of("RecursiveVisitorMixin", "BiomeConditionSource262Mixin",
            "SurfaceRulesContext262Mixin", "SurfaceSystem262Mixin");
    private boolean minecraft262;

    @Override
    public void onLoad(String mixinPackage) {
        try {
            MixinService.getService().getBytecodeProvider().getClassNode(RECURSIVE_VISITOR);
            minecraft262 = true;
        } catch (ClassNotFoundException older) {
            minecraft262 = false;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read Minecraft's DensityFunction classes", failure);
        }
        LoggerFactory.getLogger("ultrafastpregen").info("Ultra Fast Pregen: hooks for Minecraft {}",
                minecraft262 ? "26.2" : "26.1 to 26.1.2");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        if (BEFORE_26_2.contains(name)) return !minecraft262;
        if (FROM_26_2.contains(name)) return minecraft262;
        return true;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
