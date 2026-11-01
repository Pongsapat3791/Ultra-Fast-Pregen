package io.github.pongsapat3791.ufpmanager;

import io.github.pongsapat3791.ufpmanager.WebServer.BadRequest;
import io.github.pongsapat3791.ufpmanager.WebServer.Request;
import io.github.pongsapat3791.ufpmanager.WebServer.Response;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** What the page can ask. The bridges exist only with their mod installed, so each class that names a mod's API loads then. */
final class Api implements WebServer.Routes {
    private static final Map<String, String> ASSETS = Map.of(
            "/", "index.html", "/index.html", "index.html", "/app.js", "app.js", "/app.css", "app.css");
    private static final long START = System.currentTimeMillis();

    private final Manager.Layout layout;
    private final RegionMap map;
    private final TerrainPreview terrain;
    private final UfpSettings settings;
    private final PregenBridge pregen;
    private final LinkBridge link;
    private final ManagerConfig config;
    private final LoginCodes codes = new LoginCodes();

    Api(Manager.Layout layout, ManagerConfig config) {
        this.config = config;
        this.layout = layout;
        this.map = new RegionMap(layout.gameDirectory());
        this.terrain = new TerrainPreview(map);
        this.settings = new UfpSettings(layout.ufpConfig());
        this.pregen = present("io.github.pongsapat3791.ultrafastpregen.api.UltraFastPregenApi") ? new PregenBridge(layout.log(), this::loginLink) : null;
        this.link = present("io.github.pongsapat3791.ufplink.api.UfpLinkApi") ? new LinkBridge() : null;
        if (pregen != null) map.use(pregen);
        if (pregen == null && link == null) layout.log().accept("Neither Ultra Fast Pregen nor Ultra Fast Pregen Link is installed.");
    }

    /** What /ufpmanager says. */
    private String loginLink() {
        String link = "Open http://" + config.shownAddress() + ":" + config.port + "/#code=" + codes.next() + " (it works once, for "
                + LoginCodes.LIFETIME_MINUTES + " minutes).";
        return config.loopbackOnly() ? link + " From another machine: ssh -L " + config.port + ":127.0.0.1:" + config.port
                + " you@this-server, then the same link there." : link;
    }

    LoginCodes codes() {
        return codes;
    }

    private static boolean present(String name) {
        try {
            Class.forName(name, false, Api.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }

    /** A plugin can be disabled while the server goes on: a later start has its own listeners. */
    void close() {
        if (pregen != null) pregen.close();
        terrain.close();
    }

    @Override
    public Response asset(String path) throws IOException {
        String file = ASSETS.get(path);
        if (file == null) return null;
        try (InputStream in = Api.class.getClassLoader().getResourceAsStream("web/" + file)) {
            if (in == null) return null;
            String type = file.endsWith(".js") ? "text/javascript" : file.endsWith(".css") ? "text/css" : "text/html";
            return new Response(200, type + "; charset=utf-8", in.readAllBytes());
        }
    }

    @Override
    public Response handle(Request request) throws IOException {
        boolean post = request.method().equals("POST");
        return switch (request.path()) {
            case "/api/info" -> Response.json(info());
            case "/api/poll" -> Response.json(poll(request));
            case "/api/dimensions" -> Response.json(Json.object("dimensions", map.dimensions()));
            case "/api/map/regions" -> Response.json(Json.object("regions", map.regions(dimension(request))));
            case "/api/map/region" -> new Response(200, "application/octet-stream",
                    map.chunks(dimension(request), request.number("x"), request.number("z")));
            case "/api/map/terrain" -> terrain.picture(dimension(request), request.number("x"), request.number("z"),
                    "small".equals(request.field("size")));
            case "/api/settings" -> {
                if (post) settings.save(request.fields());
                yield Response.json(settings.values());
            }
            case "/api/pregen/start" -> posted(post, () -> needPregen().start(request));
            case "/api/pregen/stop" -> posted(post, () -> needPregen().stop());
            case "/api/pregen/resume" -> posted(post, () -> needPregen().resume(request));
            case "/api/link/plan" -> posted(post, () -> needLink().plan(request));
            case "/api/link/start" -> posted(post, () -> needLink().start(request));
            case "/api/link/stop" -> posted(post, () -> needLink().stop());
            case "/api/link/invite" -> posted(post, () -> needLink().invite(request));
            case "/api/link/join" -> posted(post, () -> needLink().join(request));
            case "/api/link/leave" -> posted(post, () -> needLink().leave());
            case "/api/link/local" -> posted(post, () -> needLink().startLocal(request));
            case "/api/link/resume" -> posted(post, () -> needLink().resumeLocal());
            default -> null;
        };
    }

    private interface Action {
        Object run() throws IOException;
    }

    private static Response posted(boolean post, Action action) throws IOException {
        if (!post) return Response.error(405, "use POST");
        return Response.json(action.run());
    }

    private PregenBridge needPregen() {
        if (pregen == null) throw new BadRequest("Ultra Fast Pregen is not installed.");
        return pregen;
    }

    private LinkBridge needLink() {
        if (link == null) throw new BadRequest("Ultra Fast Pregen Link is not installed.");
        return link;
    }

    /** A dimension id, checked: it becomes part of a path. */
    private static String dimension(Request request) {
        String id = request.field("dimension");
        if (id == null || !RegionMap.validDimension(id)) throw new BadRequest("bad dimension");
        return id;
    }

    private Map<String, Object> info() {
        Runtime runtime = Runtime.getRuntime();
        return Json.object(
                "manager", version(),
                "ufp", pregen != null ? pregen.info() : null,
                "link", link != null ? link.info() : null,
                "cpus", runtime.availableProcessors(),
                "heapMaxMb", runtime.maxMemory() >> 20,
                "system", System.getProperty("os.name", "?") + " " + System.getProperty("os.arch", ""),
                "java", System.getProperty("java.version", "?"),
                "world", map.worldName());
    }

    private Object poll(Request request) {
        Runtime runtime = Runtime.getRuntime();
        return Json.object(
                "heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) >> 20,
                "uptimeMs", System.currentTimeMillis() - START,
                "ufp", pregen != null ? pregen.poll(request.field("since") == null ? -1 : request.number("since")) : null,
                "link", link != null ? link.poll() : null);
    }

    private static String version() {
        try (InputStream in = Api.class.getClassLoader().getResourceAsStream("web/version.txt")) {
            return in == null ? "?" : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException failure) {
            return "?";
        }
    }
}
