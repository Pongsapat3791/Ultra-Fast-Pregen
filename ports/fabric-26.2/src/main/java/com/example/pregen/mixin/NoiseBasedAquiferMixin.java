package com.example.pregen.mixin;

import com.example.pregen.WorldgenCompat;
import com.example.pregen.terrain.AquiferAirProof;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * The air proof for the cell-skipping fill (FastNoiseFill). Above skipSamplingAboveY, Minecraft's
 * computeSubstance answers a non-positive density with the global fluid at that y and clears the
 * fluid-update flag, without sampling aquifers; a cell there is air when that fluid is air at
 * every y of it. Nothing of computeSubstance itself changes.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.Aquifer$NoiseBasedAquifer")
public abstract class NoiseBasedAquiferMixin implements AquiferAirProof {
    @Shadow @Final private Aquifer.FluidPicker globalFluidPicker;
    @Shadow @Final private int skipSamplingAboveY;

    /** 0 unknown, 1 the generator's own y-only fluid picker, 2 any other picker. */
    @Unique private int pregen$pickerKind;

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
}
