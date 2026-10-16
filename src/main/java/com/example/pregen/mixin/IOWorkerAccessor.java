package com.example.pregen.mixin;

import com.mojang.datafixers.util.Either;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The storage and pending-write map are only safe to touch from a task run by submitTask. */
@Mixin(IOWorker.class)
public interface IOWorkerAccessor {

    @Accessor("storage")
    RegionFileStorage pregen$getStorage();

    @Accessor("pendingWrites")
    Map<ChunkPos, ?> pregen$getPendingWrites();

    @Invoker("submitTask")
    <T> CompletableFuture<T> pregen$submitTask(Supplier<Either<T, Exception>> task);
}
