package com.example.pregen;

import com.google.gson.JsonElement;
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
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.storage.LevelResource;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.Plugin;

/**
 * Ultra Fast Pregen's side of PregenWorlds on a Bukkit server. A separate world is a Bukkit world, which Bukkit names and
 * places itself, and Folia cannot create one. On Paper and Folia chunks go through Paper's chunk system (MoonriseChunkIO),
 * where a chunk's check and its write are two steps, so what the server holds must be left out first (heldChunks).
 */
final class PregenWorldsImpl implements PregenWorlds {
    static final PregenWorldsImpl INSTANCE = new PregenWorldsImpl();

    private final Map<String, World> separate = new ConcurrentHashMap<>();

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

    /** On the server thread; Folia has none, and what comes here runs on its global region. */
    private static <T> T onServer(Supplier<T> action) {
        if (Bukkit.isPrimaryThread()) return action.get();
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runOnServer(server(), () -> {
            try {
                result.complete(action.get());
            } catch (RuntimeException | Error failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.join();
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
        for (ServerLevel level : PregenRuns.levels(server())) ids.add(level.dimension().identifier().toString());
        return ids;
    }

    @Override
    public Path dimensionFolder(String dimension) {
        return Platform.dimensionFolder(level(dimension)).toAbsolutePath().normalize();
    }

    @Override
    public ServerFacts facts() {
        MinecraftServer server = server();
        Map<String, DimensionFacts> dimensions = new TreeMap<>();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        for (ServerLevel level : PregenRuns.levels(server)) {
            String id = level.dimension().identifier().toString();
            if (separate.containsKey(id)) continue;
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            String settings = ChunkGenerator.CODEC.encodeStart(ops, generator).result()
                    .map(JsonElement::toString).orElse("not encodable: " + generator.getClass().getName());
            dimensions.put(id, new DimensionFacts(sha256(settings), probe(level)));
        }
        Map<String, String> plugins = new TreeMap<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) plugins.put(plugin.getName(), plugin.getDescription().getVersion());
        return new ServerFacts(SharedConstants.getCurrentVersion().name(), dataVersion(), server.overworld().getSeed(), "bukkit", plugins,
                new ArrayList<>(server.getPackRepository().getSelectedIds()), dimensions);
    }

    @Override
    public int dataVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
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
                    int y = Math.max(level.getMinY(), Math.min(height, level.getMaxY()));
                    Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y),
                            QuartPos.fromBlock(z), random.sampler());
                    out.append(x).append(',').append(z).append('=').append(height).append(' ')
                            .append(biome.unwrapKey().map(key -> key.identifier().toString()).orElse("?")).append(';');
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

    /** Bukkit worlds have a plain name. */
    private static String worldName(String id) {
        return id.replace(':', '_').replace('/', '_');
    }

    /** Paper keeps every world in the main one; Spigot gives each a world folder of its own, with one dimension of its kind in it. */
    @Override
    public Path separateWorldFolder(String like, String id) {
        String name = worldName(id);
        if (Platform.MOONRISE) return worldFolder().resolve("dimensions").resolve("minecraft").resolve(name);
        String kind = switch (level(like).getWorld().getEnvironment()) {
            case NETHER -> "the_nether";
            case THE_END -> "the_end";
            default -> "overworld";
        };
        return Bukkit.getWorldContainer().toPath().resolve(name).resolve("dimensions").resolve("minecraft").resolve(kind)
                .toAbsolutePath().normalize();
    }

    @Override
    public String openSeparateWorld(String like, String id) throws IOException {
        if (Platform.FOLIA) throw new IOException("Folia cannot create a world while it runs.");
        Path expected = separateWorldFolder(like, id);
        World world = onServer(() -> new WorldCreator(worldName(id)).copy(level(like).getWorld()).createWorld());
        if (world == null) throw new IOException("The server did not create the world " + worldName(id) + ".");
        String actual = world.getKey().toString();
        separate.put(actual, world);
        Path folder = dimensionFolder(actual);
        if (!folder.equals(expected)) {
            closeSeparateWorld(actual);
            throw new IOException("The world " + actual + " is in " + folder + ", not in " + expected + ".");
        }
        PreGenMod.LOGGER.info("[PreGen] Opened the separate world {} for {}", actual, like);
        return actual;
    }

    @Override
    public void closeSeparateWorld(String id) throws IOException {
        World world = separate.remove(id);
        if (world == null) return;
        if (!onServer(() -> Bukkit.unloadWorld(world, true))) throw new IOException("The server kept the world " + id + ".");
        PreGenMod.LOGGER.info("[PreGen] Closed the separate world {}", id);
    }

    @Override
    public List<StoredChunk> readChunks(String dimension, long[] chunks) throws IOException {
        ChunkStorage storage = Platform.storage(level(dimension));
        Map<Long, CompletableFuture<Optional<CompoundTag>>> loads = new LinkedHashMap<>();
        for (long chunk : chunks) loads.put(chunk, storage.load(chunk));
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
        return Platform.findHeld(level(dimension), new LongArrayList(chunks)).join().toLongArray();
    }

    @Override
    public WriteResult writeChunks(String dimension, Store store, List<ChunkWrite> chunks, WriteRule rule, boolean dryRun) throws IOException {
        ServerLevel level = level(dimension);
        return (store == Store.CHUNKS ? Platform.storage(level) : Platform.entityStorage(level)).writeFrames(chunks, rule, dryRun);
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
        // Once the server ticks: its command map is complete, and the command list goes to players again.
        Platform.runNextTick(Platform.plugin, () -> UltraFastPregenPlugin.addCommand(new BukkitCommand(name, handler)));
    }

    private static final class BukkitCommand extends Command {
        private final CommandHandler handler;

        BukkitCommand(String name, CommandHandler handler) {
            super(name);
            this.handler = handler;
            setPermission(UltraFastPregenPlugin.COMMAND_PERMISSION);
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] arguments) {
            if (!testPermission(sender)) return true;
            handler.run(String.join(" ", arguments), new Reply(sender));
            return true;
        }
    }

    private record Reply(CommandSender sender) implements CommandReply {
        @Override
        public void tell(String message, boolean toOperators) {
            sender.sendMessage(message);
            if (toOperators && !(sender instanceof ConsoleCommandSender)) PreGenMod.LOGGER.info(message);
        }

        @Override
        public void fail(String message) {
            sender.sendMessage(ChatColor.RED + message);
        }

        @Override
        public boolean hasPermission(int level) {
            return sender instanceof ConsoleCommandSender || sender.isOp();
        }

        @Override
        public String name() {
            return sender.getName();
        }
    }
}
