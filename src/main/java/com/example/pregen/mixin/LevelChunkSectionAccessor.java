package com.example.pregen.mixin;

import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The section's block counters, which a run of blocks written at once moves as its single writes would (see BlockBits). */
@Mixin(LevelChunkSection.class)
public interface LevelChunkSectionAccessor {
    @Accessor("nonEmptyBlockCount")
    short pregen$getNonEmptyBlockCount();

    @Accessor("nonEmptyBlockCount")
    void pregen$setNonEmptyBlockCount(short count);

    @Accessor("tickingBlockCount")
    short pregen$getTickingBlockCount();

    @Accessor("tickingBlockCount")
    void pregen$setTickingBlockCount(short count);
}
