package com.example.pregen.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Only safe on the owning IOWorker's thread, like every other use of RegionFileStorage. */
@Mixin(RegionFileStorage.class)
public interface RegionFileStorageAccessor {

    @Accessor("folder")
    Path pregen$getFolder();

    /** Open region files keyed by ChunkPos.pack(regionX, regionZ); an empty entry says the file does not exist. */
    @Accessor("regionCache")
    Long2ObjectLinkedOpenHashMap<Optional<RegionFile>> pregen$getRegionCache();

    /** Opens (creating if needed) and caches the region file, exactly as the server's own reads and writes do. */
    @Invoker("getOrCreateRegionFile")
    RegionFile pregen$getRegionFile(ChunkPos pos) throws IOException;
}
