package com.example.pregen.mixin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A template palette lazily caches its blocks per block type in a plain HashMap. Minecraft
 * creates structure starts and places structures one chunk at a time, but this mod runs them
 * on all worker threads, where two villages using the same house template filled the cache
 * at once (ConcurrentModificationException). The cached lists are the same either way.
 */
@Mixin(StructureTemplate.Palette.class)
public abstract class StructureTemplatePaletteMixin {
    @Shadow @Final @Mutable private Map<Block, List<StructureTemplate.StructureBlockInfo>> cache;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void pregen$threadSafeCache(List<StructureTemplate.StructureBlockInfo> blocks, CallbackInfo ci) {
        cache = new ConcurrentHashMap<>();
    }
}
