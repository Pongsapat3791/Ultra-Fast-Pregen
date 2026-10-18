package com.example.pregen.mixin;

import com.example.pregen.terrain.ReusableBiomeSupplier;
import com.example.pregen.terrain.TerrainGenerationContext;
import com.google.common.base.Suppliers;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * On this mod's terrain workers, one lazy biome supplier per chunk (see ReusableBiomeSupplier).
 * Priority above C2ME's and its forks' (1100, FastChunkGen): they overwrite updateY, and Mixin
 * refuses to inject into a method merged by a mixin of higher priority, failing the whole class.
 * Here both injections then sit in their updateY: the reset only moves this mod's own supplier,
 * and the redirect has nothing to replace where their code no longer calls Suppliers.memoize.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$Context", priority = 1500)
public abstract class SurfaceRulesContextMixin {
    @Shadow @Final private Function<BlockPos, Holder<Biome>> biomeGetter;
    @Shadow @Final BlockPos.MutableBlockPos pos;
    @Unique private ReusableBiomeSupplier pregen$biome;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void pregen$createSupplier(CallbackInfo ci) {
        if (ReusableBiomeSupplier.enabled && TerrainGenerationContext.isActive()) {
            pregen$biome = new ReusableBiomeSupplier(biomeGetter, pos);
        }
    }

    @Inject(method = "updateY", at = @At("HEAD"))
    private void pregen$moveSupplier(int stoneDepthAbove, int stoneDepthBelow, int waterHeight, int x, int y, int z,
                                     CallbackInfo ci) {
        if (pregen$biome != null) pregen$biome.reset(x, y, z);
    }

    // Only a shortcut: other mods (ModernFix's worldgen_allocation) replace updateY without this call.
    @Redirect(method = "updateY", require = 0, at = @At(value = "INVOKE", remap = false,
            target = "Lcom/google/common/base/Suppliers;memoize(Lcom/google/common/base/Supplier;)Lcom/google/common/base/Supplier;"))
    private com.google.common.base.Supplier<Holder<Biome>> pregen$reuseSupplier(com.google.common.base.Supplier<Holder<Biome>> lazy) {
        return pregen$biome != null ? pregen$biome : Suppliers.memoize(lazy);
    }
}
