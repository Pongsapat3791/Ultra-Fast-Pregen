package io.github.pongsapat3791.ufplink.entry;

import io.github.pongsapat3791.ufplink.UfpLink;
import java.nio.file.Path;
import net.minecraftforge.fml.common.Mod;

/** Forge. */
@Mod(UfpLink.MOD_ID)
public final class ForgeEntry {
    public ForgeEntry() {
        UfpLink.start(Path.of("config"));
    }
}
