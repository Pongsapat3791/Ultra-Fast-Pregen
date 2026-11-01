package io.github.pongsapat3791.ufplink;

import io.github.pongsapat3791.ufplink.region.RegionReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import io.github.pongsapat3791.ultrafastpregen.api.PregenWorlds;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * One import (or, as a check, a count) of the chunks another server generated. For each dimension
 * both worlds have, every region file of the source is read without changing it; each chunk is
 * checked (data version, position), and the chunks this server lacks are written through its IO
 * workers together with their mobs. Chunks the server holds, has on disk or has queued to save are
 * left as they are, so an import can be repeated, or stopped at any point and started again.
 */
final class ImportTask {
    private static final AtomicReference<ImportTask> CURRENT = new AtomicReference<>();
    private static final long PROGRESS_NANOS = TimeUnit.SECONDS.toNanos(5);
    /** Rejected chunks named in the log one by one; the rest are only counted. */
    private static final int LOGGED_REJECTIONS = 10;

    private record DimensionPlan(String id, String dimension, Path folder, List<Path> regions) {}

    /** A dimension folder to merge into a level of this server, without a fingerprint (see startMerge). */
    record Source(String label, Path folder, String target) {}

    /** The world folder of an import, or the first folder of a merge (for messages). */
    private final Path source;
    /** A merge's folders; null for an import from another world (which compares fingerprints first). */
    private final List<Source> merge;
    /** Called with whether every region file was done, after the run ends; may be null. */
    private final Consumer<Boolean> onFinished;
    private final boolean check;
    private final boolean force;
    /** Who asked; null when the API did. */
    private final PregenWorlds.CommandReply requester;
    private final long startedAt = System.nanoTime();
    private volatile String stopReason;
    private volatile String stage = "comparing the fingerprints";
    private volatile long regionsTotal;
    private volatile long regionsDone;
    private volatile long scanned;
    private volatile long imported;
    private volatile long kept;
    private volatile long held;
    private volatile long rejected;
    private volatile long entities;
    private volatile long replacedPartial;
    private long lastProgressAt = System.nanoTime();

    private ImportTask(Path source, List<Source> merge, boolean check, boolean force, PregenWorlds.CommandReply requester,
                       Consumer<Boolean> onFinished) {
        this.source = source;
        this.merge = merge;
        this.onFinished = onFinished;
        this.check = check;
        this.force = force;
        this.requester = requester;
    }

    static ImportTask current() {
        return CURRENT.get();
    }

    /** Runs the import (or check) on its own thread; null when another one is running. */
    static ImportTask start(Path source, boolean check, boolean force, PregenWorlds.CommandReply requester) {
        return launch(new ImportTask(source, null, check, force, requester, null));
    }

    /**
     * Merges dimension folders this server wrote itself (a pregen's separate world) into its levels:
     * the same generator made them, so there is no fingerprint to compare. Null when another import runs.
     */
    static ImportTask startMerge(List<Source> sources, PregenWorlds.CommandReply requester, Consumer<Boolean> onFinished) {
        return launch(new ImportTask(sources.get(0).folder(), sources, false, false, requester, onFinished));
    }

    private static ImportTask launch(ImportTask task) {
        if (!CURRENT.compareAndSet(null, task)) return null;
        Thread thread = new Thread(task::run, "UFP-Link-Import");
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    /** Stops after the region file being imported. */
    void cancel(String reason) {
        if (stopReason == null) stopReason = reason;
    }

    String describe() {
        return String.format(Locale.ROOT, "%s %s: %s | %d/%d region files | %,d chunks %s, %,d already here, %,d held by the server,"
                        + " %,d rejected | %,d with mobs | %s",
                check ? "Checking" : merge != null ? "Merging" : "Importing", source, stage, regionsDone, regionsTotal, imported,
                check ? "to import" : "imported", kept, held, rejected, entities, elapsed());
    }

    private String elapsed() {
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt);
        return seconds >= 60 ? seconds / 60 + "m " + seconds % 60 + "s" : seconds + "s";
    }

    private void run() {
        boolean complete = false;
        try {
            List<DimensionPlan> plans = merge != null ? mergePlans() : comparedPlans();
            if (plans == null) return;
            tell(String.format(Locale.ROOT, "%s %s: %d region files in %d dimensions.",
                    check ? "Checking" : merge != null ? "Merging" : "Importing", source, regionsTotal, plans.size()), false);
            for (DimensionPlan plan : plans) {
                if (stopped()) break;
                importDimension(plan);
            }
            String summary = String.format(Locale.ROOT, "%s %,d chunks%s; %,d already here, %,d held by the server, %,d rejected"
                            + " (%d/%d region files, %s).",
                    check ? "Would import" : merge != null ? "Merged" : "Imported", imported,
                    check ? "" : String.format(Locale.ROOT, " (%,d over part-generated ones, %,d with mobs)", replacedPartial, entities),
                    kept, held, rejected,
                    regionsDone, regionsTotal, elapsed());
            if (stopReason != null) summary = "Stopped (" + stopReason + "). " + summary + " Run it again to continue.";
            complete = stopReason == null && regionsDone == regionsTotal;
            tell(summary, true);
        } catch (Throwable failure) {
            UfpLink.LOGGER.error("[UFP Link] {} from {} failed", merge != null ? "Merge" : "Import", source, failure);
            fail("It failed: " + failure + " (see the server log). Chunks written so far stay; running it again continues.");
        } finally {
            CURRENT.compareAndSet(this, null);
            if (onFinished != null) onFinished.accept(complete);
        }
    }

    private List<DimensionPlan> mergePlans() throws IOException {
        List<DimensionPlan> plans = new ArrayList<>();
        for (Source part : merge) {
            List<Path> regions = regionFiles(part.folder().resolve("region"));
            regionsTotal += regions.size();
            plans.add(new DimensionPlan(part.label(), part.target(), part.folder(), regions));
        }
        return plans;
    }

    /** The dimensions to import from another world, after comparing its fingerprint; null when nothing may be imported. */
    private List<DimensionPlan> comparedPlans() throws IOException {
            Fingerprint theirs = Fingerprint.read(source);
            if (theirs == null) {
                fail(source + " has no " + Fingerprint.FILE_NAME + ". Start the server of that world once with " + UfpLink.NAME
                        + " installed (or run /ufplink fingerprint there), stop it, then import.");
                return null;
            }
            Fingerprint.Comparison comparison = Fingerprint.compare(theirs, Fingerprint.compute());
            for (String line : comparison.blocking()) fail(line);
            for (String line : comparison.differences()) tell((force && comparison.blocking().isEmpty() ? "Imported anyway (force): " : "") + line, false);
            for (String line : comparison.notes()) tell(line, false);
            if (!comparison.importable(force)) {
                fail(!comparison.blocking().isEmpty() ? "Nothing was imported."
                        : !comparison.differences().isEmpty()
                        ? "Nothing was imported. Add force only if that world generates exactly like this one; otherwise chunks from"
                            + " it will not line up with the ones this server generates next to them."
                        : "The source world has no dimension this server has.");
                return null;
            }
            List<DimensionPlan> plans = new ArrayList<>();
            for (String id : comparison.dimensions()) {
                if (!UfpLink.worlds().dimensions().contains(id)) continue;
                Path folder = SeparateWorld.folderIn(source, id);
                List<Path> regions = regionFiles(folder.resolve("region"));
                regionsTotal += regions.size();
                plans.add(new DimensionPlan(id, id, folder, regions));
            }
        return plans;
    }

    private boolean stopped() {
        if (stopReason == null && !UfpLink.worlds().serverRunning()) stopReason = "the server is stopping";
        return stopReason != null;
    }

    private static List<Path> regionFiles(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> RegionReader.regionOf(file) != null).sorted().toList();
        }
    }

    private void importDimension(DimensionPlan plan) throws IOException {
        PregenWorlds worlds = UfpLink.worlds();
        int dataVersion = worlds.dataVersion();
        Path entityFolder = plan.folder().resolve("entities");
        for (Path regionFile : plan.regions()) {
            if (stopped()) return;
            stage = plan.id() + " " + regionFile.getFileName();
            List<PregenWorlds.ChunkWrite> frames = new ArrayList<>();
            try (RegionReader reader = RegionReader.open(regionFile)) {
                for (int[] chunk : reader.chunks()) {
                    ChunkPos pos = new ChunkPos(chunk[0], chunk[1]);
                    scanned++;
                    try {
                        ByteBuffer frame = reader.frame(pos.x, pos.z);
                        String problem = ChunkFrames.checkChunk(frame, pos, dataVersion);
                        if (problem != null) {
                            reject(plan.id(), pos, problem);
                            continue;
                        }
                        frames.add(new PregenWorlds.ChunkWrite(pos.toLong(), frame, ChunkFrames.isFull(frame), null));
                    } catch (IOException | RuntimeException failure) {
                        reject(plan.id(), pos, failure.toString());
                    }
                }
            } catch (IOException failure) {
                UfpLink.LOGGER.warn("[UFP Link] Could not read {}: {}", regionFile, failure.toString());
                regionsDone++;
                continue;
            }
            Set<Long> heldNow = new HashSet<>();
            for (long chunk : worlds.heldChunks(plan.dimension(), frames.stream().mapToLong(PregenWorlds.ChunkWrite::chunk).toArray())) {
                heldNow.add(chunk);
            }
            if (!heldNow.isEmpty()) frames.removeIf(frame -> heldNow.contains(frame.chunk()));
            held += heldNow.size();
            PregenWorlds.WriteResult result = worlds.writeChunks(plan.dimension(), PregenWorlds.Store.CHUNKS, frames,
                    PregenWorlds.WriteRule.ABSENT_OR_PART_GENERATED, check);
            replacedPartial += result.replaced();
            imported += result.written().length;
            kept += frames.size() - result.written().length;
            if (!check && result.written().length > 0) {
                importMobs(plan.dimension(), dataVersion, entityFolder.resolve(regionFile.getFileName()), result.written());
            }
            regionsDone++;
            if (System.nanoTime() - lastProgressAt > PROGRESS_NANOS) {
                lastProgressAt = System.nanoTime();
                UfpLink.LOGGER.info("[UFP Link] {}", describe());
            }
        }
    }

    /** The mobs and other entities of chunks just imported, from the source's entities folder. */
    private void importMobs(String dimension, int dataVersion, Path file, long[] chunks) {
        if (!Files.isRegularFile(file)) return;
        List<PregenWorlds.ChunkWrite> frames = new ArrayList<>();
        try (RegionReader reader = RegionReader.open(file)) {
            for (long chunk : chunks) {
                ChunkPos pos = new ChunkPos(chunk);
                if (!reader.has(pos.x, pos.z)) continue;
                try {
                    ByteBuffer frame = reader.frame(pos.x, pos.z);
                    if (ChunkFrames.checkEntities(frame, pos, dataVersion) == null) frames.add(new PregenWorlds.ChunkWrite(chunk, frame, true, null));
                } catch (IOException | RuntimeException failure) {
                    UfpLink.LOGGER.warn("[UFP Link] Entities of chunk [{}, {}] in {} not imported: {}", pos.x, pos.z, file, failure.toString());
                }
            }
            entities += UfpLink.worlds().writeChunks(dimension, PregenWorlds.Store.ENTITIES, frames, PregenWorlds.WriteRule.ABSENT, false).written().length;
        } catch (IOException failure) {
            UfpLink.LOGGER.warn("[UFP Link] Could not import the entities in {}: {}", file, failure.toString());
        }
    }

    private void reject(String dimension, ChunkPos pos, String why) {
        rejected++;
        if (rejected <= LOGGED_REJECTIONS) {
            UfpLink.LOGGER.warn("[UFP Link] {} chunk [{}, {}] not imported: {}", dimension, pos.x, pos.z, why);
        } else if (rejected == LOGGED_REJECTIONS + 1) {
            UfpLink.LOGGER.warn("[UFP Link] More chunks were rejected; they are only counted from here on.");
        }
    }

    private void tell(String message, boolean toOps) {
        UfpLink.LOGGER.info("[UFP Link] {}", message);
        if (requester != null) requester.tell("[UFP Link] " + message, toOps);
    }

    private void fail(String message) {
        UfpLink.LOGGER.warn("[UFP Link] {}", message);
        if (requester != null) requester.fail("[UFP Link] " + message);
    }
}
