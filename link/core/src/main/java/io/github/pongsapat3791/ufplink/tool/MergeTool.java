package io.github.pongsapat3791.ufplink.tool;

import io.github.pongsapat3791.ufplink.region.RegionReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * The offline merge tool: {@code java -jar ufplink.jar merge <generated world> <target>}. Adds the
 * chunks of a world generated elsewhere to the world of a stopped server by the rules of /ufplink
 * import, without Minecraft, so it also serves servers that cannot run the mod: Paper and the other
 * Bukkit-based servers, which keep the nether and the end in world folders of their own.
 */
public final class MergeTool {
    private static final String NAME = "Ultra Fast Pregen Link";
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final String END = "minecraft:the_end";
    private static final long PROGRESS_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final int LOGGED_REJECTIONS = 10;
    private static final Set<String> CHUNK_TAGS = Set.of("DataVersion", "xPos", "zPos", "Status");
    private static final Set<String> ENTITY_TAGS = Set.of("DataVersion", "Position");
    /** spigot.yml's structure seeds at their defaults, which are Minecraft's own (Paper uses these). */
    private static final Map<String, String> SPIGOT_SEEDS = new LinkedHashMap<>();

    static {
        SPIGOT_SEEDS.put("seed-village", "10387312");
        SPIGOT_SEEDS.put("seed-desert", "14357617");
        SPIGOT_SEEDS.put("seed-igloo", "14357618");
        SPIGOT_SEEDS.put("seed-jungle", "14357619");
        SPIGOT_SEEDS.put("seed-swamp", "14357620");
        SPIGOT_SEEDS.put("seed-monument", "10387313");
        SPIGOT_SEEDS.put("seed-shipwreck", "165745295");
        SPIGOT_SEEDS.put("seed-ocean", "14357621");
        SPIGOT_SEEDS.put("seed-outpost", "165745296");
        SPIGOT_SEEDS.put("seed-endcity", "10387313");
        SPIGOT_SEEDS.put("seed-nether", "30084232");
        SPIGOT_SEEDS.put("seed-mansion", "10387319");
        SPIGOT_SEEDS.put("seed-fossil", "14357921");
        SPIGOT_SEEDS.put("seed-portal", "34222645");
        SPIGOT_SEEDS.put("seed-ancientcity", "20083232");
        SPIGOT_SEEDS.put("seed-trailruins", "83469867");
        SPIGOT_SEEDS.put("seed-trialchambers", "94251327");
        SPIGOT_SEEDS.put("seed-buriedtreasure", "10387320");
        SPIGOT_SEEDS.put("seed-mineshaft", "default");
        SPIGOT_SEEDS.put("seed-stronghold", "default");
    }

    private MergeTool() {}

    private static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    /**
     * Where a world keeps its dimensions. {@code world}: the folder with the overworld's level.dat;
     * {@code server}: the server folder holding the settings, or null; {@code bukkit}: the nether, the
     * end and other dimensions live in world folders of their own (world_nether/DIM-1, world_the_end/DIM1).
     * Since Minecraft 26.1 every dimension, the overworld too, is in dimensions/namespace/name, and Paper
     * keeps them all in the main world.
     */
    private record Layout(Path world, Path server, boolean bukkit) {
        boolean allInDimensions() {
            return Files.isDirectory(world.resolve("dimensions").resolve("minecraft").resolve("overworld"));
        }

        /** The world folder (level.dat, session.lock) of a dimension. */
        Path root(String id) {
            if (!bukkit || id.equals(OVERWORLD)) return world;
            String suffix = switch (id) {
                case NETHER -> "nether";
                case END -> "the_end";
                default -> id.replace(':', '_');
            };
            Path own = world.resolveSibling(world.getFileName() + "_" + suffix);
            return allInDimensions() && !Files.isDirectory(own) ? world : own;
        }

        /** The folder holding a dimension's region, entities and poi folders. */
        Path storage(String id) {
            Path root = root(id);
            int colon = id.indexOf(':');
            Path named = root.resolve("dimensions").resolve(id.substring(0, colon)).resolve(id.substring(colon + 1));
            if (allInDimensions()) return named;
            return switch (id) {
                case OVERWORLD -> root;
                case NETHER -> root.resolve("DIM-1");
                case END -> root.resolve("DIM1");
                default -> named;
            };
        }

        String describe() {
            return world + (bukkit ? " (Paper/Spigot layout" : " (vanilla layout") + (server != null ? ", server folder " + server + ")" : ")");
        }
    }

    private record Dimension(String id, Path source, Path target, Path targetRoot, List<Path> regions) {}

    /** What one dimension's merge did (or a check would do). */
    private static final class Counts {
        final AtomicLong regions = new AtomicLong();
        final AtomicLong added = new AtomicLong();
        final AtomicLong replaced = new AtomicLong();
        final AtomicLong kept = new AtomicLong();
        final AtomicLong rejected = new AtomicLong();
        final AtomicLong unverified = new AtomicLong();
        final AtomicLong entities = new AtomicLong();
        final AtomicLong poi = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
    }

    private static final class Findings {
        final Set<String> refused = new LinkedHashSet<>();
        final Set<String> differences = new LinkedHashSet<>();
        final Set<String> warnings = new LinkedHashSet<>();
        final Set<String> notes = new LinkedHashSet<>();
    }

    private static final AtomicLong REJECTIONS = new AtomicLong();
    private static volatile boolean stopping;

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (UsageException usage) {
            System.err.println(usage.getMessage());
            System.err.println();
            System.err.println(usage());
            code = 2;
        } catch (IOException | RuntimeException failure) {
            System.err.println("Failed: " + failure);
            code = 1;
        }
        System.exit(code);
    }

    private static String version() {
        String version = MergeTool.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }

    private static String usage() {
        return String.join(System.lineSeparator(),
                NAME + " " + version() + ": merge tool",
                "",
                "  java -jar <this jar> merge <generated world> <target> [options]",
                "  java -jar <this jar> check <generated world> <target> [options]",
                "",
                "Adds the chunks of a world generated elsewhere (for example with /ufplink pregen on a Fabric server)",
                "to the world of a stopped server: Paper, Spigot, Purpur, vanilla, Fabric or NeoForge.",
                "  <generated world>  the generated world's folder (the one with level.dat), or its server folder",
                "  <target>           the target server's folder (with server.properties), or its world folder",
                "Chunks the target lacks are added with their mobs, part-generated chunks of the target are replaced",
                "by fully generated ones, and every other chunk stays as it is. 'check' reports what 'merge' would do",
                "without writing anything.",
                "",
                "Options:",
                "  --dimension <id>  only this dimension: overworld, the_nether, the_end or a full id (repeatable)",
                "  --force           merge although generation settings, datapacks, Paper/Spigot settings differ,",
                "                    or the free space looks too small",
                "  --threads <n>     region files merged at once (default " + defaultThreads() + ")");
    }

    private static int defaultThreads() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    }

    private static int run(String[] args) throws UsageException, IOException {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help") || args[0].equals("-h")) {
            System.out.println(usage());
            return args.length == 0 ? 2 : 0;
        }
        boolean dryRun = switch (args[0]) {
            case "merge" -> false;
            case "check" -> true;
            default -> throw new UsageException("Unknown command: " + args[0]);
        };
        List<String> paths = new ArrayList<>();
        Set<String> only = new LinkedHashSet<>();
        boolean force = false;
        int threads = defaultThreads();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--force" -> force = true;
                case "--dry-run" -> dryRun = true;
                case "--dimension" -> {
                    if (++i >= args.length) throw new UsageException("--dimension needs a dimension");
                    only.add(dimensionId(args[i]));
                }
                case "--threads" -> {
                    if (++i >= args.length) throw new UsageException("--threads needs a number");
                    try {
                        threads = Integer.parseInt(args[i]);
                    } catch (NumberFormatException bad) {
                        throw new UsageException("--threads needs a number, not " + args[i]);
                    }
                    if (threads < 1 || threads > 64) throw new UsageException("--threads must be 1 to 64");
                }
                default -> {
                    if (arg.startsWith("--")) throw new UsageException("Unknown option: " + arg);
                    paths.add(arg);
                }
            }
        }
        if (paths.size() != 2) throw new UsageException("Give the generated world and the target.");

        System.out.println(NAME + " " + version() + " merge tool" + (dryRun ? " (check: nothing is written)" : ""));
        Layout source = layout(Path.of(paths.get(0)), "generated world");
        Layout target = layout(Path.of(paths.get(1)), "target");
        System.out.println("Generated world: " + source.describe());
        System.out.println("Target:          " + target.describe());
        if (Files.isSameFile(source.world(), target.world())) {
            System.out.println("Refused: the generated world and the target are the same world.");
            return 1;
        }

        Findings findings = new Findings();
        List<Dimension> dimensions = dimensions(source, target, only, findings);
        List<FileLock> locks = new ArrayList<>();
        try {
            Set<Path> targetRoots = new LinkedHashSet<>();
            targetRoots.add(target.world());
            for (Dimension dimension : dimensions) targetRoots.add(dimension.targetRoot());
            for (Path root : targetRoots) {
                if (!lock(root, locks)) {
                    String line = root + " is open in a running server (its session.lock is held).";
                    if (dryRun) findings.warnings.add(line + " The counts may change before the merge; stop the server before merging.");
                    else findings.refused.add(line + " Stop the server first.");
                }
            }
            Set<Path> sourceRoots = new LinkedHashSet<>();
            sourceRoots.add(source.world());
            for (Dimension dimension : dimensions) sourceRoots.add(source.root(dimension.id()));
            for (Path root : sourceRoots) {
                if (!lock(root, locks)) {
                    findings.refused.add("The generated world " + root + " is open in a running server (its session.lock is held):"
                            + " chunks it is saving could be read half-written. Stop that server first.");
                }
            }

            int targetDataVersion = checkLevels(source, target, dimensions, findings);
            checkServerSettings(target, dimensions, findings);
            checkFingerprints(source, target, findings);
            if (!dryRun) checkSpace(dimensions, threads, target, findings, force);

            for (String line : findings.notes) System.out.println("Note: " + line);
            for (String line : findings.warnings) System.out.println("Warning: " + line);
            for (String line : findings.differences) System.out.println((force ? "Differs (allowed by --force): " : "Differs: ") + line);
            for (String line : findings.refused) System.out.println("Refused: " + line);
            if (!findings.refused.isEmpty()) {
                System.out.println(dryRun ? "A merge would be refused." : "Nothing was merged.");
                return 1;
            }
            if (!findings.differences.isEmpty() && !force) {
                System.out.println((dryRun ? "A merge would be refused without --force." : "Nothing was merged.") + " Add --force"
                        + " only if the generated world was generated exactly like the target would generate it; otherwise its"
                        + " chunks will not join the ones the target's server generates next to them.");
                return 1;
            }
            if (dimensions.isEmpty()) {
                System.out.println("Nothing to merge: the two worlds have no dimension with chunks in common.");
                return 1;
            }
            return merge(dimensions, targetDataVersion, dryRun, threads);
        } finally {
            for (FileLock lock : locks) {
                try {
                    lock.channel().close();
                } catch (IOException ignored) {
                    // The lock goes with the process anyway.
                }
            }
        }
    }

    private static String dimensionId(String name) {
        String id = name.contains(":") ? name : "minecraft:" + name;
        return switch (id) {
            case "minecraft:nether" -> NETHER;
            case "minecraft:end" -> END;
            default -> id;
        };
    }

    /** The layout of the world at a folder: a world folder (with level.dat) or a server folder (with server.properties). */
    private static Layout layout(Path given, String what) throws IOException {
        Path folder = given.toAbsolutePath().normalize();
        if (!Files.isDirectory(folder)) throw new IOException("The " + what + " " + folder + " is not a folder.");
        Path world;
        Path server;
        if (Files.isRegularFile(folder.resolve("level.dat"))) {
            world = folder;
            Path parent = folder.getParent();
            server = parent != null && Files.isRegularFile(parent.resolve("server.properties")) ? parent : null;
        } else if (Files.isRegularFile(folder.resolve("server.properties"))) {
            server = folder;
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(folder.resolve("server.properties"), StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (java.nio.charset.MalformedInputException latin1) {
                try (Reader reader = Files.newBufferedReader(folder.resolve("server.properties"), StandardCharsets.ISO_8859_1)) {
                    properties.load(reader);
                }
            }
            String levelName = properties.getProperty("level-name", "world").strip();
            world = folder.resolve(levelName.isEmpty() ? "world" : levelName).normalize();
            if (!Files.isRegularFile(world.resolve("level.dat"))) {
                throw new IOException("The " + what + "'s world " + world + " has no level.dat. Start that server once so it"
                        + " creates its world, stop it, and run this again.");
            }
        } else {
            throw new IOException("The " + what + " " + folder + " has neither level.dat (a world folder) nor server.properties"
                    + " (a server folder).");
        }
        boolean bukkit = server != null && (Files.isRegularFile(server.resolve("bukkit.yml")) || Files.isRegularFile(server.resolve("spigot.yml")))
                || Files.isDirectory(world.resolveSibling(world.getFileName() + "_nether"))
                || Files.isDirectory(world.resolveSibling(world.getFileName() + "_the_end"));
        return new Layout(world, server, bukkit);
    }

    /** The dimensions with region files in the generated world whose folders the target has. */
    private static List<Dimension> dimensions(Layout source, Layout target, Set<String> only, Findings findings) throws IOException {
        Map<String, Path> found = new LinkedHashMap<>();
        for (String id : List.of(OVERWORLD, NETHER, END)) found.put(id, source.storage(id));
        Path custom = source.world().resolve("dimensions");
        if ((!source.bukkit() || source.allInDimensions()) && Files.isDirectory(custom)) {
            List<Path> regionFolders;
            try (Stream<Path> walk = Files.walk(custom, 6)) {
                regionFolders = walk.filter(path -> path.getFileName().toString().equals("region") && Files.isDirectory(path)).sorted().toList();
            }
            for (Path regionFolder : regionFolders) {
                Path relative = custom.relativize(regionFolder.getParent());
                if (relative.getNameCount() < 2) continue;
                String namespace = relative.getName(0).toString();
                // A pregen's separate world that an interrupted /ufplink pregen left behind.
                if (namespace.equals("ufplink") || relative.getName(1).toString().startsWith("ufplink_pregen_")) continue;
                found.put(namespace + ":" + relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/'), regionFolder.getParent());
            }
        }
        for (String id : only) {
            if (!found.containsKey(id)) findings.notes.add(id + " (--dimension) is not in the generated world.");
        }
        List<Dimension> dimensions = new ArrayList<>();
        for (Map.Entry<String, Path> entry : found.entrySet()) {
            String id = entry.getKey();
            if (!only.isEmpty() && !only.contains(id)) continue;
            List<Path> regions = regionFiles(entry.getValue().resolve("region"));
            if (regions.isEmpty()) continue;
            Path targetRoot = target.root(id);
            Path targetStorage = target.storage(id);
            boolean exists = Files.isDirectory(targetRoot) && (target.bukkit() || id.equals(OVERWORLD) || Files.isDirectory(targetStorage));
            if (!exists) {
                findings.notes.add(id + " is not in the target yet (no " + (target.bukkit() ? targetRoot : targetStorage) + "); skipped."
                        + " Start the target's server once so it creates it, stop it, and merge again.");
                continue;
            }
            dimensions.add(new Dimension(id, entry.getValue(), targetStorage, targetRoot, regions));
        }
        return dimensions;
    }

    private static List<Path> regionFiles(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> RegionReader.regionOf(file) != null && Files.isRegularFile(file)).sorted().toList();
        }
    }

    /** Takes the world's session.lock as Minecraft does; false when a running server holds it. */
    private static boolean lock(Path root, List<FileLock> locks) throws IOException {
        FileChannel channel;
        try {
            channel = FileChannel.open(root.resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException failure) {
            throw new IOException("Cannot open " + root.resolve("session.lock") + " (" + failure.getMessage() + "): run this as the"
                    + " user the server runs as.", failure);
        }
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (IOException | java.nio.channels.OverlappingFileLockException failure) {
            lock = null;
        }
        if (lock == null) {
            channel.close();
            return false;
        }
        locks.add(lock);
        return true;
    }

    private static final Map<Path, Map<String, Object>> LEVEL_DATA = new LinkedHashMap<>();

    /** The Data compound of the level.dat in a world folder; null when it has none. */
    private static Map<String, Object> levelData(Path root) throws IOException {
        if (LEVEL_DATA.containsKey(root)) return LEVEL_DATA.get(root);
        Path file = root.resolve("level.dat");
        Map<String, Object> data = Files.isRegularFile(file) ? Nbt.compound(Nbt.readGzipFile(file), "Data") : null;
        LEVEL_DATA.put(root, data);
        return data;
    }

    private static String versionName(Map<String, Object> data) {
        String name = Nbt.string(Nbt.compound(data, "Version"), "Name");
        return (name != null ? name : "?") + " (data version " + Nbt.number(data, "DataVersion", -1) + ")";
    }

    /**
     * Compares the level.dat files: Minecraft's version, the seed and each dimension's generator, and
     * the datapacks. Returns the target's data version (the newest chunk format its server reads).
     */
    private static int checkLevels(Layout source, Layout target, List<Dimension> dimensions, Findings findings) throws IOException {
        Map<String, Object> sourceWorld = levelData(source.world());
        Map<String, Object> targetWorld = levelData(target.world());
        if (sourceWorld == null || targetWorld == null) {
            findings.refused.add("A level.dat could not be read.");
            return -1;
        }
        int targetDataVersion = (int) Nbt.number(targetWorld, "DataVersion", -1);
        if (targetDataVersion <= 0) findings.refused.add("The target's level.dat names no Minecraft version.");
        for (Dimension dimension : dimensions) {
            Map<String, Object> sourceData = orElse(levelData(source.root(dimension.id())), sourceWorld);
            Map<String, Object> targetData = orElse(levelData(dimension.targetRoot()), targetWorld);
            long sourceVersion = Nbt.number(sourceData, "DataVersion", -1);
            long targetVersion = Nbt.number(targetData, "DataVersion", -1);
            if (sourceVersion > targetVersion) {
                findings.refused.add("The generated world is from a newer Minecraft, " + versionName(sourceData) + ", than the target, "
                        + versionName(targetData) + ": the target's server cannot read its chunks.");
            } else if (sourceVersion < targetVersion) {
                findings.differences.add("The generated world is from an older Minecraft, " + versionName(sourceData) + ", than the"
                        + " target, " + versionName(targetData) + ": its chunks would be upgraded, and generation changes between versions.");
            }
            Map<String, Object> sourceSettings = worldGenSettings(sourceData, dimension.source(), source.root(dimension.id()), source.world());
            Map<String, Object> targetSettings = worldGenSettings(targetData, dimension.target(), dimension.targetRoot(), target.world());
            long sourceSeed = Nbt.number(sourceSettings, "seed", Long.MIN_VALUE);
            long targetSeed = Nbt.number(targetSettings, "seed", Long.MIN_VALUE);
            if (sourceSeed == Long.MIN_VALUE || targetSeed == Long.MIN_VALUE) {
                findings.refused.add("The seed of " + dimension.id() + " could not be read from a level.dat.");
            } else if (sourceSeed != targetSeed) {
                findings.refused.add(dimension.id() + ": the seed differs: the generated world has " + sourceSeed + ", the target "
                        + targetSeed + (dimension.targetRoot().equals(target.world()) ? "." : " (in " + dimension.targetRoot().resolve("level.dat") + ")."));
            }
            Object sourceStem = Nbt.compound(Nbt.compound(sourceSettings, "dimensions"), dimension.id());
            Object targetStem = Nbt.compound(Nbt.compound(targetSettings, "dimensions"), dimension.id());
            if (sourceStem == null || targetStem == null) {
                findings.notes.add(dimension.id() + ": a level.dat does not describe its generator, so it was not compared.");
            } else if (!Nbt.same(sourceStem, targetStem)) {
                findings.differences.add(dimension.id() + ": the generator settings in level.dat differ (world type, preset or"
                        + " dimension type).");
            }
        }
        Set<String> sourcePacks = datapacks(sourceWorld);
        Set<String> targetPacks = datapacks(targetWorld);
        if (!sourcePacks.equals(targetPacks)) {
            Set<String> onlySource = new TreeSet<>(sourcePacks);
            onlySource.removeAll(targetPacks);
            Set<String> onlyTarget = new TreeSet<>(targetPacks);
            onlyTarget.removeAll(sourcePacks);
            findings.differences.add("Datapacks differ:" + (onlySource.isEmpty() ? "" : " only in the generated world " + onlySource + ";")
                    + (onlyTarget.isEmpty() ? "" : " only in the target " + onlyTarget + ";") + " datapacks can change generation.");
        }
        return targetDataVersion;
    }

    /**
     * The seed and the generators: in level.dat, and since Minecraft 26.1 in data/minecraft/world_gen_settings.dat of
     * the world, or on Paper of each dimension.
     */
    private static Map<String, Object> worldGenSettings(Map<String, Object> levelData, Path... folders) throws IOException {
        Map<String, Object> settings = Nbt.compound(levelData, "WorldGenSettings");
        if (settings != null) return settings;
        for (Path folder : folders) {
            Path file = folder.resolve("data").resolve("minecraft").resolve("world_gen_settings.dat");
            if (Files.isRegularFile(file)) return Nbt.compound(Nbt.readGzipFile(file), "data");
        }
        return null;
    }

    private static Map<String, Object> orElse(Map<String, Object> value, Map<String, Object> fallback) {
        return value != null ? value : fallback;
    }

    /** The enabled datapacks, without the ones a loader or server adds for itself. */
    private static Set<String> datapacks(Map<String, Object> data) {
        Set<String> packs = new TreeSet<>();
        Object enabled = Nbt.compound(data, "DataPacks") == null ? null : Nbt.compound(data, "DataPacks").get("Enabled");
        if (!(enabled instanceof List<?> list)) return packs;
        for (Object pack : list) {
            if (!(pack instanceof String id)) continue;
            boolean platform = id.equals("fabric") || id.startsWith("fabric-") || id.startsWith("fabric:") || id.equals("paper")
                    || id.equals("bukkit") || id.equals("file/bukkit") || id.equals("neoforge") || id.equals("forge")
                    || id.equals("mod_resources") || id.startsWith("mod:");
            if (!platform) packs.add(id);
        }
        return packs;
    }

    /** Settings of Bukkit-based servers that change generation: structure seeds, feature seeds, generator plugins. */
    private static void checkServerSettings(Layout target, List<Dimension> dimensions, Findings findings) throws IOException {
        if (target.server() == null) {
            if (target.bukkit()) {
                findings.warnings.add("No server folder next to the target world, so spigot.yml, bukkit.yml and Paper's world"
                        + " settings were not checked. Give the server folder as the target to check them.");
            }
            return;
        }
        Map<String, Object> spigot = Yaml.read(target.server().resolve("spigot.yml"));
        Map<String, Object> bukkit = Yaml.read(target.server().resolve("bukkit.yml"));
        Map<String, Object> paperDefaults = Yaml.read(target.server().resolve("config").resolve("paper-world-defaults.yml"));
        for (Dimension dimension : dimensions) {
            String world = dimension.targetRoot().getFileName().toString();
            List<String> seeds = new ArrayList<>();
            for (Map.Entry<String, String> seed : SPIGOT_SEEDS.entrySet()) {
                Object value = Yaml.get(spigot, "world-settings", world, seed.getKey());
                if (value == null) value = Yaml.get(spigot, "world-settings", "default", seed.getKey());
                if (value instanceof String text && !text.strip().equals(seed.getValue())) seeds.add(seed.getKey() + ": " + text);
            }
            if (!seeds.isEmpty()) {
                findings.differences.add(world + ": spigot.yml sets structure seeds Minecraft does not use (" + String.join(", ", seeds)
                        + "): the target places those structures elsewhere.");
            }
            Object generator = Yaml.get(bukkit, "worlds", world, "generator");
            if (generator instanceof String text && !text.isBlank()) {
                findings.differences.add(world + ": bukkit.yml gives it the generator " + text + " (a plugin), not Minecraft's.");
            }
            Path inDimension = dimension.target().resolve("paper-world.yml");
            Map<String, Object> paperWorld = Yaml.read(Files.isRegularFile(inDimension) ? inDimension : dimension.targetRoot().resolve("paper-world.yml"));
            Object random = paperValue(paperWorld, paperDefaults, "feature-seeds", "generate-random-seeds-for-all");
            if (random instanceof String text && text.strip().equalsIgnoreCase("true")) {
                findings.differences.add(world + ": Paper's feature-seeds.generate-random-seeds-for-all is true: ores, trees and other"
                        + " features are placed with other seeds.");
            }
            Set<String> custom = new TreeSet<>();
            for (Map<String, Object> config : List.of(paperDefaults, paperWorld)) {
                if (Yaml.get(config, "feature-seeds") instanceof Map<?, ?> seedsSection) {
                    for (Object key : seedsSection.keySet()) {
                        if (!key.equals("generate-random-seeds-for-all")) custom.add(key.toString());
                    }
                }
            }
            if (!custom.isEmpty()) {
                findings.differences.add(world + ": Paper's feature-seeds sets seeds for " + custom + ": those features are placed differently.");
            }
            Object flat = paperValue(paperWorld, paperDefaults, "environment", "generate-flat-bedrock");
            if (flat instanceof String text && text.strip().equalsIgnoreCase("true")) {
                findings.warnings.add(world + ": Paper generates flat bedrock there; the merged chunks have Minecraft's uneven bedrock.");
            }
        }
    }

    /** A Paper world setting: the world's paper-world.yml over config/paper-world-defaults.yml. */
    private static Object paperValue(Map<String, Object> world, Map<String, Object> defaults, String... keys) {
        Object value = Yaml.get(world, keys);
        return value != null ? value : Yaml.get(defaults, keys);
    }

    /** The mods the generated world was made with, from the fingerprint /ufplink writes. */
    @SuppressWarnings("unchecked")
    private static void checkFingerprints(Layout source, Layout target, Findings findings) {
        Map<String, Object> sourcePrint = fingerprint(source.world(), findings);
        Map<String, Object> targetPrint = fingerprint(target.world(), findings);
        if (sourcePrint == null) {
            findings.notes.add("The generated world has no ufplink-fingerprint.json (it was generated without " + NAME
                    + "), so its mods were not checked: merge only worlds generated without mods that change generation.");
            return;
        }
        Map<String, Object> sourceMods = sourcePrint.get("mods") instanceof Map<?, ?> mods ? (Map<String, Object>) mods : Map.of();
        if (targetPrint == null) {
            if (!sourceMods.isEmpty()) {
                List<String> mods = new ArrayList<>();
                new TreeMap<>(sourceMods).forEach((id, version) -> mods.add(id + " " + version));
                findings.warnings.add("The generated world was made with these mods: " + String.join(", ", mods) + ". If any of them"
                        + " changes generation (terrain, biomes, structures, ores), its chunks will not join the ones the target's"
                        + " server generates next to them.");
            }
            return;
        }
        Map<String, Object> targetMods = targetPrint.get("mods") instanceof Map<?, ?> mods ? (Map<String, Object>) mods : Map.of();
        TreeSet<String> ids = new TreeSet<>(sourceMods.keySet());
        ids.addAll(targetMods.keySet());
        List<String> changes = new ArrayList<>();
        for (String id : ids) {
            Object there = sourceMods.get(id);
            Object here = targetMods.get(id);
            if (there == null) changes.add(id + " " + here + " only in the target");
            else if (here == null) changes.add(id + " " + there + " only in the generated world");
            else if (!there.equals(here)) changes.add(id + " " + there + " in the generated world, " + here + " in the target");
        }
        if (!changes.isEmpty()) findings.differences.add("Mods differ: " + String.join("; ", changes) + ".");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fingerprint(Path world, Findings findings) {
        Path file = world.resolve("ufplink-fingerprint.json");
        if (!Files.isRegularFile(file)) return null;
        try {
            Object json = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (json instanceof Map<?, ?> map) return (Map<String, Object>) map;
        } catch (IOException failure) {
            findings.warnings.add(file + " could not be read: " + failure.getMessage());
        }
        return null;
    }

    /** At most what the merge adds (every region and entities file of the generated world), and a copy per thread. */
    private static void checkSpace(List<Dimension> dimensions, int threads, Layout target, Findings findings, boolean force) throws IOException {
        long needed = 0;
        long largest = 0;
        for (Dimension dimension : dimensions) {
            for (Path region : dimension.regions()) {
                needed += Files.size(region);
                Path entities = dimension.source().resolve("entities").resolve(region.getFileName());
                if (Files.isRegularFile(entities)) needed += Files.size(entities);
                Path existing = dimension.target().resolve("region").resolve(region.getFileName());
                if (Files.isRegularFile(existing)) largest = Math.max(largest, Files.size(existing));
            }
        }
        needed += threads * (largest + 16L * 1024 * 1024);
        long free = Files.getFileStore(target.world()).getUsableSpace();
        if (free < needed) {
            String line = String.format(Locale.ROOT, "The target's disk has %,d MB free; the merge may need up to %,d MB.",
                    free / (1024 * 1024), needed / (1024 * 1024));
            if (force) findings.warnings.add(line + " Merging anyway (--force): a region file that does not fit is left as it was.");
            else findings.refused.add(line + " Free some space, or add --force to try anyway (a region file that does not fit is left as it was).");
        }
    }

    private record Job(Dimension dimension, Path region) {}

    private static int merge(List<Dimension> dimensions, int targetDataVersion, boolean dryRun, int threads) throws IOException {
        Map<String, Counts> counts = new LinkedHashMap<>();
        long totalRegions = 0;
        for (Dimension dimension : dimensions) {
            counts.put(dimension.id(), new Counts());
            totalRegions += dimension.regions().size();
            System.out.println(String.format(Locale.ROOT, "%s: %,d region files, %s -> %s", dimension.id(), dimension.regions().size(),
                    dimension.source(), dimension.target()));
            if (!dryRun) removeLeftovers(dimension.target());
        }
        long startedAt = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "UFP-Link-Merge");
            thread.setDaemon(true);
            return thread;
        });
        Thread hook = new Thread(() -> {
            // Ctrl+C: finish the region files being merged, start no more.
            stopping = true;
            pool.shutdown();
            try {
                pool.awaitTermination(120, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "UFP-Link-Merge-Stop");
        Runtime.getRuntime().addShutdownHook(hook);
        List<Future<?>> futures = new ArrayList<>();
        for (Dimension dimension : dimensions) {
            Counts dimensionCounts = counts.get(dimension.id());
            for (Path region : dimension.regions()) {
                futures.add(pool.submit(() -> mergeRegion(new Job(dimension, region), targetDataVersion, dryRun, dimensionCounts)));
            }
        }
        long lastProgress = System.nanoTime();
        for (Future<?> future : futures) {
            while (true) {
                try {
                    future.get(1, TimeUnit.SECONDS);
                    break;
                } catch (TimeoutException waiting) {
                    if (System.nanoTime() - lastProgress > PROGRESS_NANOS) {
                        lastProgress = System.nanoTime();
                        System.out.println(progress(counts, totalRegions, dryRun, startedAt));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                } catch (ExecutionException failure) {
                    throw new IOException(failure.getCause());
                }
            }
        }
        pool.shutdown();
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException alreadyStopping) {
            // The JVM is shutting down: the hook runs.
        }

        double seconds = (System.nanoTime() - startedAt) / 1e9;
        System.out.println(String.format(Locale.ROOT, "%s in %.1f s:", dryRun ? "Checked" : "Merged", seconds));
        long failed = 0;
        long added = 0;
        for (Map.Entry<String, Counts> entry : counts.entrySet()) {
            Counts c = entry.getValue();
            failed += c.failed.get();
            added += c.added.get() + c.replaced.get();
            System.out.println(String.format(Locale.ROOT, "  %s: %,d chunks %s, %,d part-generated chunks %s, %,d kept (the target's own),"
                            + " %,d rejected; %,d with mobs; %,d points of interest %s%s%s",
                    entry.getKey(), c.added.get(), dryRun ? "to add" : "added", c.replaced.get(), dryRun ? "to replace" : "replaced",
                    c.kept.get(), c.rejected.get(), c.entities.get(), c.poi.get(), dryRun ? "to rebuild" : "left to rebuild",
                    c.unverified.get() > 0 ? String.format(Locale.ROOT, "; %,d LZ4 chunks copied unchecked", c.unverified.get()) : "",
                    c.failed.get() > 0 ? String.format(Locale.ROOT, "; %,d region files FAILED (left as they were)", c.failed.get()) : ""));
        }
        if (stopping) {
            System.out.println("Stopped before the end: region files done so far are complete, the others are as they were. Run it"
                    + " again to continue.");
            return 1;
        }
        if (failed > 0) {
            System.out.println("Some region files failed (see above) and were left as they were; running it again retries them.");
            return 1;
        }
        if (!dryRun && added > 0) {
            System.out.println("Done. Start the server: it relights the added chunks and rebuilds their points of interest (beds,"
                    + " job sites) as they first load.");
        }
        return 0;
    }

    private static String progress(Map<String, Counts> counts, long totalRegions, boolean dryRun, long startedAt) {
        long regions = 0;
        long added = 0;
        long replaced = 0;
        long kept = 0;
        for (Counts c : counts.values()) {
            regions += c.regions.get();
            added += c.added.get();
            replaced += c.replaced.get();
            kept += c.kept.get();
        }
        return String.format(Locale.ROOT, "  %,d/%,d region files, %,d chunks %s, %,d replaced, %,d kept, %d s",
                regions, totalRegions, added, dryRun ? "to add" : "added", replaced, kept,
                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt));
    }

    /** Copies an earlier run left when it was killed (ours only: *.ufplink-tmp). */
    private static void removeLeftovers(Path storage) throws IOException {
        for (String folder : List.of("region", "entities", "poi")) {
            Path dir = storage.resolve(folder);
            if (!Files.isDirectory(dir)) continue;
            List<Path> leftovers;
            try (Stream<Path> files = Files.list(dir)) {
                leftovers = files.filter(file -> file.getFileName().toString().endsWith(".ufplink-tmp")).toList();
            }
            for (Path leftover : leftovers) Files.deleteIfExists(leftover);
        }
    }

    /**
     * One region file: the generated world's chunks the target lacks are added, its fully generated
     * chunks replace part-generated ones of the target, and the rest of the target stays. Then the mobs
     * of those chunks, and the target's points of interest for them are removed so its server rebuilds
     * them from the blocks.
     */
    private static void mergeRegion(Job job, int targetDataVersion, boolean dryRun, Counts counts) {
        if (stopping) return;
        Dimension dimension = job.dimension();
        String name = job.region().getFileName().toString();
        try {
            // Chunks that are now the generated world's: written here, or by an earlier run that stopped before their mobs.
            List<int[]> merged = new ArrayList<>();
            Path targetFile = dimension.target().resolve("region").resolve(name);
            try (RegionReader source = RegionReader.open(job.region());
                 RegionWriter target = dryRun ? RegionWriter.inspect(targetFile) : RegionWriter.open(targetFile)) {
                for (int[] chunk : source.chunks()) {
                    int x = chunk[0];
                    int z = chunk[1];
                    ByteBuffer frame;
                    Map<String, Object> tag;
                    try {
                        frame = source.frame(x, z);
                        tag = Nbt.peekFrame(frame, CHUNK_TAGS);
                    } catch (IOException | RuntimeException failure) {
                        reject(dimension.id(), x, z, failure.toString(), counts);
                        continue;
                    }
                    String problem = tag == null ? null : checkChunk(tag, x, z, targetDataVersion);
                    if (problem != null) {
                        reject(dimension.id(), x, z, problem, counts);
                        continue;
                    }
                    boolean full = tag != null && isFull(tag);
                    if (target.has(x, z)) {
                        ByteBuffer existing;
                        try {
                            existing = target.frame(x, z);
                        } catch (IOException | RuntimeException unreadable) {
                            counts.kept.incrementAndGet();
                            continue;
                        }
                        if (frame.equals(existing)) {
                            counts.kept.incrementAndGet();
                            merged.add(chunk);
                            continue;
                        }
                        if (!full || storedFull(existing)) {
                            counts.kept.incrementAndGet();
                            continue;
                        }
                        counts.replaced.incrementAndGet();
                    } else {
                        counts.added.incrementAndGet();
                    }
                    if (tag == null) counts.unverified.incrementAndGet();
                    target.write(x, z, frame);
                    merged.add(chunk);
                }
                target.commit();
            }
            if (!merged.isEmpty()) {
                mergeEntities(dimension, name, merged, targetDataVersion, dryRun, counts);
                clearPoi(dimension, name, merged, dryRun, counts);
            }
        } catch (IOException | RuntimeException failure) {
            counts.failed.incrementAndGet();
            System.out.println("FAILED " + dimension.id() + " " + name + ": " + failure + " (left as it was)");
        } finally {
            counts.regions.incrementAndGet();
        }
    }

    private static String checkChunk(Map<String, Object> tag, int x, int z, int targetDataVersion) {
        long dataVersion = Nbt.number(tag, "DataVersion", -1);
        if (dataVersion <= 0) return "no data version";
        if (dataVersion > targetDataVersion) return "data version " + dataVersion + ", newer than the target's " + targetDataVersion;
        long xPos = Nbt.number(tag, "xPos", Long.MIN_VALUE);
        long zPos = Nbt.number(tag, "zPos", Long.MIN_VALUE);
        if (xPos != x || zPos != z) return "it says it is chunk [" + xPos + ", " + zPos + "]";
        if (Nbt.string(tag, "Status") == null) return "no generation status";
        if (!(tag.get("sections") instanceof Nbt.Skipped sections) || sections.type() != 9) return "no sections";
        return null;
    }

    private static boolean isFull(Map<String, Object> tag) {
        String status = Nbt.string(tag, "Status");
        return "minecraft:full".equals(status) || "full".equals(status);
    }

    /** Whether the target's chunk is fully generated; unreadable or LZ4 counts as fully generated (it stays). */
    private static boolean storedFull(ByteBuffer frame) {
        try {
            Map<String, Object> tag = Nbt.peekFrame(frame, Set.of("Status"));
            return tag == null || isFull(tag);
        } catch (IOException | RuntimeException failure) {
            return true;
        }
    }

    /** The mobs of merged chunks, from the generated world's entities folder, where the target has none for them. */
    private static void mergeEntities(Dimension dimension, String name, List<int[]> chunks, int targetDataVersion, boolean dryRun,
                                      Counts counts) throws IOException {
        Path sourceFile = dimension.source().resolve("entities").resolve(name);
        if (!Files.isRegularFile(sourceFile)) return;
        Path targetFile = dimension.target().resolve("entities").resolve(name);
        try (RegionReader source = RegionReader.open(sourceFile);
             RegionWriter target = dryRun ? RegionWriter.inspect(targetFile) : RegionWriter.open(targetFile)) {
            for (int[] chunk : chunks) {
                int x = chunk[0];
                int z = chunk[1];
                if (!source.has(x, z) || target.has(x, z)) continue;
                try {
                    ByteBuffer frame = source.frame(x, z);
                    Map<String, Object> tag = Nbt.peekFrame(frame, ENTITY_TAGS);
                    if (tag != null) {
                        long dataVersion = Nbt.number(tag, "DataVersion", -1);
                        if (dataVersion <= 0 || dataVersion > targetDataVersion) continue;
                        if (!(tag.get("Position") instanceof int[] position) || position.length != 2 || position[0] != x || position[1] != z) continue;
                        if (!(tag.get("Entities") instanceof Nbt.Skipped entities) || entities.type() != 9) continue;
                    }
                    target.write(x, z, frame);
                    counts.entities.incrementAndGet();
                } catch (IOException | RuntimeException failure) {
                    System.out.println("Mobs of " + dimension.id() + " chunk [" + x + ", " + z + "] not merged: " + failure);
                }
            }
            target.commit();
        }
    }

    /** Removes the target's points of interest of merged chunks: its server rebuilds them from the chunks' blocks. */
    private static void clearPoi(Dimension dimension, String name, List<int[]> chunks, boolean dryRun, Counts counts) throws IOException {
        Path file = dimension.target().resolve("poi").resolve(name);
        if (!Files.isRegularFile(file)) return;
        try (RegionWriter poi = dryRun ? RegionWriter.inspect(file) : RegionWriter.open(file)) {
            for (int[] chunk : chunks) {
                if (!poi.has(chunk[0], chunk[1])) continue;
                poi.remove(chunk[0], chunk[1]);
                counts.poi.incrementAndGet();
            }
            poi.commit();
        }
    }

    private static void reject(String dimension, int x, int z, String why, Counts counts) {
        counts.rejected.incrementAndGet();
        long rejected = REJECTIONS.incrementAndGet();
        if (rejected <= LOGGED_REJECTIONS) {
            System.out.println("Rejected " + dimension + " chunk [" + x + ", " + z + "]: " + why);
        } else if (rejected == LOGGED_REJECTIONS + 1) {
            System.out.println("More chunks were rejected; they are only counted from here on.");
        }
    }
}
