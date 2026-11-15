package com.example.pregen;

import com.mojang.serialization.Codec;
import java.util.List;
import java.util.concurrent.Executor;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.BossEvent;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.TagValueInput;

/**
 * The calls Minecraft spells differently between the versions this family's sources are built for: this is
 * Minecraft 1.21.11's copy (the family's own, for 26.x, is in ports/neoforge-26.1.2). A chunk position is not a
 * record yet, a boss bar makes its own id, and a region's difficulty reads the level's day time.
 */
public final class Mc {
    private Mc() {}

    public static int x(ChunkPos pos) {
        return pos.x;
    }

    public static int z(ChunkPos pos) {
        return pos.z;
    }

    public static long pack(ChunkPos pos) {
        return pos.toLong();
    }

    public static long pack(int chunkX, int chunkZ) {
        return ChunkPos.asLong(chunkX, chunkZ);
    }

    public static ChunkPos unpack(long packed) {
        return new ChunkPos(packed);
    }

    public static ServerBossEvent bossBar(Component name, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
        return new ServerBossEvent(name, color, overlay);
    }

    /** The difficulty a generation region answers with for a position outside its chunks, as Minecraft's own region does. */
    public static DifficultyInstance regionDifficulty(ServerLevel level, BlockPos pos) {
        return new DifficultyInstance(level.getDifficulty(), level.getDayTime(), 0L, level.getMoonBrightness(pos));
    }

    /** A further world of the server, as the server opens its own. */
    public static ServerLevel openLevel(MinecraftServer server, Executor executor, LevelStorageSource.LevelStorageAccess storage,
                                        ServerLevelData data, ResourceKey<Level> key, LevelStem stem, boolean debug, long biomeSeed) {
        return new ServerLevel(server, executor, storage, data, key, stem, debug, biomeSeed, List.of(), false, server.overworld().getRandomSequences());
    }

    /** A key's id as text: minecraft:overworld. */
    public static String id(ResourceKey<?> key) {
        return key.identifier().toString();
    }

    /** The key of the dimension with this id. */
    public static ResourceKey<Level> dimension(String id) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse(id));
    }

    /** May run game master commands (permission level 2). */
    public static boolean gamemaster(CommandSourceStack source) {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source);
    }

    /** May run the server owner's commands (permission level 4). */
    public static boolean owner(CommandSourceStack source) {
        return Commands.hasPermission(Commands.LEVEL_OWNERS).test(source);
    }

    public static boolean gamemaster(ServerPlayer player) {
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /** A stored chunk's data as the server reads it (SerializableChunkData#parse); null when it has no readable status. */
    public static net.minecraft.world.level.chunk.storage.SerializableChunkData parseStored(ServerLevel level, net.minecraft.nbt.CompoundTag tag) {
        return net.minecraft.world.level.chunk.storage.SerializableChunkData.parse(level, level.palettedContainerFactory(), tag);
    }

    /** An empty chunk in generation, as the server makes one. */
    public static ProtoChunk newProtoChunk(ServerLevel level, ChunkPos pos, LevelHeightAccessor heights) {
        return new ProtoChunk(pos, UpgradeData.EMPTY, heights, level.palettedContainerFactory(), null);
    }

    private static final Strategy<BlockState> SECTION_STATES = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);

    /** The codec a stored chunk's sections keep their block states with. */
    public static Codec<PalettedContainer<BlockState>> blockStatesCodec() {
        return PalettedContainer.codecRW(BlockState.CODEC, SECTION_STATES, Blocks.AIR.defaultBlockState());
    }

    /** A section's block states, all air, that can be written to. */
    public static PalettedContainer<BlockState> emptyBlockStates() {
        return new PalettedContainer<>(Blocks.AIR.defaultBlockState(), SECTION_STATES);
    }

    public static boolean singleplayerOwner(MinecraftServer server, ServerPlayer player) {
        return server.isSingleplayerOwner(player.nameAndId());
    }

    /** The world spawn of this level. */
    public static BlockPos spawn(ServerLevel level) {
        return level.getRespawnData().pos();
    }

    /** The game's version as it names itself: 26.1.2. */
    public static String versionName() {
        return SharedConstants.getCurrentVersion().name();
    }

    /** The data version the game writes into what it saves. */
    public static int dataVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    /** Adds the entities a generated chunk carries (as saved tags) to the level, as the server does when the chunk becomes full. */
    public static void addGeneratedEntities(ServerLevel level, ChunkAccess chunk, List<CompoundTag> entities) {
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(chunk.problemPath(), PreGenMod.LOGGER)) {
            level.addWorldGenChunkEntities(EntityType.loadEntitiesRecursive(
                    TagValueInput.create(reporter, level.registryAccess(), entities), level, EntitySpawnReason.LOAD));
        }
    }
}
