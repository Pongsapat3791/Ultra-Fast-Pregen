package io.github.pongsapat3791.ufplink;

import io.github.pongsapat3791.ufplink.tool.Json;
import io.github.pongsapat3791.ultrafastpregen.api.PregenApi;
import io.github.pongsapat3791.ultrafastpregen.api.PregenRequest;
import io.github.pongsapat3791.ultrafastpregen.api.PregenStatus;
import io.github.pongsapat3791.ultrafastpregen.api.PregenWorlds;
import io.github.pongsapat3791.ultrafastpregen.api.StartResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * /ufplink pregen: Ultra Fast Pregen generates an area in a separate world, then the chunks the target dimension does
 * not have are merged into it, and the separate world is deleted. Nothing is written to the target before the merge,
 * and the merge never replaces a chunk. A job cut short (server stop, /ufplink cancel) keeps its separate world and a
 * job file there; /ufplink pregen resume continues it, skipping what was already generated or merged.
 */
final class PregenJob {
    static final String JOB_FILE = "ufplink-job.json";
    /** The vanilla world border's largest position; generated chunks must stay inside it. */
    private static final long MAX_WORLD_COORDINATE = 29_999_984L;
    private static final AtomicReference<PregenJob> CURRENT = new AtomicReference<>();

    enum State { GENERATING, MERGING }

    private final String target;
    private final int worldK;
    private final int centerX;
    private final int centerZ;
    /** Who asked; null when the API did. */
    private final PregenWorlds.CommandReply requester;
    private final String wanted;
    private final Path folder;
    /** The separate world's id while it is open. */
    private volatile String separate;
    private volatile State state = State.GENERATING;
    private volatile boolean generating;
    private volatile String stopReason;

    private PregenJob(String target, int worldK, int centerX, int centerZ, PregenWorlds.CommandReply requester) {
        this.target = target;
        this.worldK = worldK;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.requester = requester;
        this.wanted = SeparateWorld.idFor(target);
        this.folder = UfpLink.worlds().separateWorldFolder(target, wanted);
    }

    static PregenJob current() {
        return CURRENT.get();
    }

    /** Whether a worldK x worldK area centered on (x, z) stays inside the world (with the structure ring around it). */
    static boolean fitsWorld(int worldK, int x, int z) {
        long reach = worldK * 500L + (UfpLink.worlds().neighborChunks() + 1) * 16L;
        return Math.abs((long) x) + reach <= MAX_WORLD_COORDINATE && Math.abs((long) z) + reach <= MAX_WORLD_COORDINATE;
    }

    /** Starts a pregen of the worldK x worldK block area centered on (x, z) in the target; an error or null. */
    static String start(String target, int worldK, int x, int z, PregenWorlds.CommandReply requester) {
        String problem = refusal(target);
        if (problem != null) return problem;
        if (!fitsWorld(worldK, x, z)) {
            return String.format(Locale.ROOT, "A %dk area centered on [%d, %d] reaches past the world limit.", worldK, x, z);
        }
        PregenJob job = new PregenJob(target, worldK, x, z, requester);
        if (Files.isRegularFile(job.folder.resolve(JOB_FILE))) {
            return "A pregen of " + target + " was cut short before; continue it with /ufplink pregen resume"
                    + " (or delete " + job.folder + " to start over).";
        }
        if (!CURRENT.compareAndSet(null, job)) return "A pregen is running: /ufplink status, /ufplink cancel.";
        job.generate();
        return null;
    }

    /** Continues the pregen a stop cut short (its job file says what and how far); an error or null. */
    @SuppressWarnings("unchecked")
    static String resume(PregenWorlds.CommandReply requester) {
        Path jobFile = unfinishedJobFile();
        if (jobFile == null) return "No pregen was cut short in this world.";
        PregenJob job;
        State state;
        try {
            Map<String, Object> json = (Map<String, Object>) Json.parse(Files.readString(jobFile, StandardCharsets.UTF_8));
            String target = (String) json.get("target");
            if (!UfpLink.worlds().dimensions().contains(target)) return jobFile + " is for " + target + ", which this server does not have.";
            String problem = refusal(target);
            if (problem != null) return problem;
            job = new PregenJob(target, ((Number) json.get("world_k")).intValue(), ((Number) json.get("x")).intValue(),
                    ((Number) json.get("z")).intValue(), requester);
            state = State.valueOf((String) json.get("state"));
        } catch (IOException | RuntimeException failure) {
            return "Could not read " + jobFile + ": " + failure;
        }
        if (!CURRENT.compareAndSet(null, job)) return "A pregen is running: /ufplink status, /ufplink cancel.";
        if (state == State.MERGING) job.merge();
        else job.generate();
        return null;
    }

    private static String refusal(String target) {
        if (CURRENT.get() != null) return "A pregen is running: /ufplink status, /ufplink cancel.";
        if (ImportTask.current() != null) return "An import is running: /ufplink status, /ufplink cancel.";
        // A host's own worker (local_worker) generates its tiles in the same separate world.
        if (HostJob.current() != null) return "A distributed pregen is running: /ufplink host status, /ufplink cancel.";
        if (SeparateWorld.isOne(target)) return "That is a pregen's separate world.";
        return null;
    }

    /** The job file of a pregen this world has not finished, or null. */
    static Path unfinishedJobFile() {
        PregenWorlds worlds = UfpLink.worlds();
        return worlds.dimensions().stream().filter(dimension -> !SeparateWorld.isOne(dimension)).sorted()
                .map(dimension -> worlds.separateWorldFolder(dimension, SeparateWorld.idFor(dimension)).resolve(JOB_FILE))
                .filter(Files::isRegularFile).findFirst().orElse(null);
    }

    /** Opens the separate world and starts the run in it. */
    private void generate() {
        PregenWorlds worlds = UfpLink.worlds();
        PregenApi pregen = UfpLink.pregen();
        try {
            state = State.GENERATING;
            writeJobFile();
            separate = worlds.openSeparateWorld(target, wanted);
            StartResult started = pregen.start(PregenRequest.square(separate, centerX, centerZ, worldK * 500).keepingExistingChunks());
            if (!started.started()) {
                closeSeparate();
                finish("Could not start the pregen: " + started.message(), true);
                return;
            }
            generating = true;
        } catch (IOException | RuntimeException failure) {
            UfpLink.LOGGER.error("[UFP Link] Could not start the pregen", failure);
            closeSeparate();
            finish("Could not start the pregen: " + failure, true);
            return;
        }
        tell(String.format(Locale.ROOT, "Generating a %dk x %dk area around [%d, %d] for %s in a separate world (%s);"
                + " it is merged into %s when it is done.", worldK, worldK, centerX, centerZ, target, wanted, target));
        Thread watcher = new Thread(this::watch, "UFP-Link-Pregen");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void watch() {
        PregenApi pregen = UfpLink.pregen();
        try {
            while (pregen.isRunning()) Thread.sleep(500);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        generating = false;
        PregenStatus status = pregen.status().orElse(null);
        boolean completed = status != null && status.state() == PregenStatus.State.COMPLETED;
        if (!UfpLink.worlds().serverRunning()) {
            // The server saves and closes the separate world with its others; resume continues.
            finish("The server stopped during the pregen; /ufplink pregen resume continues it.", true);
            return;
        }
        closeSeparate();
        if (completed && stopReason == null) {
            merge();
        } else {
            finish(String.format(Locale.ROOT, "The pregen stopped (%s) at %.1f%%; /ufplink pregen resume continues it.",
                    stopReason != null ? stopReason : status != null ? status.stage() : "no status",
                    status != null ? status.progress() * 100 : 0), true);
        }
    }

    private void closeSeparate() {
        String id = separate;
        if (id == null) return;
        separate = null;
        try {
            UfpLink.worlds().closeSeparateWorld(id);
        } catch (IOException | RuntimeException failure) {
            UfpLink.LOGGER.warn("[UFP Link] Closing the separate world {} failed", id, failure);
        }
    }

    /** Merges the separate world's chunks into the target, then deletes it. */
    private void merge() {
        state = State.MERGING;
        try {
            writeJobFile();
        } catch (IOException failure) {
            UfpLink.LOGGER.warn("[UFP Link] Could not write the job file", failure);
        }
        ImportTask task = ImportTask.startMerge(List.of(new ImportTask.Source(wanted, folder, target)), requester, complete -> {
            if (complete) {
                deleteFolder();
                finish("Pregen of " + target + " done: the separate world was merged and deleted.", false);
            } else {
                finish("The merge stopped; /ufplink pregen resume continues it.", true);
            }
        });
        if (task == null) finish("Another import is running; /ufplink pregen resume merges the pregen later.", true);
    }

    /** Stops after the batch or region file in progress; resume continues. */
    void cancel(String reason) {
        if (stopReason == null) stopReason = reason;
        if (state == State.GENERATING && generating) UfpLink.pregen().stop();
        ImportTask merging = ImportTask.current();
        if (state == State.MERGING && merging != null) merging.cancel(reason);
    }

    String describe() {
        if (state == State.MERGING) {
            ImportTask merging = ImportTask.current();
            return merging != null ? merging.describe() : "Merging the separate world into " + target + ".";
        }
        PregenStatus status = generating ? UfpLink.pregen().status().orElse(null) : null;
        if (status == null) return "Starting the pregen of " + target + ".";
        return String.format(Locale.ROOT, "Generating %dk x %dk around [%d, %d] for %s in %s: %.1f%% (%,d/%,d chunks) | %.1f chunks/s"
                        + " | ETA %s | %s", worldK, worldK, centerX, centerZ, target, wanted, status.progress() * 100,
                status.doneChunks(), status.totalChunks(), status.chunksPerSecond(),
                status.etaMillis() < 0 ? "unknown" : status.etaMillis() / 1000 + " s", status.stage());
    }

    private void writeJobFile() throws IOException {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(JOB_FILE), String.format(Locale.ROOT,
                "{\"about\": \"A pregen of %s that is not finished; /ufplink pregen resume continues it.\", \"target\": \"%s\","
                        + " \"world_k\": %d, \"x\": %d, \"z\": %d, \"state\": \"%s\"}\n",
                UfpLink.NAME, target, worldK, centerX, centerZ, state.name()), StandardCharsets.UTF_8);
    }

    private void deleteFolder() {
        try {
            SeparateWorld.delete(folder);
        } catch (IOException failure) {
            UfpLink.LOGGER.warn("[UFP Link] Could not delete {}; delete it by hand", folder, failure);
        }
    }

    private void finish(String message, boolean problem) {
        CURRENT.compareAndSet(this, null);
        if (problem) UfpLink.LOGGER.warn("[UFP Link] {}", message);
        else UfpLink.LOGGER.info("[UFP Link] {}", message);
        if (requester == null || !UfpLink.worlds().serverRunning()) return;
        if (problem) requester.fail("[UFP Link] " + message);
        else requester.tell("[UFP Link] " + message, true);
    }

    private void tell(String message) {
        UfpLink.LOGGER.info("[UFP Link] {}", message);
        if (requester != null) requester.tell("[UFP Link] " + message, true);
    }
}
