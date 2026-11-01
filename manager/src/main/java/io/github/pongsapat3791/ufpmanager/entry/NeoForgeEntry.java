package io.github.pongsapat3791.ufpmanager.entry;

import io.github.pongsapat3791.ufpmanager.Manager;
import net.neoforged.fml.common.Mod;

/** NeoForge. See ForgeEntry. */
@Mod(Manager.MOD_ID)
public final class NeoForgeEntry {
    public NeoForgeEntry() {
        Manager.start(Manager.Layout.mod());
    }
}
