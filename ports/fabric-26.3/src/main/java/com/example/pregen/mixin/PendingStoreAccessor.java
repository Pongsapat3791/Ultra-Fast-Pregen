package com.example.pregen.mixin;

import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Update an older queued save on its owning IO thread before forcing regenerated data. */
@Mixin(targets = "net.minecraft.world.level.chunk.storage.IOWorker$PendingStore")
public interface PendingStoreAccessor {
    @Accessor("data")
    void pregen$setData(CompoundTag data);

    @Accessor("data")
    CompoundTag pregen$getData();
}
