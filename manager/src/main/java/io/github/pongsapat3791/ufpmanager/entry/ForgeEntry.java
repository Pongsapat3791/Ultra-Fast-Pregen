package io.github.pongsapat3791.ufpmanager.entry;

import io.github.pongsapat3791.ufpmanager.Manager;
import net.minecraftforge.fml.common.Mod;

/** Forge. The web page needs no event of the loader: it runs for as long as the game does. */
@Mod(Manager.MOD_ID)
public final class ForgeEntry {
    public ForgeEntry() {
        Manager.start(Manager.Layout.mod());
    }
}
