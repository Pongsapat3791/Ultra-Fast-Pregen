package com.example.pregen.mixin;

import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Up to Minecraft 1.21.10 ChunkMap extends ChunkStorage, which holds the dimension's IO worker itself (from 1.21.11
 * on it extends SimpleRegionStorage, and the family's accessor of this name reads the worker there).
 */
@Mixin(ChunkStorage.class)
public interface ChunkStorageAccessor {

    /** The dimension's IO worker; ChunkMap extends ChunkStorage. */
    @Accessor("worker")
    IOWorker pregen$getWorker();
}
