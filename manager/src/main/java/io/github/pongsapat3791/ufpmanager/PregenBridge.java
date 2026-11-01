package io.github.pongsapat3791.ufpmanager;

import io.github.pongsapat3791.ufpmanager.WebServer.BadRequest;
import io.github.pongsapat3791.ufpmanager.WebServer.Request;
import io.github.pongsapat3791.ultrafastpregen.api.PregenApi;
import io.github.pongsapat3791.ultrafastpregen.api.PregenListener;
import io.github.pongsapat3791.ultrafastpregen.api.PregenRequest;
import io.github.pongsapat3791.ultrafastpregen.api.PregenStatus;
import io.github.pongsapat3791.ultrafastpregen.api.PregenWorlds;
import io.github.pongsapat3791.ultrafastpregen.api.StartResult;
import io.github.pongsapat3791.ultrafastpregen.api.UltraFastPregenApi;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Ultra Fast Pregen, through its API. Only this class names it. The listener is added from a thread of its own, as the
 * order in which loaders start mods differs; a run's chunks are kept for the page's map, the last ones only.
 */
final class PregenBridge implements PregenListener, RegionMap.LiveWorlds {
    private static final int KEEP_BATCHES = 48;
    private static final int MAX_POLLED_CHUNKS = 40_000;
    private static final int KEEP_FINISHED = 30;

    private record Saved(long sequence, String dimension, long[] chunks) {}

    private final Deque<Saved> saved = new ArrayDeque<>();
    private final Deque<Map<String, Object>> finished = new ArrayDeque<>();
    private long sequence;
    private final Supplier<String> loginLink;
    private volatile boolean closed;

    PregenBridge(Consumer<String> log, Supplier<String> loginLink) {
        this.loginLink = loginLink;
        Thread thread = new Thread(() -> attach(log), "UFP-Manager-Attach");
        thread.setDaemon(true);
        thread.start();
    }

    private void attach(Consumer<String> log) {
        for (int i = 0; i < 6000 && !closed; i++) {
            try {
                PregenApi api = UltraFastPregenApi.get();
                api.addListener(this);
                if (api.apiVersion() >= 2) api.worlds().addCommand(Manager.MOD_ID, this::command);
                return;
            } catch (IllegalStateException notYet) {
                sleep(50);
            }
        }
        log.accept("Ultra Fast Pregen did not initialize; the map shows no chunks of a run.");
    }

    /** /ufpmanager: a login link. It lets whoever has it run the page, so level 4 only: the console and a singleplayer world's owner. */
    private boolean command(String arguments, PregenWorlds.CommandReply source) {
        if (!source.hasPermission(4)) {
            source.fail("[UFP Manager] That needs operator level 4.");
            return false;
        }
        source.tell("[UFP Manager] " + loginLink.get(), false);
        return true;
    }

    void close() {
        closed = true;
        try {
            UltraFastPregenApi.get().removeListener(this);
        } catch (IllegalStateException notYet) {
            // the listener was never added
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** The running server's worlds, or null: no server now, or an Ultra Fast Pregen before API version 2. */
    private static PregenWorlds worlds() {
        try {
            PregenApi api = UltraFastPregenApi.get();
            return api.apiVersion() >= 2 && api.worlds().serverRunning() ? api.worlds() : null;
        } catch (IllegalStateException notYet) {
            return null;
        }
    }

    @Override
    public Path worldFolder() {
        PregenWorlds worlds = worlds();
        return worlds == null ? null : worlds.worldFolder();
    }

    @Override
    public Path dimensionFolder(String dimension) {
        PregenWorlds worlds = worlds();
        return worlds == null || !worlds.dimensions().contains(dimension) ? null : worlds.dimensionFolder(dimension);
    }

    /** Without a separate world a Link run has open: it is no world to show. */
    @Override
    public List<String> dimensions() {
        PregenWorlds worlds = worlds();
        return worlds == null ? List.of() : worlds.dimensions().stream().filter(id -> !id.startsWith("ufplink:") && !id.contains(":ufplink_pregen_")).toList();
    }

    private static PregenApi api() {
        try {
            return UltraFastPregenApi.get();
        } catch (IllegalStateException notYet) {
            throw new BadRequest("Ultra Fast Pregen has not started yet.");
        }
    }

    Map<String, Object> info() {
        try {
            PregenApi api = UltraFastPregenApi.get();
            return Json.object("version", api.modVersion(), "api", api.apiVersion());
        } catch (IllegalStateException notYet) {
            return Json.object("version", "starting", "api", 0);
        }
    }

    Map<String, Object> poll(long since) {
        Map<String, Object> answer = Json.object();
        try {
            answer.put("status", UltraFastPregenApi.get().status().map(PregenBridge::shown).orElse(null));
            answer.put("running", UltraFastPregenApi.get().isRunning());
        } catch (IllegalStateException notYet) {
            answer.put("status", null);
            answer.put("running", false);
        }
        List<Object> batches = new ArrayList<>();
        synchronized (this) {
            int count = 0;
            long last = since;
            for (Saved batch : saved) {
                if (batch.sequence() <= since) continue;
                if (count + batch.chunks().length > MAX_POLLED_CHUNKS && count > 0) break;
                count += batch.chunks().length;
                last = batch.sequence();
                int[] pairs = new int[batch.chunks().length * 2];
                for (int i = 0; i < batch.chunks().length; i++) {
                    long chunk = batch.chunks()[i];
                    pairs[2 * i] = (int) chunk;
                    pairs[2 * i + 1] = (int) (chunk >>> 32);
                }
                batches.add(Json.object("dimension", batch.dimension(), "chunks", pairs));
            }
            answer.put("sequence", since < 0 ? sequence : last);
            answer.put("batches", since < 0 ? List.of() : batches);
            answer.put("finished", new ArrayList<>(finished));
        }
        return answer;
    }

    Map<String, Object> start(Request request) {
        String dimension = request.field("dimension");
        if (dimension == null) throw new BadRequest("missing dimension");
        int x = request.number("x");
        int z = request.number("z");
        int radius = request.number("radius");
        if (radius < 1 || radius > PregenRequest.MAX_RADIUS) throw new BadRequest("The radius is 1 to " + PregenRequest.MAX_RADIUS + " blocks.");
        PregenRequest area = "circle".equals(request.field("shape")) ? PregenRequest.circle(dimension, x, z, radius)
                : PregenRequest.square(dimension, x, z, radius);
        return shown(api().start(area));
    }

    Map<String, Object> resume(Request request) {
        String dimension = request.field("dimension");
        if (dimension == null) throw new BadRequest("missing dimension");
        return shown(api().resume(dimension));
    }

    Map<String, Object> stop() {
        return Json.object("stopping", api().stop());
    }

    private static Map<String, Object> shown(StartResult result) {
        return Json.object("started", result.started(), "message", result.message());
    }

    private static Map<String, Object> shown(PregenStatus status) {
        PregenRequest area = status.request();
        return Json.object(
                "dimension", area.dimension(), "x", area.centerX(), "z", area.centerZ(), "radius", area.radius(),
                "shape", area.shape().name().toLowerCase(java.util.Locale.ROOT),
                "state", status.state().name(), "ended", status.state().ended(),
                "total", status.totalChunks(), "done", status.doneChunks(), "kept", status.keptChunks(),
                "progress", status.progress(), "chunksPerSecond", status.chunksPerSecond(),
                "elapsedMs", status.elapsedMillis(), "etaMs", status.etaMillis(), "stage", status.stage());
    }

    @Override
    public synchronized void onChunksSaved(String dimension, long[] chunks) {
        saved.addLast(new Saved(++sequence, dimension, chunks));
        while (saved.size() > KEEP_BATCHES) saved.removeFirst();
    }

    @Override
    public synchronized void onFinish(PregenStatus status) {
        Map<String, Object> entry = shown(status);
        entry.put("when", System.currentTimeMillis());
        finished.addFirst(entry);
        while (finished.size() > KEEP_FINISHED) finished.removeLast();
    }
}
