package io.github.pongsapat3791.ufpmanager;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Which chunks a world has, from the headers of its region files. It reads only the files' first 4 KiB, with no help from
 * Minecraft, so it works alike on every version, and on a running server: a chunk in the header exists on disk, whatever
 * its status. Ultra Fast Pregen says where the running server's worlds are; without it, or before it has the API for that,
 * server.properties names the world, and in singleplayer the map shows only what a run saves.
 */
final class RegionMap {
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");
    private static final int HEADER = 4096;

    /** The running server's worlds as Ultra Fast Pregen knows them; null where there is no telling. */
    interface LiveWorlds {
        Path worldFolder();

        Path dimensionFolder(String dimension);

        List<String> dimensions();
    }

    private final Path gameDirectory;
    private volatile LiveWorlds live;

    RegionMap(Path gameDirectory) {
        this.gameDirectory = gameDirectory;
    }

    void use(LiveWorlds worlds) {
        live = worlds;
    }

    static boolean validDimension(String id) {
        return DIMENSION.matcher(id).matches() && !id.contains("..");
    }

    /** level-name of server.properties; "world" without one. */
    String worldName() {
        Path liveWorld = live != null ? live.worldFolder() : null;
        if (liveWorld != null) return liveWorld.getFileName().toString();
        Path file = gameDirectory.resolve("server.properties");
        if (!Files.isRegularFile(file)) return null;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException failure) {
            return "world";
        }
        return properties.getProperty("level-name", "world");
    }

    private List<Path> folders(String dimension) {
        List<Path> folders = new ArrayList<>();
        Path liveFolder = live != null ? live.dimensionFolder(dimension) : null;
        if (liveFolder != null) folders.add(liveFolder.resolve("region"));
        String world = worldName();
        if (world == null) return folders;
        String[] id = dimension.split(":", 2);
        Path root = gameDirectory.resolve(world);
        folders.add(root.resolve("dimensions").resolve(id[0]).resolve(id[1]).resolve("region"));
        switch (dimension) {
            case "minecraft:overworld" -> folders.add(root.resolve("region"));
            case "minecraft:the_nether" -> {
                folders.add(root.resolve("DIM-1/region"));
                folders.add(gameDirectory.resolve(world + "_nether/DIM-1/region"));
                folders.add(gameDirectory.resolve(world + "_nether/dimensions/minecraft/the_nether/region"));
            }
            case "minecraft:the_end" -> {
                folders.add(root.resolve("DIM1/region"));
                folders.add(gameDirectory.resolve(world + "_the_end/DIM1/region"));
                folders.add(gameDirectory.resolve(world + "_the_end/dimensions/minecraft/the_end/region"));
            }
            default -> {
            }
        }
        return folders;
    }

    private Path folder(String dimension) {
        for (Path candidate : folders(dimension)) {
            if (Files.isDirectory(candidate)) return candidate;
        }
        return null;
    }

    /** The region's file, or null when the dimension has no folder. */
    Path regionFile(String dimension, int regionX, int regionZ) {
        Path folder = folder(dimension);
        return folder == null ? null : folder.resolve("r." + regionX + "." + regionZ + ".mca");
    }

    /** The three vanilla dimensions and any other found on disk. */
    List<String> dimensions() {
        Set<String> found = new LinkedHashSet<>(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
        if (live != null) found.addAll(live.dimensions());
        String world = worldName();
        if (world == null) return new ArrayList<>(found);
        Path root = gameDirectory.resolve(world).resolve("dimensions");
        if (!Files.isDirectory(root)) return new ArrayList<>(found);
        try (Stream<Path> namespaces = Files.list(root)) {
            for (Path namespace : (Iterable<Path>) namespaces::iterator) {
                try (Stream<Path> paths = Files.walk(namespace, 4)) {
                    paths.filter(path -> path.endsWith("region") && Files.isDirectory(path)).forEach(region -> {
                        String path = namespace.relativize(region.getParent()).toString().replace('\\', '/');
                        String id = namespace.getFileName() + ":" + path;
                        if (validDimension(id)) found.add(id);
                    });
                }
            }
        } catch (IOException failure) {
            // the three vanilla ones are still there
        }
        return new ArrayList<>(found);
    }

    /** Every region of the dimension, as [x, z, chunks on disk]. */
    List<int[]> regions(String dimension) throws IOException {
        List<int[]> regions = new ArrayList<>();
        Path folder = folder(dimension);
        if (folder == null) return regions;
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Matcher name = REGION_FILE.matcher(file.getFileName().toString());
                if (!name.matches()) continue;
                byte[] bits = header(file);
                int count = 0;
                for (byte value : bits) count += Integer.bitCount(value & 255);
                if (count > 0) regions.add(new int[] {Integer.parseInt(name.group(1)), Integer.parseInt(name.group(2)), count});
            }
        }
        return regions;
    }

    /** The region's chunks as 1,024 bits (128 bytes): bit x + 32 * z, lowest bit of each byte first. */
    byte[] chunks(String dimension, int regionX, int regionZ) throws IOException {
        Path folder = folder(dimension);
        if (folder == null) return new byte[128];
        Path file = folder.resolve("r." + regionX + "." + regionZ + ".mca");
        return Files.isRegularFile(file) ? header(file) : new byte[128];
    }

    private static byte[] header(Path file) throws IOException {
        byte[] bits = new byte[128];
        ByteBuffer buffer = ByteBuffer.allocate(HEADER);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // until the header is in, or the file ends
            }
        }
        buffer.flip();
        for (int i = 0; i + 4 <= buffer.limit(); i += 4) {
            if (buffer.getInt(i) != 0) {
                int chunk = i / 4;
                bits[chunk >> 3] |= (byte) (1 << (chunk & 7));
            }
        }
        return bits;
    }
}
