package com.example.pregen.mixin;

import java.util.Map;
import java.util.concurrent.Executor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** What a separate world needs of the server (see PregenWorldsImpl). */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
    @Accessor("levels")
    Map<ResourceKey<Level>, ServerLevel> pregen$getLevels();

    @Accessor("executor")
    Executor pregen$getExecutor();

    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess pregen$getStorageSource();
}
