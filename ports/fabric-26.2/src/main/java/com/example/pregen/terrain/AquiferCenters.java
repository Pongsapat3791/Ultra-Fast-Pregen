package com.example.pregen.terrain;

/**
 * Minecraft's aquifer, per block of the noise fill: for every block it looks up the twelve
 * aquifer centers of the grid cells around it (cached locations, found through an index each
 * time) to pick the nearest four. All blocks of one aquifer grid cell (16 x 12 x 16) have the same
 * twelve, so the fill's aquifer (NoiseBasedAquiferMixin) keeps them per grid cell and only
 * measures the distances per block, in Minecraft's order and with its comparisons, then answers
 * exactly as computeSubstance does.
 */
public final class AquiferCenters {
    /** Benchmark switch, on unless -Dfastpregen.aquiferCenters=false. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("fastpregen.aquiferCenters", "true"));

    private AquiferCenters() {}
}
