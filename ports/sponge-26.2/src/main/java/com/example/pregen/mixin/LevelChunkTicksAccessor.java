package com.example.pregen.mixin;

import java.util.function.BiConsumer;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Set only while a chunk's ticks are registered in its level: a chunk loaded below full status has none yet. */
@Mixin(LevelChunkTicks.class)
public interface LevelChunkTicksAccessor<T> {
    @Accessor("onTickAdded")
    BiConsumer<LevelChunkTicks<T>, ScheduledTick<T>> pregen$getOnTickAdded();
}
