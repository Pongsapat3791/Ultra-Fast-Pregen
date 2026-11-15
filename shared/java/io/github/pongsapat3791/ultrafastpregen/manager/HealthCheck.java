package io.github.pongsapat3791.ultrafastpregen.manager;

import io.github.pongsapat3791.ultrafastpregen.manager.WebServer.Request;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * What could go wrong with a run, as checks that say pass, warn or fail and what to do about it: before a run (disk,
 * memory, the world border, chunks that exist, settings), during one (no progress, speed, memory, disk) and for a
 * Link network (silent or slow machines, rejected chunks, refused machines). Everything here only reads: the
 * server's numbers, region file headers, level.dat. A thread of its own looks every few seconds, never the server's.
 */
final class HealthCheck {
    private static final long SAMPLE_MILLIS = 5_000;
    /** Fifteen minutes of samples. */
    private static final int KEEP_SAMPLES = 180;
    private static final long STALL_WARN = 5 * 60_000L;
    private static final long STALL_FAIL = 15 * 60_000L;
    private static final long SPEED_WINDOW = 10 * 60_000L;
    private static final long SILENT_MACHINE = 5 * 60_000L;
    private static final long RECENT_REFUSAL = 15 * 60_000L;
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int MAX_REGION_FILES = 20_000;

    /** One look at a run: what tells that it moves. */
    private record Sample(long time, long done, double progress, String stage) {}

    /** A Link machine's count of chunks, and when it last grew. */
    private static final class Heard {
        long chunks = -1;
        long since;
    }

    private final RegionMap map;
    private final UfpSettings settings;
    private final PregenBridge pregen;
    private final LinkBridge link;
    private final Path gameDirectory;
    private final Deque<Sample> samples = new ArrayDeque<>();
    private final Map<String, Heard> machines = new HashMap<>();
    private String runKey = "";
    private volatile List<Map<String, Object>> live = List.of();
    private volatile boolean closed;

    HealthCheck(RegionMap map, UfpSettings settings, PregenBridge pregen, LinkBridge link, Path gameDirectory) {
        this.map = map;
        this.settings = settings;
        this.pregen = pregen;
        this.link = link;
        this.gameDirectory = gameDirectory;
        Thread thread = new Thread(this::watch, "UFP-Manager-Health");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    void close() {
        closed = true;
    }

    private void watch() {
        while (!closed) {
            try {
                live = look();
            } catch (RuntimeException failure) {
                // a check that could not be made says nothing; the next look tries again
            }
            try {
                Thread.sleep(SAMPLE_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** The worst of the checks of a run and of Link, for the page's tab: ok, warn or fail. */
    String level() {
        return worst(live);
    }

    /** All checks: those of the area the request names (when no run is going on) and the live ones. */
    Map<String, Object> report(Request request) {
        List<Map<String, Object>> checks = new ArrayList<>();
        Map<String, Object> status = status();
        boolean running = status != null && "RUNNING".equals(status.get("state"));
        String dimension = request.field("dimension");
        if (!running && dimension != null && RegionMap.validDimension(dimension)) {
            try {
                checks.addAll(before(dimension, whole(request.field("x"), 0), whole(request.field("z"), 0),
                        Math.max(1, whole(request.field("radius"), 500)), "circle".equals(request.field("shape"))));
            } catch (IOException | RuntimeException failure) {
                checks.add(check("before", "before", "info", "The area could not be checked",
                        String.valueOf(failure.getMessage()), "Try again; the server's log may say more."));
            }
        }
        checks.addAll(live);
        return Json.object("status", worst(checks), "running", running, "checks", checks, "when", System.currentTimeMillis());
    }

    // ---- before a run ------------------------------------------------------------------------------------------

    private List<Map<String, Object>> before(String dimension, int x, int z, int radius, boolean circle) throws IOException {
        List<Map<String, Object>> checks = new ArrayList<>();
        int minChunkX = Math.floorDiv(x - radius, 16);
        int maxChunkX = Math.floorDiv(x + radius - 1, 16);
        int minChunkZ = Math.floorDiv(z - radius, 16);
        int maxChunkZ = Math.floorDiv(z + radius - 1, 16);
        long chunks = circle ? Math.round(Math.PI * radius * (double) radius / 256)
                : (long) (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);

        // Chunks that exist in the area, and what a chunk of this dimension takes on disk here.
        Path folder = map.regionFolder(dimension);
        long existing = 0;
        long storedChunks = 0;
        long storedBytes = 0;
        boolean counted = true;
        if (folder != null) {
            List<Path> files = new ArrayList<>();
            try (Stream<Path> listed = Files.list(folder)) {
                listed.limit(MAX_REGION_FILES + 1L).forEach(files::add);
            }
            counted = files.size() <= MAX_REGION_FILES;
            for (Path file : counted ? files : List.<Path>of()) {
                Matcher name = REGION_FILE.matcher(file.getFileName().toString());
                if (!name.matches()) continue;
                int regionX = Integer.parseInt(name.group(1));
                int regionZ = Integer.parseInt(name.group(2));
                if (regionX * 32 + 31 < minChunkX || regionX * 32 > maxChunkX || regionZ * 32 + 31 < minChunkZ || regionZ * 32 > maxChunkZ) {
                    continue;
                }
                byte[] bits = map.chunks(dimension, regionX, regionZ);
                int inFile = 0;
                for (int index = 0; index < 1024; index++) {
                    if ((bits[index >> 3] & (1 << (index & 7))) == 0) continue;
                    inFile++;
                    int chunkX = regionX * 32 + (index & 31);
                    int chunkZ = regionZ * 32 + (index >> 5);
                    if (chunkX < minChunkX || chunkX > maxChunkX || chunkZ < minChunkZ || chunkZ > maxChunkZ) continue;
                    if (circle) {
                        double dx = chunkX * 16 + 8 - x;
                        double dz = chunkZ * 16 + 8 - z;
                        if (dx * dx + dz * dz > (double) radius * radius) continue;
                    }
                    existing++;
                }
                storedChunks += inFile;
                storedBytes += Files.size(file);
            }
        }
        existing = Math.min(existing, chunks);
        long perChunk = storedChunks >= 500 ? Math.max(512, storedBytes / storedChunks)
                : dimension.equals("minecraft:the_end") ? 1_500 : 9_000;
        Map<String, String> values = settings.values();
        boolean keep = !"false".equals(values.get("skip_existing_chunks"));
        long toWrite = keep ? chunks - existing : chunks;

        // Disk.
        long needed = toWrite * perChunk * 11 / 10;
        long free = freeBytes(folder);
        if (free >= 0) {
            String detail = String.format(Locale.ROOT, "About %s for %,d chunks (%,d bytes a chunk%s); %s free.", size(needed), toWrite,
                    perChunk, storedChunks >= 500 ? ", as the chunks stored here" : ", a usual size", size(free));
            if (free < needed) {
                checks.add(check("disk", "before", "fail", "Not enough disk space for this area", detail,
                        "Free " + size(needed - free) + " more on the world's disk, or choose a smaller area."));
            } else if (free < needed * 3 / 2) {
                checks.add(check("disk", "before", "warn", "Disk space is just enough", detail,
                        "The estimate can be off by a third; free more space or watch the run."));
            } else {
                checks.add(check("disk", "before", "ok", "Disk space", detail, null));
            }
        }

        // Memory.
        long heap = Runtime.getRuntime().maxMemory() >> 20;
        String batch = values.getOrDefault("batch_size", "1000");
        if (heap < 1_900) {
            checks.add(check("heap", "before", "fail", "Too little memory", heap + " MB for the server; the mod is tested from 2 GB.",
                    "Give the server at least 2 GB (-Xmx2G), better 4 GB or more."));
        } else if (heap < 3_900) {
            checks.add(check("heap", "before", "warn", "Little memory: the run will be slower",
                    heap + " MB for the server. Batches of " + batch + " blocks may be cut down and run one at a time.",
                    "Give the server 4 GB or more (-Xmx4G) for full-size batches."));
        } else {
            checks.add(check("heap", "before", "ok", "Memory", heap + " MB for the server, batches of " + batch + " blocks.", null));
        }

        // Chunks that exist already.
        if (!counted) {
            checks.add(check("existing", "before", "info", "Chunks that exist were not counted",
                    "The dimension has more than " + MAX_REGION_FILES + " region files.", null));
        } else if (existing > 0 && !keep) {
            checks.add(check("existing", "before", "warn", String.format(Locale.ROOT, "%,d chunks of this area exist and will be replaced", existing),
                    "skip_existing_chunks is false: every chunk of the area is generated again. Whatever players built, placed or took there is lost.",
                    "Turn \"Keep chunks that exist\" on in the Server tab unless you mean to replace them, and keep a copy of the world."));
        } else {
            long percent = chunks == 0 ? 0 : Math.round(existing * 100.0 / chunks);
            checks.add(check("existing", "before", "ok", "Chunks that exist already",
                    String.format(Locale.ROOT, "%,d of %,d (%d%%) exist and are kept; %,d will be generated.", existing, chunks, percent, toWrite), null));
        }

        // The world border.
        double[] border = worldBorder(dimension);
        if (border != null && border[2] > 0 && border[2] < 5.9e7) {
            double half = border[2] / 2;
            boolean outside = x - radius < border[0] - half || x + radius > border[0] + half
                    || z - radius < border[1] - half || z + radius > border[1] + half;
            String detail = String.format(Locale.ROOT, "The border is %,.0f blocks wide around %,.0f, %,.0f.", border[2], border[0], border[1]);
            if (outside) {
                checks.add(check("border", "before", "warn", "The area reaches beyond the world border", detail,
                        "Players cannot go there. /pregen worldborder takes the border's center and size."));
            } else {
                checks.add(check("border", "before", "ok", "Inside the world border", detail, null));
            }
        }

        // Settings that change what a run gives.
        if ("always".equals(values.get("parallel_features"))) {
            checks.add(check("features", "before", "warn", "parallel_features is always",
                    "Features run on all threads even when another mod keeps state while it places them. In packs with such mods"
                            + " (BCLib) the world then is not the pack's own: their features land elsewhere and some come out rarer.",
                    "Set \"Features on all threads\" to Auto in the Server tab unless you tested this pack with it."));
        }
        if ("vanilla".equals(values.get("terrain_pipeline"))) {
            checks.add(check("terrain", "before", "info", "The terrain path is Minecraft's own",
                    "terrain_pipeline is vanilla: the same world, slower.", "Auto picks the fast path where it is safe."));
        }
        return checks;
    }

    private long freeBytes(Path folder) {
        Path place = folder;
        while (place != null && !Files.exists(place)) place = place.getParent();
        if (place == null) place = gameDirectory;
        try {
            return Files.getFileStore(place).getUsableSpace();
        } catch (IOException | RuntimeException unknown) {
            return -1;
        }
    }

    /**
     * The dimension's border as center x, center z, size; null when no file says. Minecraft 26.x keeps a border for
     * each dimension in the dimension's own data folder; before that level.dat holds one, in Overworld coordinates.
     */
    private double[] worldBorder(String dimension) {
        Path regions = map.regionFolder(dimension);
        if (regions != null && regions.getParent() != null) {
            Map<String, Double> own = numbers(regions.getParent().resolve("data").resolve("minecraft").resolve("world_border.dat"));
            if (own.containsKey("center_x") && own.containsKey("center_z") && own.containsKey("size")) {
                return new double[] {own.get("center_x"), own.get("center_z"), own.get("size")};
            }
        }
        String world = map.worldName();
        if (world == null || !dimension.equals("minecraft:overworld")) return null;
        Map<String, Double> level = numbers(gameDirectory.resolve(world).resolve("level.dat"));
        Double x = level.get("BorderCenterX");
        Double z = level.get("BorderCenterZ");
        Double size = level.get("BorderSize");
        return x == null || z == null || size == null ? null : new double[] {x, z, size};
    }

    /** Every number of type double in a compressed NBT file, by its name; empty when the file is not there or not readable. */
    private static Map<String, Double> numbers(Path file) {
        Map<String, Double> found = new HashMap<>();
        if (!Files.isRegularFile(file)) return found;
        try (InputStream in = Files.newInputStream(file); DataInputStream data = new DataInputStream(new GZIPInputStream(in, 1 << 16))) {
            if (data.readUnsignedByte() != 10) return found;
            data.readUTF();
            compound(data, found, 0);
        } catch (IOException | RuntimeException unreadable) {
            found.clear();
        }
        return found;
    }

    /** Reads a compound to its end, keeping its numbers of type double. */
    private static void compound(DataInputStream data, Map<String, Double> found, int depth) throws IOException {
        if (depth > 64) throw new IOException("too deep");
        while (true) {
            int type = data.readUnsignedByte();
            if (type == 0) return;
            String name = data.readUTF();
            if (type == 6) {
                found.put(name, data.readDouble());
            } else {
                skip(data, type, found, depth);
            }
        }
    }

    private static void skip(DataInputStream data, int type, Map<String, Double> found, int depth) throws IOException {
        switch (type) {
            case 1 -> data.skipNBytes(1);
            case 2 -> data.skipNBytes(2);
            case 3, 5 -> data.skipNBytes(4);
            case 4, 6 -> data.skipNBytes(8);
            case 7 -> data.skipNBytes(data.readInt());
            case 8 -> data.skipNBytes(data.readUnsignedShort());
            case 9 -> {
                int inner = data.readUnsignedByte();
                int count = data.readInt();
                for (int i = 0; i < count; i++) {
                    if (inner == 10) compound(data, found, depth + 1);
                    else skip(data, inner, found, depth + 1);
                }
            }
            case 10 -> compound(data, found, depth + 1);
            case 11 -> data.skipNBytes(4L * data.readInt());
            case 12 -> data.skipNBytes(8L * data.readInt());
            default -> throw new IOException("unknown tag " + type);
        }
    }

    // ---- during a run, and Link --------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> status() {
        Object status = pregen.poll(-1).get("status");
        return status instanceof Map<?, ?> ? (Map<String, Object>) status : null;
    }

    /** One look: the checks of a run that is going on (or just failed) and of the Link network. */
    private synchronized List<Map<String, Object>> look() {
        List<Map<String, Object>> checks = new ArrayList<>();
        long now = System.currentTimeMillis();
        Map<String, Object> status = status();
        if (status != null && "RUNNING".equals(status.get("state"))) {
            run(checks, status, now);
        } else {
            samples.clear();
            runKey = "";
            if (status != null && "FAILED".equals(status.get("state"))) {
                checks.add(check("failed", "run", "fail", "The last run failed", "It stopped at " + number(status, "done") + " of "
                        + number(status, "total") + " chunks: " + status.get("stage") + ".",
                        "The server's log has the cause. /pregen resume (or Resume on the Pregen tab) continues from what was saved."));
            }
        }
        link(checks, now);
        return List.copyOf(checks);
    }

    private void run(List<Map<String, Object>> checks, Map<String, Object> status, long now) {
        long done = number(status, "done");
        long total = number(status, "total");
        double progress = status.get("progress") instanceof Number value ? value.doubleValue() : 0;
        String stage = String.valueOf(status.get("stage"));
        String key = status.get("dimension") + "," + status.get("x") + "," + status.get("z") + "," + status.get("radius") + "," + status.get("shape");
        if (!key.equals(runKey) || (!samples.isEmpty() && done < samples.peekLast().done())) {
            samples.clear();
            runKey = key;
        }
        samples.addLast(new Sample(now, done, progress, stage));
        while (samples.size() > KEEP_SAMPLES) samples.removeFirst();

        // Does it move? A chunk count, a progress value or a stage text that changes is movement.
        long moved = samples.peekFirst().time();
        Sample before = null;
        for (Sample sample : samples) {
            if (before != null && (sample.done() != before.done() || sample.progress() != before.progress() || !sample.stage().equals(before.stage()))) {
                moved = sample.time();
            }
            before = sample;
        }
        long still = now - moved;
        if (still >= STALL_WARN) {
            checks.add(check("stall", "run", still >= STALL_FAIL ? "fail" : "warn",
                    "No progress for " + (still / 60_000) + " minutes", "The run stands at " + done + " of " + total + " chunks, stage: " + stage + ".",
                    "Look at the server's log and its CPU use. If it hangs: /pregen stop, then /pregen resume continues from what was saved;"
                            + " a server restart continues by itself."));
        } else {
            checks.add(check("stall", "run", "ok", "The run is moving", done + " of " + total + " chunks, stage: " + stage + ".", null));
        }

        // Speed of the last ten minutes against the whole run.
        double average = status.get("chunksPerSecond") instanceof Number value ? value.doubleValue() : 0;
        Sample first = samples.peekFirst();
        long span = now - first.time();
        long steps = samples.stream().map(Sample::done).distinct().count();
        if (span >= SPEED_WINDOW - SAMPLE_MILLIS && steps >= 3 && average > 0) {
            double recent = (done - first.done()) * 1000.0 / span;
            if (recent < average * 0.5) {
                checks.add(check("speed", "run", "warn", "The run has slowed down",
                        String.format(Locale.ROOT, "%.0f chunks/s over the last %d minutes, %.0f over the whole run.", recent, span / 60_000, average),
                        "Something else may be using the CPU or the disk (players, a backup, another server). Lower \"Threads\" if players are online."));
            } else {
                checks.add(check("speed", "run", "ok", "Speed",
                        String.format(Locale.ROOT, "%.0f chunks/s over the last %d minutes, %.0f over the whole run.", recent, span / 60_000, average), null));
            }
        }

        // Memory that stays in use after a collection.
        long max = Runtime.getRuntime().maxMemory();
        long kept = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = pool.getType() == MemoryType.HEAP ? pool.getCollectionUsage() : null;
            if (usage != null) kept += usage.getUsed();
        }
        if (max > 0 && kept > 0) {
            long percent = kept * 100 / max;
            String detail = (kept >> 20) + " MB of " + (max >> 20) + " MB stay in use after a collection (" + percent + "%).";
            if (percent >= 92) {
                checks.add(check("heap-run", "run", "warn", "Memory is tight", detail,
                        "The run may cut its batches down or run out of memory. Give the server more memory, or lower \"Batch size\" for the next run."));
            } else {
                checks.add(check("heap-run", "run", "ok", "Memory during the run", detail, null));
            }
        }

        // Will the disk last to the end?
        String dimension = String.valueOf(status.get("dimension"));
        Path folder = RegionMap.validDimension(dimension) ? map.regionFolder(dimension) : null;
        long free = freeBytes(folder);
        long left = Math.max(0, total - done - number(status, "kept"));
        long needed = left * (dimension.equals("minecraft:the_end") ? 1_500 : 9_000) * 11 / 10;
        if (free >= 0 && left > 0) {
            String detail = String.format(Locale.ROOT, "%,d chunks to go, about %s; %s free.", left, size(needed), size(free));
            if (free < needed) {
                checks.add(check("disk-run", "run", "fail", "The disk will be full before the run ends", detail,
                        "Free space now, or stop the run (it can be resumed) before the disk is full."));
            } else {
                checks.add(check("disk-run", "run", "ok", "Disk space for the rest of the run", detail, null));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void link(List<Map<String, Object>> checks, long now) {
        Map<String, Object> state = link.poll();
        String role = String.valueOf(state.get("role"));
        if (role.equals("none")) {
            machines.clear();
            return;
        }
        Map<String, Object> run = state.get("run") instanceof Map<?, ?> ? (Map<String, Object>) state.get("run") : null;
        boolean running = run != null && Boolean.TRUE.equals(run.get("running"));
        List<Object> listed = state.get("machines") instanceof List<?> ? (List<Object>) state.get("machines") : List.of();
        if (role.equals("host")) {
            if (running && listed.isEmpty()) {
                checks.add(check("link-machines", "link", "fail", "A distributed run is going on and no machine is connected",
                        "Tiles wait until a machine joins.", "Start a client and run the host's line there: /ufplink set client <invite>."));
            }
            List<Double> speeds = new ArrayList<>();
            for (Object entry : listed) {
                Map<String, Object> machine = (Map<String, Object>) entry;
                if (Boolean.TRUE.equals(machine.get("busy")) && machine.get("chunksPerSecond") instanceof Number speed && speed.doubleValue() > 0) {
                    speeds.add(speed.doubleValue());
                }
            }
            speeds.sort(null);
            double median = speeds.isEmpty() ? 0 : speeds.get(speeds.size() / 2);
            List<String> seen = new ArrayList<>();
            for (Object entry : listed) {
                Map<String, Object> machine = (Map<String, Object>) entry;
                String name = String.valueOf(machine.get("name"));
                seen.add(name);
                Heard heard = machines.computeIfAbsent(name, key -> new Heard());
                long chunks = number(machine, "chunks");
                boolean busy = Boolean.TRUE.equals(machine.get("busy"));
                if (chunks != heard.chunks || !busy) {
                    heard.chunks = chunks;
                    heard.since = now;
                }
                if (busy && now - heard.since >= SILENT_MACHINE) {
                    checks.add(check("link-silent-" + name, "link", "warn", "Machine " + name + " has sent nothing for " + ((now - heard.since) / 60_000) + " minutes",
                            "It holds a tile and no chunk of it has arrived.",
                            "Look at that server's log. If it hangs, stop it: its tile goes back to the others."));
                } else if (busy && speeds.size() >= 2 && machine.get("chunksPerSecond") instanceof Number speed && speed.doubleValue() > 0
                        && speed.doubleValue() < median * 0.4) {
                    checks.add(check("link-slow-" + name, "link", "warn", "Machine " + name + " is much slower than the others",
                            String.format(Locale.ROOT, "%.0f chunks/s; the middle one of the busy machines makes %.0f.", speed.doubleValue(), median),
                            "Give it fewer tiles in the plan, or look at what else runs on it."));
                }
            }
            machines.keySet().retainAll(seen);
            if (run != null && number(run, "rejected") > 0) {
                checks.add(check("link-rejected", "link", "warn", number(run, "rejected") + " chunks from clients were not taken",
                        "A chunk a client sent did not pass the host's check (another Minecraft version, a damaged chunk).",
                        "The host's log names each one. The tile still counts as done: generate the area again with \"Keep chunks that exist\" on to fill the holes."));
            }
            List<Object> refusals = state.get("refusals") instanceof List<?> ? (List<Object>) state.get("refusals") : List.of();
            int shown = 0;
            for (Object entry : refusals) {
                Map<String, Object> refusal = (Map<String, Object>) entry;
                if (now - number(refusal, "when") > RECENT_REFUSAL || shown++ >= 3) continue;
                checks.add(check("link-refused-" + refusal.get("name"), "link", "warn", "Machine " + refusal.get("name") + " was refused",
                        String.valueOf(refusal.get("reason")), "A client must have the host's Minecraft version, mods and world settings; the reason names what differs."));
            }
            if (checks.stream().noneMatch(check -> "link".equals(check.get("set")))) {
                checks.add(check("link", "link", "ok", "Link network", listed.size() + (listed.size() == 1 ? " machine" : " machines") + " connected"
                        + (running ? ", a distributed run is going on." : "."), null));
            }
        } else if (role.equals("client") && state.get("client") == null) {
            checks.add(check("link-client", "link", "warn", "This client has no host", "It is set to be a client and is not connected.",
                    "Run the host's line again (/ufplink set client <invite>), or /ufplink set none."));
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static Map<String, Object> check(String id, String set, String level, String title, String detail, String fix) {
        return Json.object("id", id, "set", set, "level", level, "title", title, "detail", detail, "fix", fix);
    }

    private static String worst(List<Map<String, Object>> checks) {
        String worst = "ok";
        for (Map<String, Object> check : checks) {
            Object level = check.get("level");
            if ("fail".equals(level)) return "fail";
            if ("warn".equals(level)) worst = "warn";
        }
        return worst;
    }

    private static long number(Map<String, Object> map, String name) {
        return map.get(name) instanceof Number value ? value.longValue() : 0;
    }

    private static int whole(String text, int fallback) {
        if (text == null) return fallback;
        try {
            return (int) Math.max(-30_000_000, Math.min(30_000_000, Math.round(Double.parseDouble(text.trim()))));
        } catch (NumberFormatException wrong) {
            return fallback;
        }
    }

    private static String size(long bytes) {
        if (bytes >= 1L << 30) return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
        return String.format(Locale.ROOT, "%.0f MB", Math.max(1, bytes / (double) (1L << 20)));
    }
}
