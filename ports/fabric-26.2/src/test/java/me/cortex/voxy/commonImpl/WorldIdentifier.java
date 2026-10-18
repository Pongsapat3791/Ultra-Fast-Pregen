package me.cortex.voxy.commonImpl;

import net.minecraft.world.level.Level;

/** Test stand-in for Voxy's world identifier; see com.example.pregen.LodStubs. */
public class WorldIdentifier {
    public final Level level;

    private WorldIdentifier(Level level) {
        this.level = level;
    }

    public static WorldIdentifier of(Level level) {
        return new WorldIdentifier(level);
    }
}
