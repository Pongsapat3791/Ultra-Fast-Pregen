package com.example.pregen.terrain;

/**
 * Implemented on Minecraft's noise-based aquifer by this mod's mixin. On Minecraft 1.21.2 to 1.21.8 that mixin is
 * the 1.21.1 family's (the aquifer is still 1.21.1's there), which answers the three questions below and has no
 * rule of its own for a block.
 */
public interface AquiferAirProof {
    /**
     * True when vanilla's computeSubstance yields air for every block of the box at any
     * non-positive density: no lava from the global fluid picker, and every block lies far
     * enough above all candidate aquifer fluid levels (see AquiferCenterCache).
     */
    boolean pregen$airAtNonPositiveDensity(int minX, int minY, int minZ, int width, int height);

    /** Whether the answer above depends on the box's heights alone, so it holds for every such box of a chunk at those heights. */
    boolean pregen$fluidByYOnly();

    /**
     * Called when the noise fill is done. Carvers then reuse this aquifer, and below their lava
     * level they read the fluid-update flag left by the previous computeSubstance call (which
     * decides whether that lava is scheduled to flow). From here on every call sets the flag as
     * Minecraft's does, and the flag the fill left is corrected to Minecraft's.
     */
    void pregen$endFill();

    /**
     * The 26.x family's per-block rule with the aquifer centers kept per grid cell. Not on these versions:
     * AquiferCenters is off in this layer, so the fill never asks. A default, so that the aquifer has the method
     * (an abstract mixin class may leave an interface's method out, and the first call would then fail at run time).
     */
    default net.minecraft.world.level.block.state.BlockState pregen$computeSubstance(
            net.minecraft.world.level.levelgen.DensityFunction.FunctionContext context, double density) {
        throw new UnsupportedOperationException("the aquifer of this Minecraft version keeps no centers per grid cell");
    }
}
