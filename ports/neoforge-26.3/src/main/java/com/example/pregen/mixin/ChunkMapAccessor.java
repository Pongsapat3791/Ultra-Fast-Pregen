package com.example.pregen.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Server-thread-only views of which chunks the server is holding in memory. */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {

    @Invoker("getUpdatingChunkIfPresent")
    ChunkHolder pregen$getUpdatingChunk(long pos);

    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder pregen$getVisibleChunk(long pos);

    @Accessor("pendingUnloads")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> pregen$getPendingUnloads();
}
