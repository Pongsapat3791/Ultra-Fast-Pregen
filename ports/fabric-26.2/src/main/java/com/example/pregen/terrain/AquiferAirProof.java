package com.example.pregen.terrain;

/** Implemented on Minecraft's noise-based aquifer by this mod's mixin. */
public interface AquiferAirProof {
    /**
     * True when vanilla's computeSubstance yields air for every block of the box at any
     * non-positive density: no lava from the global fluid picker, and every block lies far
     * enough above all candidate aquifer fluid levels (see AquiferCenterCache).
     */
    boolean pregen$airAtNonPositiveDensity(int minX, int minY, int minZ, int width, int height);

    /**
     * Called when the noise fill is done. Carvers then reuse this aquifer, and below their lava
     * level they read the fluid-update flag left by the previous computeSubstance call (which
     * decides whether that lava is scheduled to flow). From here on every call sets the flag as
     * Minecraft's does, and the flag the fill left is corrected to Minecraft's.
     */
    void pregen$endFill();
}
