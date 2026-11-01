package io.github.pongsapat3791.ufpmanager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Ultra Fast Pregen Manager: a web page inside the server, to run pregens by clicking. The page and its small web
 * server use only java.base, and the mod names nothing of Minecraft: one jar runs on every loader and version. It
 * reaches Ultra Fast Pregen and Ultra Fast Pregen Link through their APIs (PregenBridge, LinkBridge), when installed.
 */
public final class Manager {
    public static final String MOD_ID = "ufpmanager";
    public static final String NAME = "Ultra Fast Pregen Manager";

    private static WebServer web;
    private static Api routes;

    private Manager() {}

    /**
     * Where things are on this platform.
     *
     * @param gameDirectory the server's folder
     * @param configDirectory where ufpmanager.conf goes
     * @param ufpConfig Ultra Fast Pregen's settings file
     * @param log the server's log
     */
    public record Layout(Path gameDirectory, Path configDirectory, Path ufpConfig, Consumer<String> log) {
        public static Layout mod() {
            Path game = Path.of("").toAbsolutePath();
            return new Layout(game, game.resolve("config"), game.resolve("config/ultrafastpregen.conf"),
                    message -> System.out.println("[UFP Manager] " + message));
        }

        public static Layout plugin(Path dataFolder, Consumer<String> log) {
            Path plugins = dataFolder.toAbsolutePath().getParent();
            return new Layout(plugins.getParent(), dataFolder.toAbsolutePath(),
                    plugins.resolve("UltraFastPregen/ultrafastpregen.conf"), log);
        }
    }

    /** Starts the web page. A problem is logged and leaves the server as it is. */
    public static synchronized void start(Layout layout) {
        if (web != null) return;
        try {
            ManagerConfig config = ManagerConfig.load(layout.configDirectory());
            if (!config.enabled) {
                layout.log().accept("The web page is off (enabled=false in " + ManagerConfig.FILE_NAME + ").");
                return;
            }
            Api api = new Api(layout, config);
            WebServer server = new WebServer(config, api, api.codes(), layout.log());
            server.start();
            web = server;
            routes = api;
            // Not the token: logs get pasted into forums.
            layout.log().accept("Web page: http://" + config.shownAddress() + ":" + config.port + "/ (/ufpmanager gives a login link; the token is in "
                    + layout.configDirectory().resolve(ManagerConfig.FILE_NAME) + ")");
            if (!config.loopbackOnly()) {
                layout.log().accept("The page is open to the network (bind=" + config.bind + ") without encryption: keep it on a"
                        + " LAN or a VPN, and the token private.");
            }
        } catch (IOException | RuntimeException failure) {
            layout.log().accept("The web page did not start: " + failure);
        }
    }

    public static synchronized void stop() {
        if (web != null) web.stop();
        if (routes != null) routes.close();
        web = null;
        routes = null;
    }
}
