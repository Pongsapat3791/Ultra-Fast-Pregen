package com.example.pregen.mixin;

import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SimpleRegionStorage.class)
public interface ChunkStorageAccessor {

    /** The dimension's IO worker; ChunkMap extends SimpleRegionStorage. */
    @Accessor("worker")
    IOWorker pregen$getWorker();
}
