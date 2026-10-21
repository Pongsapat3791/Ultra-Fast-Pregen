package com.example.pregen.mixin;

import com.example.pregen.WorldgenCompat;
import com.example.pregen.terrain.AquiferAirProof;
import net.minecraft.SharedConstants;
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

/**
 * The air proof for the cell-skipping fill (FastNoiseFill). Above skipSamplingAboveY, Minecraft's
 * computeSubstance answers a non-positive density with the global fluid at that y and clears the
 * fluid-update flag, without sampling aquifers; a cell there is air when that fluid is air at
 * every y of it. And the fill's computeSubstance, with the aquifer centers kept per grid cell (see
 * AquiferCenters). Nothing of Minecraft's computeSubstance itself changes.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.Aquifer$NoiseBasedAquifer")
public abstract class NoiseBasedAquiferMixin implements AquiferAirProof {
    @Shadow @Final private Aquifer.FluidPicker globalFluidPicker;
    @Shadow @Final private int skipSamplingAboveY;
    @Shadow @Final private PositionalRandomFactory positionalRandomFactory;
    @Shadow @Final protected long[] aquiferLocationCache;
    @Shadow protected boolean shouldScheduleFluidUpdate;
    @Shadow @Final private static double FLOWING_UPDATE_SIMULARITY;

    /** 0 unknown, 1 the generator's own y-only fluid picker, 2 any other picker. */
    @Unique private int pregen$pickerKind;
    /** The grid cell whose twelve surrounding aquifer centers are kept below, in Minecraft's order. */
    @Unique private int pregen$anchorX = Integer.MIN_VALUE;
    @Unique private int pregen$anchorY;
    @Unique private int pregen$anchorZ;
    @Unique private final int[] pregen$centerIndex = new int[12];
    @Unique private final int[] pregen$centerX = new int[12];
    @Unique private final int[] pregen$centerY = new int[12];
    @Unique private final int[] pregen$centerZ = new int[12];

    @Shadow protected abstract int getIndex(int gridX, int gridY, int gridZ);

    @Shadow
    private Aquifer.FluidStatus getAquiferStatus(int index) {
        throw new AssertionError();
    }

    @Shadow
    private double calculatePressure(DensityFunction.FunctionContext context, MutableDouble barrierNoiseValue,
                                     Aquifer.FluidStatus statusClosest1, Aquifer.FluidStatus statusClosest2) {
        throw new AssertionError();
    }

    @Shadow
    protected static double similarity(int distanceSqr1, int distanceSqr2) {
        throw new AssertionError();
    }

    @Shadow
    protected static int gridX(int blockCoord) {
        throw new AssertionError();
    }

    @Shadow
    protected static int gridY(int blockCoord) {
        throw new AssertionError();
    }

    @Shadow
    protected static int gridZ(int blockCoord) {
        throw new AssertionError();
    }

    @Shadow
    private static int fromGridX(int gridCoord, int blockOffset) {
        throw new AssertionError();
    }

    @Shadow
    private static int fromGridY(int gridCoord, int blockOffset) {
        throw new AssertionError();
    }

    @Shadow
    private static int fromGridZ(int gridCoord, int blockOffset) {
        throw new AssertionError();
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
        if (minY <= skipSamplingAboveY || !WorldgenCompat.aquiferOptimizationAllowed()) return false;
        int maxX = minX + width - 1;
        int maxY = minY + height - 1;
        int maxZ = minZ + width - 1;
        // Minecraft's own picker (NoiseBasedChunkGenerator.createFluidPicker) reads only y.
        boolean yOnly = pregen$pickerReadsOnlyY();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!globalFluidPicker.computeFluid(x, y, z).at(y).isAir()) return false;
                    if (yOnly) break;
                }
                if (yOnly) break;
            }
        }
        return true;
    }

    /** Every call above skipSamplingAboveY clears the flag as Minecraft's does; nothing to correct. */
    @Override
    public void pregen$endFill() {
    }

    /** The twelve aquifer centers around the grid cell, located (and cached) as Minecraft locates them. */
    @Unique
    private void pregen$selectAnchor(int xAnchor, int yAnchor, int zAnchor) {
        int center = 0;
        for (int x1 = 0; x1 <= 1; x1++) {
            for (int y1 = -1; y1 <= 1; y1++) {
                for (int z1 = 0; z1 <= 1; z1++) {
                    int spacedGridX = xAnchor + x1;
                    int spacedGridY = yAnchor + y1;
                    int spacedGridZ = zAnchor + z1;
                    int index = this.getIndex(spacedGridX, spacedGridY, spacedGridZ);
                    long existingLocation = this.aquiferLocationCache[index];
                    long location;
                    if (existingLocation != Long.MAX_VALUE) {
                        location = existingLocation;
                    } else {
                        RandomSource random = this.positionalRandomFactory.at(spacedGridX, spacedGridY, spacedGridZ);
                        location = BlockPos.asLong(fromGridX(spacedGridX, random.nextInt(10)),
                                fromGridY(spacedGridY, random.nextInt(9)), fromGridZ(spacedGridZ, random.nextInt(10)));
                        this.aquiferLocationCache[index] = location;
                    }
                    pregen$centerIndex[center] = index;
                    pregen$centerX[center] = BlockPos.getX(location);
                    pregen$centerY[center] = BlockPos.getY(location);
                    pregen$centerZ[center] = BlockPos.getZ(location);
                    center++;
                }
            }
        }
        pregen$anchorX = xAnchor;
        pregen$anchorY = yAnchor;
        pregen$anchorZ = zAnchor;
    }

    @Override
    public BlockState pregen$computeSubstance(DensityFunction.FunctionContext context, double density) {
        if (density > 0.0) {
            this.shouldScheduleFluidUpdate = false;
            return null;
        }
        int posX = context.blockX();
        int posY = context.blockY();
        int posZ = context.blockZ();
        Aquifer.FluidStatus globalFluid = this.globalFluidPicker.computeFluid(posX, posY, posZ);
        if (posY > this.skipSamplingAboveY) {
            this.shouldScheduleFluidUpdate = false;
            return globalFluid.at(posY);
        } else if (globalFluid.at(posY).is(Blocks.LAVA)) {
            this.shouldScheduleFluidUpdate = false;
            return SharedConstants.DEBUG_DISABLE_FLUID_GENERATION ? Blocks.AIR.defaultBlockState() : Blocks.LAVA.defaultBlockState();
        }
        int xAnchor = gridX(posX + -5);
        int yAnchor = gridY(posY + 1);
        int zAnchor = gridZ(posZ + -5);
        if (xAnchor != pregen$anchorX || yAnchor != pregen$anchorY || zAnchor != pregen$anchorZ) {
            pregen$selectAnchor(xAnchor, yAnchor, zAnchor);
        }
        int distanceSqr1 = Integer.MAX_VALUE;
        int distanceSqr2 = Integer.MAX_VALUE;
        int distanceSqr3 = Integer.MAX_VALUE;
        int distanceSqr4 = Integer.MAX_VALUE;
        int closestIndex1 = 0;
        int closestIndex2 = 0;
        int closestIndex3 = 0;
        int closestIndex4 = 0;
        int[] centerIndex = pregen$centerIndex;
        int[] centerX = pregen$centerX;
        int[] centerY = pregen$centerY;
        int[] centerZ = pregen$centerZ;
        for (int center = 0; center < 12; center++) {
            int dx = centerX[center] - posX;
            int dy = centerY[center] - posY;
            int dz = centerZ[center] - posZ;
            int newDistance = dx * dx + dy * dy + dz * dz;
            int index = centerIndex[center];
            if (distanceSqr1 >= newDistance) {
                closestIndex4 = closestIndex3;
                closestIndex3 = closestIndex2;
                closestIndex2 = closestIndex1;
                closestIndex1 = index;
                distanceSqr4 = distanceSqr3;
                distanceSqr3 = distanceSqr2;
                distanceSqr2 = distanceSqr1;
                distanceSqr1 = newDistance;
            } else if (distanceSqr2 >= newDistance) {
                closestIndex4 = closestIndex3;
                closestIndex3 = closestIndex2;
                closestIndex2 = index;
                distanceSqr4 = distanceSqr3;
                distanceSqr3 = distanceSqr2;
                distanceSqr2 = newDistance;
            } else if (distanceSqr3 >= newDistance) {
                closestIndex4 = closestIndex3;
                closestIndex3 = index;
                distanceSqr4 = distanceSqr3;
                distanceSqr3 = newDistance;
            } else if (distanceSqr4 >= newDistance) {
                closestIndex4 = index;
                distanceSqr4 = newDistance;
            }
        }

        // From here on Minecraft's computeSubstance, unchanged.
        Aquifer.FluidStatus closestStatus1 = this.getAquiferStatus(closestIndex1);
        double similarity12 = similarity(distanceSqr1, distanceSqr2);
        BlockState fluidState = closestStatus1.at(posY);
        BlockState actualFluidState = SharedConstants.DEBUG_DISABLE_FLUID_GENERATION ? Blocks.AIR.defaultBlockState() : fluidState;
        if (similarity12 <= 0.0) {
            if (similarity12 >= FLOWING_UPDATE_SIMULARITY) {
                Aquifer.FluidStatus closestStatus2 = this.getAquiferStatus(closestIndex2);
                this.shouldScheduleFluidUpdate = !closestStatus1.equals(closestStatus2);
            } else {
                this.shouldScheduleFluidUpdate = false;
            }
            return actualFluidState;
        } else if (fluidState.is(Blocks.WATER) && this.globalFluidPicker.computeFluid(posX, posY - 1, posZ).at(posY - 1).is(Blocks.LAVA)) {
            this.shouldScheduleFluidUpdate = true;
            return actualFluidState;
        }
        MutableDouble barrierNoiseValue = new MutableDouble(Double.NaN);
        Aquifer.FluidStatus closestStatus2 = this.getAquiferStatus(closestIndex2);
        double barrier12 = similarity12 * this.calculatePressure(context, barrierNoiseValue, closestStatus1, closestStatus2);
        if (density + barrier12 > 0.0) {
            this.shouldScheduleFluidUpdate = false;
            return null;
        }
        Aquifer.FluidStatus closestStatus3 = this.getAquiferStatus(closestIndex3);
        double similarity13 = similarity(distanceSqr1, distanceSqr3);
        if (similarity13 > 0.0) {
            double barrier13 = similarity12 * similarity13 * this.calculatePressure(context, barrierNoiseValue, closestStatus1, closestStatus3);
            if (density + barrier13 > 0.0) {
                this.shouldScheduleFluidUpdate = false;
                return null;
            }
        }
        double similarity23 = similarity(distanceSqr2, distanceSqr3);
        if (similarity23 > 0.0) {
            double barrier23 = similarity12 * similarity23 * this.calculatePressure(context, barrierNoiseValue, closestStatus2, closestStatus3);
            if (density + barrier23 > 0.0) {
                this.shouldScheduleFluidUpdate = false;
                return null;
            }
        }
        boolean mayFlow12 = !closestStatus1.equals(closestStatus2);
        boolean mayFlow23 = similarity23 >= FLOWING_UPDATE_SIMULARITY && !closestStatus2.equals(closestStatus3);
        boolean mayFlow13 = similarity13 >= FLOWING_UPDATE_SIMULARITY && !closestStatus1.equals(closestStatus3);
        if (!mayFlow12 && !mayFlow23 && !mayFlow13) {
            this.shouldScheduleFluidUpdate = similarity13 >= FLOWING_UPDATE_SIMULARITY
                    && similarity(distanceSqr1, distanceSqr4) >= FLOWING_UPDATE_SIMULARITY
                    && !closestStatus1.equals(this.getAquiferStatus(closestIndex4));
        } else {
            this.shouldScheduleFluidUpdate = true;
        }
        return actualFluidState;
    }
}
