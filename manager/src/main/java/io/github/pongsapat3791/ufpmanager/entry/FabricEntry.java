package io.github.pongsapat3791.ufpmanager.entry;

import io.github.pongsapat3791.ufpmanager.Manager;
import net.fabricmc.api.ModInitializer;

/** Fabric, every Minecraft version: this class names nothing of Minecraft, so no mapping can break it. */
public final class FabricEntry implements ModInitializer {
    @Override
    public void onInitialize() {
        Manager.start(Manager.Layout.mod());
    }
}
