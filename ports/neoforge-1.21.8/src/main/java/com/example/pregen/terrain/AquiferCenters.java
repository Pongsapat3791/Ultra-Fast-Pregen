package com.example.pregen.terrain;

/**
 * The 26.x family's fill keeps the aquifer's centers per grid cell (its NoiseBasedAquiferMixin). The aquifer of
 * Minecraft 1.21.2 to 1.21.8 is still the one of 1.21.1, and its mixin is that family's, which has no such rule:
 * the fill asks Minecraft's own per-block rule here, as it does on 1.21.1.
 */
public final class AquiferCenters {
    /** Off on these versions, whatever -Dfastpregen.aquiferCenters says. */
    public static volatile boolean enabled = false;

    private AquiferCenters() {}
}
