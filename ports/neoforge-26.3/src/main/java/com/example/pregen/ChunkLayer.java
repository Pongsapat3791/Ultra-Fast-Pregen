package com.example.pregen;

/**
 * Represents the distinct world generation pipeline layers processed on RAM.
 */
public enum ChunkLayer {
    STRUCTURES("Stage 1/5: Structure Starts & References"),
    TERRAIN_PIPELINE("Stage 2/5: Biomes, Noise, Surface & Caves (Pipelined)"),
    FEATURES("Stage 3/5: Features Decoration (Phased)"),
    LIGHTING("Stage 4/5: Lighting (Phased)");

    private final String displayName;

    ChunkLayer(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
