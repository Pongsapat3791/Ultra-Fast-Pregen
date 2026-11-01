package io.github.pongsapat3791.ufplink.entry;

import io.github.pongsapat3791.ufplink.UfpLink;
import java.nio.file.Path;
import net.neoforged.fml.common.Mod;

/** NeoForge. */
@Mod(UfpLink.MOD_ID)
public final class NeoForgeEntry {
    public NeoForgeEntry() {
        UfpLink.start(Path.of("config"));
    }
}
