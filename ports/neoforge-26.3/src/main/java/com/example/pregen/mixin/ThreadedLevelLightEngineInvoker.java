package com.example.pregen.mixin;

import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ThreadedLevelLightEngine.class)
public interface ThreadedLevelLightEngineInvoker {

    /** Same call ChunkMap.scheduleUnload makes: drops the chunk's block/sky light data. */
    @Invoker("updateChunkStatus")
    void callUpdateChunkStatus(ChunkPos pos);
}
