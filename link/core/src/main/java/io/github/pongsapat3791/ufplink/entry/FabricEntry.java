package io.github.pongsapat3791.ufplink.entry;

import io.github.pongsapat3791.ufplink.UfpLink;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;

/** Fabric, every Minecraft version: this class names nothing of Minecraft, so no mapping can break it. */
public final class FabricEntry implements ModInitializer {
    @Override
    public void onInitialize() {
        UfpLink.start(Path.of("config"));
    }
}
