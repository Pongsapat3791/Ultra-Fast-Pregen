package com.example.pregen.mixin;

import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkStorage.class)
public interface ChunkStorageAccessor {

    /** The dimension's IO worker; ChunkMap extends ChunkStorage. */
    @Accessor("worker")
    IOWorker pregen$getWorker();
}
