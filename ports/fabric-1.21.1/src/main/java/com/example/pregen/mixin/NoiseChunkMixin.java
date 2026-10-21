package com.example.pregen.mixin;

import com.example.pregen.WorldgenCompat;
import com.example.pregen.terrain.ColumnCachedDensity;
import com.example.pregen.terrain.NoiseChunkWrap;
import com.example.pregen.terrain.SharedCorners;
import com.example.pregen.terrain.TerrainGenerationContext;
import java.util.List;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * On this mod's terrain workers, caches 2D climate noises per column (see ColumnCachedDensity)
 * and builds the chunk's router with less work: the other functions without the lookup map, and
 * shared parts once (see NoiseChunkWrap). Corner columns on the chunk's edges come from the
 * neighbor that computed them first where possible (see SharedCorners).
 */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin {
    @Shadow @Final int firstNoiseX;
    @Shadow @Final int firstNoiseZ;
    @Shadow @Final private int firstCellX;
    @Shadow @Final private int firstCellZ;
    @Shadow @Final int cellWidth;
    @Shadow @Final List<NoiseChunk.NoiseInterpolator> interpolators;
    @Shadow private int cellStartBlockX;
    @Shadow private int cellStartBlockZ;

    /** The edge corners this chunk shares with its neighbors, or null when it adds values of its own. */
    @Unique private SharedCorners pregen$corners;

    @Unique private NoiseChunkWrap.Memo pregen$memo;

    @Shadow
    private DensityFunction wrapNew(DensityFunction function) {
        throw new AssertionError();
    }

    @Unique
    private static boolean pregen$shortcuts() {
        return NoiseChunkWrap.enabled && TerrainGenerationContext.isActive() && WorldgenCompat.noiseChunkWrapAllowed();
    }

    /** This chunk's wrapping with shared results kept, in place of a new this::wrap. */
    @Unique
    private DensityFunction.Visitor pregen$memoized(DensityFunction.Visitor wrap) {
        if (!pregen$shortcuts()) return wrap;
        if (pregen$memo == null) pregen$memo = new NoiseChunkWrap.Memo(wrap);
        return pregen$memo;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void pregen$shareCorners(int cellCountXZ, RandomState randomState, int chunkMinBlockX, int chunkMinBlockZ,
                                     NoiseSettings noiseSettings, DensityFunctions.BeardifierOrMarker beardifier,
                                     NoiseGeneratorSettings settings, Aquifer.FluidPicker globalFluidPicker, Blender blender,
                                     CallbackInfo ci) {
        // A whole chunk's noise, with no blending and no structure pieces in its beardifier.
        if (cellCountXZ * cellWidth == 16 && blender == Blender.empty() && TerrainGenerationContext.isActive()
                && (Object) beardifier instanceof BeardifierAccessor pieces
                && !pieces.pregen$getPieceIterator().hasNext() && !pieces.pregen$getPieceIterator().hasPrevious()
                && !pieces.pregen$getJunctionIterator().hasNext() && !pieces.pregen$getJunctionIterator().hasPrevious()
                && WorldgenCompat.fastNoiseFillAllowed()) {
            pregen$corners = SharedCorners.of(randomState);
        }
    }

    // Only a shortcut: a mod that replaces fillSlice leaves every corner computed here.
    @Redirect(method = "fillSlice", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator;fillArray([DLnet/minecraft/world/level/levelgen/DensityFunction$ContextProvider;)V"))
    private void pregen$fillCornerColumn(NoiseChunk.NoiseInterpolator interpolator, double[] column,
                                         DensityFunction.ContextProvider provider) {
        SharedCorners corners = pregen$corners;
        int x = cellStartBlockX;
        int z = cellStartBlockZ;
        boolean edgeX = x == firstCellX * cellWidth || x == firstCellX * cellWidth + 16;
        boolean edgeZ = z == firstCellZ * cellWidth || z == firstCellZ * cellWidth + 16;
        if (corners == null || !edgeX && !edgeZ) {
            interpolator.fillArray(column, provider);
            return;
        }
        corners.fill(x, z, interpolators.indexOf(interpolator), interpolators.size(), edgeX && edgeZ ? 4 : 2, column,
                () -> interpolator.fillArray(column, provider));
    }

    @Inject(method = "wrap", at = @At("HEAD"), cancellable = true)
    private void pregen$wrapWithoutLookup(DensityFunction function, CallbackInfoReturnable<DensityFunction> cir) {
        if (NoiseChunkWrap.bypassesLookup(function) && pregen$shortcuts()) cir.setReturnValue(wrapNew(function));
    }

    // Only shortcuts: a mod that builds the router another way leaves Minecraft's own calls in place.
    @Redirect(method = "<init>", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseRouter;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/NoiseRouter;"))
    private NoiseRouter pregen$mapRouter(NoiseRouter router, DensityFunction.Visitor wrap) {
        return router.mapAll(pregen$memoized(wrap));
    }

    @Redirect(method = {"<init>", "cachedClimateSampler"}, require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/DensityFunction;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;"))
    private DensityFunction pregen$mapFunction(DensityFunction function, DensityFunction.Visitor wrap) {
        return function.mapAll(pregen$memoized(wrap));
    }

    @Inject(method = "wrapNew", at = @At("RETURN"), cancellable = true)
    private void pregen$cacheColumnConstant(DensityFunction function, CallbackInfoReturnable<DensityFunction> cir) {
        DensityFunction wrapped = cir.getReturnValue();
        if (!(wrapped instanceof ShiftedNoiseAccessor) || !TerrainGenerationContext.isActive()
                || !WorldgenCompat.fastNoiseFillAllowed()) {
            return;
        }
        DensityFunction cached = ColumnCachedDensity.wrapIfColumnConstant(wrapped,
                QuartPos.toBlock(firstNoiseX), QuartPos.toBlock(firstNoiseZ));
        if (cached != wrapped) cir.setReturnValue(cached);
    }
}
