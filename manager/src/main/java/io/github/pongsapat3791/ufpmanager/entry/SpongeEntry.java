package io.github.pongsapat3791.ufpmanager.entry;

import io.github.pongsapat3791.ufpmanager.Manager;
import org.spongepowered.plugin.builtin.jvm.Plugin;

/** Sponge. See ForgeEntry. */
@Plugin(Manager.MOD_ID)
public final class SpongeEntry {
    public SpongeEntry() {
        Manager.start(Manager.Layout.mod());
    }
}
