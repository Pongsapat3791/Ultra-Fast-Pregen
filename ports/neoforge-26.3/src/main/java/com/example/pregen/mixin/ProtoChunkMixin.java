package com.example.pregen.mixin;

import com.example.pregen.RegeneratedChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ProtoChunk.class)
public abstract class ProtoChunkMixin implements RegeneratedChunk {
    @Unique private volatile boolean pregen$regenerated;

    @Override
    public boolean pregen$isRegenerated() {
        return pregen$regenerated;
    }

    @Override
    public void pregen$markRegenerated() {
        pregen$regenerated = true;
    }
}
