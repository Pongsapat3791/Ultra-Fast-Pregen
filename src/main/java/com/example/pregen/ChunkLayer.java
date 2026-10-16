package com.example.pregen;

/**
 * Represents the distinct world generation pipeline layers processed on RAM.
 */
public enum ChunkLayer {
    STRUCTURES("Stage 1/6: Structure Starts & References"),
    TERRAIN_PIPELINE("Stage 2/6: Biomes, Noise & Surface (Pipelined)"),
    CARVERS("Stage 3/6: Caves & Aquifers"),
    FEATURES("Stage 4/6: Features Decoration (Phased)"),
    LIGHTING("Stage 5/6: Lighting (Phased)");

    private final String displayName;

    ChunkLayer(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
