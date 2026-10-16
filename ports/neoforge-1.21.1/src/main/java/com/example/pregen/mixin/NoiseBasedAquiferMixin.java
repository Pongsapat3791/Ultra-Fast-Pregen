package com.example.pregen.mixin;

import com.example.pregen.WorldgenCompat;
import com.example.pregen.terrain.AquiferAirProof;
import com.example.pregen.terrain.AquiferCenterCache;
import com.example.pregen.terrain.TerrainGenerationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.world.level.levelgen.Aquifer$NoiseBasedAquifer")
public abstract class NoiseBasedAquiferMixin implements AquiferAirProof {
    @Shadow @Final private Aquifer.FluidPicker globalFluidPicker;
    @Shadow @Final private PositionalRandomFactory positionalRandomFactory;
    @Shadow @Final private long[] aquiferLocationCache;
    @Shadow private boolean shouldScheduleFluidUpdate;
    @Shadow @Final private static double FLOWING_UPDATE_SIMULARITY;

    @Shadow private int getIndex(int x, int y, int z) { throw new AssertionError(); }
    @Shadow private Aquifer.FluidStatus getAquiferStatus(long position) { throw new AssertionError(); }
    @Shadow private double calculatePressure(DensityFunction.FunctionContext context, MutableDouble barrier,
                                             Aquifer.FluidStatus first, Aquifer.FluidStatus second) {
        throw new AssertionError();
    }

    @Unique private boolean pregen$optimized;
    @Unique private AquiferCenterCache pregen$centers;
    /** 0 unknown, 1 the generator's own y-only fluid picker, 2 any other picker. */
    @Unique private int pregen$pickerKind;
    /** During the noise fill the air shortcut may leave a flag Minecraft would not (see pregen$endFill). */
    @Unique private boolean pregen$filling = true;
    @Unique private boolean pregen$flagGuessed;
    @Unique private int pregen$guessX;
    @Unique private int pregen$guessY;
    @Unique private int pregen$guessZ;
    @Unique private double pregen$guessDensity;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void pregen$enableForTerrainWorker(CallbackInfo ci) {
        pregen$optimized = TerrainGenerationContext.isActive() && WorldgenCompat.aquiferOptimizationAllowed();
    }

    @Unique
    private long pregen$location(int x, int y, int z) {
        int index = getIndex(x, y, z);
        long position = aquiferLocationCache[index];
        if (position == Long.MAX_VALUE) {
            RandomSource random = positionalRandomFactory.at(x, y, z);
            position = BlockPos.asLong(x * 16 + random.nextInt(10), y * 12 + random.nextInt(9),
                    z * 16 + random.nextInt(10));
            aquiferLocationCache[index] = position;
        }
        return position;
    }

    @Unique
    private int pregen$fluidLevel(long centerPosition) {
        return ((FluidStatusAccessor) (Object) getAquiferStatus(centerPosition)).pregen$getFluidLevel();
    }

    @Unique
    private static double pregen$similarity(int first, int second) {
        return 1.0 - Math.abs(second - first) / 25.0;
    }

    @Unique
    private boolean pregen$pickerReadsOnlyY() {
        if (pregen$pickerKind == 0) {
            // createFluidPicker's lambda is the only FluidPicker that class defines.
            String owner = NoiseBasedChunkGenerator.class.getName() + "$$Lambda";
            pregen$pickerKind = globalFluidPicker.getClass().getName().startsWith(owner) ? 1 : 2;
        }
        return pregen$pickerKind == 1;
    }

    @Override
    public boolean pregen$airAtNonPositiveDensity(int minX, int minY, int minZ, int width, int height) {
        int maxX = minX + width - 1;
        int maxY = minY + height - 1;
        int maxZ = minZ + width - 1;
        // Minecraft's own picker (NoiseBasedChunkGenerator.createFluidPicker) reads only y.
        boolean yOnly = pregen$pickerReadsOnlyY();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (globalFluidPicker.computeFluid(x, y, z).at(y).is(Blocks.LAVA)) return false;
                    if (yOnly) break;
                }
                if (yOnly) break;
            }
        }
        if (pregen$centers == null) {
            pregen$centers = new AquiferCenterCache(this::pregen$location, this::pregen$fluidLevel);
        }
        // The aquifer grid cell depends on x and z only through floorDiv, so across the box it
        // takes its extreme values at the box's edges; every y is checked.
        for (int y = minY; y <= maxY; y++) {
            if (!pregen$centers.isAboveAllFluid(minX, y, minZ) || !pregen$centers.isAboveAllFluid(maxX, y, minZ)
                    || !pregen$centers.isAboveAllFluid(minX, y, maxZ) || !pregen$centers.isAboveAllFluid(maxX, y, maxZ)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void pregen$endFill() {
        if (!pregen$optimized || !pregen$filling) return;
        pregen$filling = false;
        if (pregen$flagGuessed) {
            // Repeat the fill's last call without the shortcut, so the flag is Minecraft's.
            ((Aquifer) (Object) this).computeSubstance(
                    new DensityFunction.SinglePointContext(pregen$guessX, pregen$guessY, pregen$guessZ), pregen$guessDensity);
        }
    }

    @Inject(method = "computeSubstance", at = @At("HEAD"), cancellable = true)
    private void pregen$computeWithCachedCenters(DensityFunction.FunctionContext context, double density,
                                                 CallbackInfoReturnable<BlockState> cir) {
        if (!pregen$optimized) return;
        int x = context.blockX();
        int y = context.blockY();
        int z = context.blockZ();
        pregen$flagGuessed = false;
        if (density > 0.0) {
            shouldScheduleFluidUpdate = false;
            cir.setReturnValue(null);
            return;
        }
        Aquifer.FluidStatus global = globalFluidPicker.computeFluid(x, y, z);
        if (global.at(y).is(Blocks.LAVA)) {
            shouldScheduleFluidUpdate = false;
            cir.setReturnValue(Blocks.LAVA.defaultBlockState());
            return;
        }

        if (pregen$centers == null) {
            pregen$centers = new AquiferCenterCache(this::pregen$location, this::pregen$fluidLevel);
        }
        AquiferCenterCache centers = pregen$centers;
        if (pregen$filling && centers.isAboveAllFluid(x, y, z)) {
            // Vanilla's result here is always air (see AIR_MARGIN_ABOVE_FLUID). doFill reads the
            // flag only for fluid results, so its value is unobservable until the fill ends.
            pregen$flagGuessed = true;
            pregen$guessX = x;
            pregen$guessY = y;
            pregen$guessZ = z;
            pregen$guessDensity = density;
            shouldScheduleFluidUpdate = true;
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
            return;
        }
        centers.findNearest(x, y, z);
        Aquifer.FluidStatus first = getAquiferStatus(centers.firstPosition);
        double similarity12 = pregen$similarity(centers.firstDistance, centers.secondDistance);
        BlockState state = first.at(y);
        if (similarity12 <= 0.0) {
            shouldScheduleFluidUpdate = similarity12 >= FLOWING_UPDATE_SIMULARITY;
            cir.setReturnValue(state);
            return;
        }
        if (state.is(Blocks.WATER) && globalFluidPicker.computeFluid(x, y - 1, z).at(y - 1).is(Blocks.LAVA)) {
            shouldScheduleFluidUpdate = true;
            cir.setReturnValue(state);
            return;
        }

        // Keep vanilla pressure arithmetic and its lazy barrier sampling unchanged.
        MutableDouble barrier = new MutableDouble(Double.NaN);
        Aquifer.FluidStatus second = getAquiferStatus(centers.secondPosition);
        double pressure12 = similarity12 * calculatePressure(context, barrier, first, second);
        if (density + pressure12 > 0.0) {
            shouldScheduleFluidUpdate = false;
            cir.setReturnValue(null);
            return;
        }
        Aquifer.FluidStatus third = getAquiferStatus(centers.thirdPosition);
        double similarity13 = pregen$similarity(centers.firstDistance, centers.thirdDistance);
        if (similarity13 > 0.0) {
            double pressure13 = similarity12 * similarity13 * calculatePressure(context, barrier, first, third);
            if (density + pressure13 > 0.0) {
                shouldScheduleFluidUpdate = false;
                cir.setReturnValue(null);
                return;
            }
        }
        double similarity23 = pregen$similarity(centers.secondDistance, centers.thirdDistance);
        if (similarity23 > 0.0) {
            double pressure23 = similarity12 * similarity23 * calculatePressure(context, barrier, second, third);
            if (density + pressure23 > 0.0) {
                shouldScheduleFluidUpdate = false;
                cir.setReturnValue(null);
                return;
            }
        }
        shouldScheduleFluidUpdate = true;
        cir.setReturnValue(state);
    }
}
