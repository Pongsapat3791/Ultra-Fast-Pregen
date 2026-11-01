package io.github.pongsapat3791.ufplink.entry;

import io.github.pongsapat3791.ufplink.UfpLink;
import java.nio.file.Path;
import org.spongepowered.plugin.builtin.jvm.Plugin;

/** Sponge. */
@Plugin(UfpLink.MOD_ID)
public final class SpongeEntry {
    public SpongeEntry() {
        UfpLink.start(Path.of("config"));
    }
}
