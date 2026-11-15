package com.example.pregen;

import java.util.Map;

/**
 * The names of the classes and methods of Minecraft 1.20.5 and 1.20.6 that the family's source
 * reaches by name (ChunkSteps, WorldChunkIO, WorldgenCompat), where the game runs on Mojang's names: NeoForge and
 * Forge for these versions. The source asks by Fabric's intermediary names; this is the answer for
 * each one it asks.
 */
public final class OfficialNames implements PreGenMod.RuntimeNames {
    private static final Map<String, String> CLASSES = Map.of(
            "net.minecraft.class_9312", "net.minecraft.world.level.chunk.status.WorldGenContext",
            "net.minecraft.class_9311", "net.minecraft.world.level.chunk.status.ToFullChunk",
            "net.minecraft.class_9310", "net.minecraft.world.level.chunk.status.ChunkStatusTasks",
            "net.minecraft.class_9240", "net.minecraft.world.level.chunk.storage.RegionStorageInfo");
    private static final Map<String, String> METHODS = Map.of(
            // ChunkStatus.generate(WorldGenContext, Executor, ToFullChunk, List)
            "net.minecraft.class_2806.method_12154", "generate",
            // ProtoChunk.getBlockEntityNbtForSaving(BlockPos, HolderLookup.Provider)
            "net.minecraft.class_2839.method_20598", "getBlockEntityNbtForSaving");

    @Override
    public String className(String intermediary) {
        String name = CLASSES.get(intermediary);
        if (name == null) throw new IllegalStateException("Ultra Fast Pregen has no name for " + intermediary);
        return name;
    }

    @Override
    public String methodName(String owner, String name, String descriptor) {
        String official = METHODS.get(owner + "." + name);
        if (official == null) throw new IllegalStateException("Ultra Fast Pregen has no name for " + owner + "." + name);
        return official;
    }
}
