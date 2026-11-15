package com.example.pregen.mixin;

import com.example.pregen.WorldgenCompat;
import com.example.pregen.terrain.ColumnCachedDensity;
import com.example.pregen.terrain.CornerLevels;
import com.example.pregen.terrain.NoiseChunkCells;
import com.example.pregen.terrain.NoiseChunkWrap;
import com.example.pregen.terrain.OnceInputs;
import com.example.pregen.terrain.SharedCorners;
import com.example.pregen.terrain.SharedSurfaceLevels;
import com.example.pregen.terrain.TerrainGenerationContext;
import java.util.Arrays;
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
 * neighbor that computed them first where possible (see SharedCorners). Before 26.2 the router it
 * wraps has the terrain's repeated range-choice input marked cache_once (see OnceInputs).
 */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin implements NoiseChunkCells {
    @Shadow @Final int firstNoiseX;
    @Shadow @Final int firstNoiseZ;
    @Shadow @Final private int firstCellX;
    @Shadow @Final private int firstCellZ;
    @Shadow @Final int cellWidth;
    @Shadow @Final private int cellCountXZ;
    @Shadow @Final int cellHeight;
    @Shadow @Final private int cellNoiseMinY;
    @Shadow @Final List<NoiseChunk.NoiseInterpolator> interpolators;
    @Shadow private int cellStartBlockX;
    @Shadow private int cellStartBlockY;
    @Shadow private int cellStartBlockZ;
    @Shadow private long arrayInterpolationCounter;
    @Shadow private long interpolationCounter;
    @Shadow private int inCellY;
    @Shadow private int arrayIndex;

    /** The edge corners this chunk shares with its neighbors, or null when it adds values of its own. */
    @Unique private SharedCorners pregen$corners;

    @Unique private NoiseChunkWrap.Memo pregen$memo;

    /** No blending and no structure pieces (see NoiseChunkCells#pregen$plain). */
    @Unique private boolean pregen$plain;

    /** How many corners of each corner column are computed, or null for all (see SkyColumns). */
    @Unique private int[] pregen$exactLevels;
    @Unique private CornerLevels pregen$cornerLevels;

    /** The preliminary surface levels shared in this chunk's world, or null (see SharedSurfaceLevels). */
    @Unique private SharedSurfaceLevels pregen$levels;

    @Shadow
    private DensityFunction wrapNew(DensityFunction function) {
        throw new AssertionError();
    }

    /** NoiseChunk#selectCellYZ, cell caches left as they are (two counter steps, as theirs take). */
    @Override
    public void pregen$selectCorners(int cellY, int cellZ) {
        for (NoiseChunk.NoiseInterpolator interpolator : interpolators) {
            ((NoiseInterpolatorAccessor) (Object) interpolator).pregen$selectCellYZ(cellY, cellZ);
        }
        cellStartBlockY = (cellY + cellNoiseMinY) * cellHeight;
        cellStartBlockZ = (firstCellZ + cellZ) * cellWidth;
        arrayInterpolationCounter += 2;
    }

    @Override
    public boolean pregen$plain() {
        return pregen$plain;
    }

    @Override
    public void pregen$setExactLevels(int[] levels) {
        pregen$exactLevels = levels;
    }

    /** What the slice-filling context provider does for a corner. */
    @Override
    public DensityFunction.FunctionContext pregen$atCornerLevel(int level) {
        cellStartBlockY = (level + cellNoiseMinY) * cellHeight;
        interpolationCounter++;
        inCellY = 0;
        arrayIndex = level;
        return (NoiseChunk) (Object) this;
    }

    /** The column's lowest {@code count} corners as Minecraft computes them; the others are left unset. */
    @Unique
    private void pregen$fillLevels(NoiseChunk.NoiseInterpolator interpolator, double[] column,
                                   DensityFunction.ContextProvider provider, int count) {
        if (count >= column.length) {
            interpolator.fillArray(column, provider);
            return;
        }
        if (count > 0) {
            if (pregen$cornerLevels == null) pregen$cornerLevels = new CornerLevels(this);
            double[] lowest = new double[count];
            ((NoiseInterpolatorAccessor) (Object) interpolator).pregen$getNoiseFiller().fillArray(lowest, pregen$cornerLevels.first(count));
            System.arraycopy(lowest, 0, column, 0, count);
        }
        Arrays.fill(column, count, column.length, Double.NaN);
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
        pregen$plain = blender == Blender.empty() && (!((Object) beardifier instanceof BeardifierAccessor structures)
                || !structures.pregen$getPieceIterator().hasNext() && !structures.pregen$getPieceIterator().hasPrevious()
                && !structures.pregen$getJunctionIterator().hasNext() && !structures.pregen$getJunctionIterator().hasPrevious());
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
        int[] exact = pregen$exactLevels;
        int count = exact == null ? column.length
                : Math.min(column.length, exact[(x / cellWidth - firstCellX) * (cellCountXZ + 1) + z / cellWidth - firstCellZ]);
        boolean edgeX = x == firstCellX * cellWidth || x == firstCellX * cellWidth + 16;
        boolean edgeZ = z == firstCellZ * cellWidth || z == firstCellZ * cellWidth + 16;
        if (corners == null || !edgeX && !edgeZ) {
            pregen$fillLevels(interpolator, column, provider, count);
            return;
        }
        corners.fill(x, z, interpolators.indexOf(interpolator), interpolators.size(), edgeX && edgeZ ? 4 : 2, column, count,
                () -> pregen$fillLevels(interpolator, column, provider, count));
    }

    @Inject(method = "wrap", at = @At("HEAD"), cancellable = true)
    private void pregen$wrapWithoutLookup(DensityFunction function, CallbackInfoReturnable<DensityFunction> cir) {
        if (NoiseChunkWrap.bypassesLookup(function) && pregen$shortcuts()) cir.setReturnValue(wrapNew(function));
    }

    // Only shortcuts: a mod that builds the router another way leaves Minecraft's own calls in place.
    @Redirect(method = "<init>", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseRouter;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/NoiseRouter;"))
    private NoiseRouter pregen$mapRouter(NoiseRouter router, DensityFunction.Visitor wrap) {
        // Before the constructor makes the aquifer, which asks for the levels of its whole area. Not for a chunk
        // that blends older chunks in: its levels are its own (the blender is set before the router is mapped).
        if (SharedSurfaceLevels.enabled && ((NoiseChunkAccessor) (Object) this).pregen$getBlender() == Blender.empty()) {
            pregen$levels = SharedSurfaceLevels.of(router);
        }
        NoiseRouter source = OnceInputs.enabled && pregen$shortcuts() ? OnceInputs.of(router) : router;
        return source.mapAll(pregen$memoized(wrap));
    }

    // Only shortcuts: without them every NoiseChunk computes its own levels.
    @Inject(method = "computePreliminarySurfaceLevel", require = 0, at = @At("HEAD"), cancellable = true)
    private void pregen$knownSurfaceLevel(long column, CallbackInfoReturnable<Integer> cir) {
        SharedSurfaceLevels levels = pregen$levels;
        if (levels == null) return;
        Integer known = levels.known(column);
        if (known != null) cir.setReturnValue(known);
    }

    @Inject(method = "computePreliminarySurfaceLevel", require = 0, at = @At("RETURN"))
    private void pregen$rememberSurfaceLevel(long column, CallbackInfoReturnable<Integer> cir) {
        SharedSurfaceLevels levels = pregen$levels;
        if (levels != null) levels.remember(column, cir.getReturnValueI());
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
