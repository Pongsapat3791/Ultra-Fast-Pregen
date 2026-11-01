package com.example.pregen.mixin;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/**
 * SpongeVanilla applies a plugin jar's mixins on any Minecraft version, and one that does not fit
 * stops the server from starting. This jar is built for 26.2, so on another version it applies none
 * and does nothing (SpongeEntrypoint says so). Minecraft's DensityFunction walks through this local
 * class on 26.2 only: 26.1.x has no such class, and 26.3 rewrote that code.
 */
public final class SpongeMixinPlugin implements IMixinConfigPlugin {
    private static final String RECURSIVE_VISITOR = "net/minecraft/world/level/levelgen/DensityFunction$1RecursiveVisitor";
    private boolean minecraft262;

    @Override
    public void onLoad(String mixinPackage) {
        try {
            MixinService.getService().getBytecodeProvider().getClassNode(RECURSIVE_VISITOR);
            minecraft262 = true;
        } catch (ClassNotFoundException otherVersion) {
            minecraft262 = false;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read Minecraft's DensityFunction classes", failure);
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return minecraft262;
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
