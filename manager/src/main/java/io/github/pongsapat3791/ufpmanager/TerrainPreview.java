package io.github.pongsapat3791.ufpmanager;

import io.github.pongsapat3791.ufpmanager.WebServer.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Pictures of regions for the map, made when the page asks and kept while the region file stays the same. Two threads of
 * the lowest priority draw them, so a server with players or a run going on is not held up; a request waits a moment and
 * else the page asks again.
 */
final class TerrainPreview {
    private static final long MAX_CACHE = 96L * 1024 * 1024;
    private static final long WAIT_MILLIS = 12_000;

    private record Key(String dimension, int x, int z) {}

    private record Picture(long stamp, RegionPainter.Pictures pictures) {
        long size() {
            return pictures.full().length + pictures.small().length;
        }
    }

    private final RegionMap map;
    private final Map<Key, Picture> cache = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<Key, Future<Picture>> pending = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor painters;
    private long cached;

    TerrainPreview(RegionMap map) {
        this.map = map;
        this.painters = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(64), task -> {
            Thread thread = new Thread(task, "UFP-Manager-Terrain");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        painters.allowCoreThreadTimeOut(true);
    }

    void close() {
        painters.shutdownNow();
    }

    /** The region's picture; 202 when it is still being drawn, 404 when the region does not exist. */
    Response picture(String dimension, int x, int z, boolean small) throws IOException {
        Path file = map.regionFile(dimension, x, z);
        if (file == null || !Files.isRegularFile(file)) return Response.error(404, "no such region");
        long stamp = Files.getLastModifiedTime(file).toMillis() * 31 + Files.size(file);
        Key key = new Key(dimension, x, z);
        Picture picture = cached(key, stamp);
        if (picture == null) {
            Future<Picture> drawing;
            try {
                drawing = pending.computeIfAbsent(key, ignored -> painters.submit(() -> draw(key, file, stamp)));
            } catch (RejectedExecutionException busy) {
                return Response.error(202, "busy");
            }
            try {
                picture = drawing.get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException slow) {
                return Response.error(202, "drawing");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Response.error(202, "drawing");
            } catch (ExecutionException failure) {
                return Response.error(500, "cannot draw this region: " + failure.getCause());
            }
        }
        return new Response(200, "image/png", small ? picture.pictures().small() : picture.pictures().full());
    }

    private synchronized Picture cached(Key key, long stamp) {
        Picture picture = cache.get(key);
        return picture != null && picture.stamp() == stamp ? picture : null;
    }

    private Picture draw(Key key, Path file, long stamp) throws IOException {
        try {
            Picture picture = new Picture(stamp, RegionPainter.paint(file, key.dimension()));
            keep(key, picture);
            return picture;
        } finally {
            pending.remove(key);
        }
    }

    private synchronized void keep(Key key, Picture picture) {
        Picture old = cache.put(key, picture);
        if (old != null) cached -= old.size();
        cached += picture.size();
        Iterator<Picture> oldest = cache.values().iterator();
        while (cached > MAX_CACHE && oldest.hasNext()) {
            cached -= oldest.next().size();
            oldest.remove();
        }
    }
}
