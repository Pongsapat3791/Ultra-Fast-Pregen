package com.example.pregen;

import com.example.pregen.mixin.ChunkStorageAccessor;
import com.example.pregen.mixin.EntityStorageAccessor;
import com.example.pregen.mixin.MinecraftServerAccessor;
import com.example.pregen.mixin.PersistentEntitySectionManagerAccessor;
import com.example.pregen.mixin.ServerLevelAccessor;
import com.example.pregen.mixin.SimpleRegionStorageAccessor;
import com.google.gson.JsonElement;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.serialization.JsonOps;
import io.github.pongsapat3791.ultrafastpregen.api.PregenWorlds;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.WorldData;

/** Ultra Fast Pregen's side of PregenWorlds: all another mod needs of Minecraft to bring chunks into a world. */
final class PregenWorldsImpl implements PregenWorlds {
    static final PregenWorldsImpl INSTANCE = new PregenWorldsImpl();

    /** A separate world reports nothing while it opens. */
    private static final ChunkProgressListener NO_PROGRESS = new ChunkProgressListener() {
        @Override
        public void updateSpawnPos(ChunkPos center) {}

        @Override
        public void onStatusChange(ChunkPos pos, ChunkStatus status) {}

        @Override
        public void start() {}

        @Override
        public void stop() {}
    };

    private final Map<String, CommandHandler> commands = new ConcurrentHashMap<>();
    private final Set<String> separate = ConcurrentHashMap.newKeySet();

    private PregenWorldsImpl() {}

    private static MinecraftServer server() {
        MinecraftServer server = PregenRuns.server;
        if (server == null) throw new IllegalStateException("No server is running.");
        return server;
    }

    private static ServerLevel level(String dimension) {
        ServerLevel level = PregenRuns.level(server(), dimension);
        if (level == null) throw new IllegalArgumentException("Dimension " + dimension + " is not loaded.");
        return level;
    }

    private static <T> T onServer(Supplier<T> action) {
        MinecraftServer server = server();
        return server.isSameThread() ? action.get() : server.submit(action).join();
    }

    @Override
    public boolean serverRunning() {
        MinecraftServer server = PregenRuns.server;
        return server != null && server.isRunning();
    }

    @Override
    public Path worldFolder() {
        return server().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
    }

    @Override
    public Path serverFolder() {
        return server().getServerDirectory().toAbsolutePath().normalize();
    }

    @Override
    public List<String> dimensions() {
        List<String> ids = new ArrayList<>();
        for (ServerLevel level : PregenRuns.levels(server())) ids.add(level.dimension().location().toString());
        return ids;
    }

    @Override
    public Path dimensionFolder(String dimension) {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension));
        return DimensionType.getStorageFolder(key, worldFolder()).toAbsolutePath().normalize();
    }

    @Override
    public ServerFacts facts() {
        MinecraftServer server = server();
        Map<String, DimensionFacts> dimensions = new TreeMap<>();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        for (ServerLevel level : PregenRuns.levels(server)) {
            String id = level.dimension().location().toString();
            if (separate.contains(id)) continue;
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            String settings = ChunkGenerator.CODEC.encodeStart(ops, generator).result()
                    .map(JsonElement::toString).orElse("not encodable: " + generator.getClass().getName());
            dimensions.put(id, new DimensionFacts(sha256(settings), probe(level)));
        }
        return new ServerFacts(SharedConstants.getCurrentVersion().getName(), SharedConstants.getCurrentVersion().getDataVersion().getVersion(),
                server.getWorldData().worldGenOptions().seed(), PreGenMod.loader, new TreeMap<>(PreGenMod.mods),
                new ArrayList<>(server.getPackRepository().getSelectedIds()), dimensions);
    }

    @Override
    public int dataVersion() {
        return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    }

    /** The terrain height and biome the generator gives at 64 places spread over 32,000 blocks. */
    private static String probe(ServerLevel level) {
        StringBuilder out = new StringBuilder();
        try {
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            RandomState random = level.getChunkSource().randomState();
            for (int i = 0; i < 8; i++) {
                for (int j = 0; j < 8; j++) {
                    int x = (i - 4) * 4099 + 13;
                    int z = (j - 4) * 4099 + 7;
                    int height = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
                    int y = Math.max(level.getMinBuildHeight(), Math.min(height, level.getMaxBuildHeight() - 1));
                    Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y),
                            QuartPos.fromBlock(z), random.sampler());
                    out.append(x).append(',').append(z).append('=').append(height).append(' ')
                            .append(biome.unwrapKey().map(key -> key.location().toString()).orElse("?")).append(';');
                }
            }
        } catch (RuntimeException failure) {
            // A generator that cannot answer outside a world: both sides fail the same way or differ.
            out.append("failed: ").append(failure.getClass().getName());
        }
        return sha256(out.toString());
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public int neighborChunks() {
        return InMemoryChunkGrid.PADDING;
    }

    @Override
    public Path separateWorldFolder(String like, String id) {
        return dimensionFolder(id);
    }

    @Override
    public String openSeparateWorld(String like, String id) {
        return onServer(() -> {
            MinecraftServer server = server();
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(id));
            if (server.getLevel(key) != null) throw new IllegalStateException("The world " + id + " is open.");
            ServerLevel target = level(like);
            MinecraftServerAccessor access = (MinecraftServerAccessor) server;
            WorldData data = server.getWorldData();
            LevelStem stem = new LevelStem(target.dimensionTypeRegistration(), target.getChunkSource().getGenerator());
            ServerLevel level = new ServerLevel(server, access.pregen$getExecutor(), access.pregen$getStorageSource(),
                    new DerivedLevelData(data, data.overworldData()), key, stem, NO_PROGRESS, data.isDebugWorld(),
                    BiomeManager.obfuscateSeed(data.worldGenOptions().seed()), List.of(), false,
                    server.overworld().getRandomSequences());
            access.pregen$getLevels().put(key, level);
            separate.add(id);
            PreGenMod.LOGGER.info("[PreGen] Opened the separate world {} for {}", id, like);
            return id;
        });
    }

    @Override
    public void closeSeparateWorld(String id) throws IOException {
        IOException failed = onServer(() -> {
            try {
                ServerLevel level = PregenRuns.level(server(), id);
                if (level == null) return null;
                ((MinecraftServerAccessor) server()).pregen$getLevels().remove(level.dimension());
                level.save(null, true, false);
                level.close();
                PreGenMod.LOGGER.info("[PreGen] Closed the separate world {}", id);
                return null;
            } catch (IOException failure) {
                return failure;
            } finally {
                separate.remove(id);
            }
        });
        if (failed != null) throw failed;
    }

    @Override
    public List<StoredChunk> readChunks(String dimension, long[] chunks) throws IOException {
        WorldChunkIO io = WorldChunkIO.forLevel(level(dimension));
        Map<Long, CompletableFuture<Optional<CompoundTag>>> loads = new LinkedHashMap<>();
        for (long chunk : chunks) loads.put(chunk, io.load(chunk));
        List<StoredChunk> stored = new ArrayList<>();
        for (Map.Entry<Long, CompletableFuture<Optional<CompoundTag>>> load : loads.entrySet()) {
            Optional<CompoundTag> tag = join(load.getValue());
            if (tag.isEmpty()) continue;
            stored.add(new StoredChunk(load.getKey(), WorldChunkIO.encode(tag.get()), WorldChunkIO.hash(tag.get()),
                    WorldChunkIO.isFullyGenerated(tag.get())));
        }
        return stored;
    }

    @Override
    public long[] heldChunks(String dimension, long[] chunks) {
        return WorldChunkIO.findHeldByServer(level(dimension), new LongArrayList(chunks)).join().toLongArray();
    }

    @Override
    public WriteResult writeChunks(String dimension, Store store, List<ChunkWrite> chunks, WriteRule rule, boolean dryRun) throws IOException {
        return io(level(dimension), store).writeFrames(chunks, rule, dryRun);
    }

    private static WorldChunkIO io(ServerLevel level, Store store) throws IOException {
        if (store == Store.CHUNKS) return WorldChunkIO.forLevel(level);
        Object manager = ((ServerLevelAccessor) level).pregen$getEntityManager();
        EntityPersistentStorage<?> storage = ((PersistentEntitySectionManagerAccessor) manager).pregen$getPermanentStorage();
        if (!(storage instanceof EntityStorage entities)) {
            throw new IOException("Another mod replaced the entities storage of " + level.dimension().location() + ".");
        }
        Object regions = ((EntityStorageAccessor) (Object) entities).pregen$getSimpleRegionStorage();
        return new WorldChunkIO(((SimpleRegionStorageAccessor) regions).pregen$getWorker());
    }

    static <T> T join(CompletableFuture<T> future) throws IOException {
        try {
            return future.join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            if (cause instanceof IOException io) throw io;
            throw new IOException(cause.getMessage(), cause);
        }
    }

    @Override
    public void addCommand(String name, CommandHandler handler) {
        commands.put(name, handler);
    }

    /** From PreGenCommand.register. */
    void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        commands.forEach((name, handler) -> dispatcher.register(Commands.literal(name)
                .requires(source -> source.hasPermission(2) || PreGenCommand.isSingleplayerOwner(source))
                .executes(context -> run(handler, "", context.getSource()))
                .then(Commands.argument("arguments", StringArgumentType.greedyString())
                        .executes(context -> run(handler, StringArgumentType.getString(context, "arguments"), context.getSource())))));
    }

    private static int run(CommandHandler handler, String arguments, CommandSourceStack source) {
        return handler.run(arguments, new Reply(source)) ? 1 : 0;
    }

    private record Reply(CommandSourceStack source) implements CommandReply {
        @Override
        public void tell(String message, boolean toOperators) {
            source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(message), toOperators));
        }

        @Override
        public void fail(String message) {
            source.getServer().execute(() -> source.sendFailure(Component.literal(message)));
        }

        @Override
        public boolean hasPermission(int level) {
            return source.hasPermission(level) || PreGenCommand.isSingleplayerOwner(source);
        }

        @Override
        public String name() {
            return source.getTextName();
        }
    }
}
