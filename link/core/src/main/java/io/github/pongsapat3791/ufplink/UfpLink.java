package io.github.pongsapat3791.ufplink;

import io.github.pongsapat3791.ufplink.api.UfpLinkApi;
import io.github.pongsapat3791.ultrafastpregen.api.PregenApi;
import io.github.pongsapat3791.ultrafastpregen.api.PregenListener;
import io.github.pongsapat3791.ultrafastpregen.api.PregenWorlds;
import io.github.pongsapat3791.ultrafastpregen.api.UltraFastPregenApi;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ultra Fast Pregen Link: generates an area outside the world players are in, on this server or on others, and adds
 * the chunks the world does not have. It names nothing of Minecraft and no loader but in package entry: all it needs
 * of the game comes through Ultra Fast Pregen's API, so one jar runs wherever Ultra Fast Pregen does.
 */
public final class UfpLink {
    public static final String MOD_ID = "ufplink";
    public static final String NAME = "Ultra Fast Pregen Link";
    public static final Logger LOGGER = LoggerFactory.getLogger(NAME);
    private static final int NEEDED_API = 2;
    /** How many ports after the configured one a server tries when it becomes a host. */
    private static final int SPARE_PORTS = 10;

    private static volatile String version = "unknown";
    private static volatile Path configFolder = Path.of("config");
    private static volatile NetworkConfig network;
    private static volatile PregenApi pregen;
    private static volatile PregenListener listener;

    private UfpLink() {}

    /**
     * From the entry point of each loader. The order in which loaders start mods differs, so this waits for Ultra Fast
     * Pregen on a thread of its own, and no server can start before every mod has.
     */
    public static void start(Path configDirectory) {
        version = readVersion();
        configFolder = configDirectory.toAbsolutePath().normalize();
        UfpLinkApi.install(new LinkApiImpl());
        Thread thread = new Thread(UfpLink::attach, "UFP-Link-Attach");
        thread.setDaemon(true);
        thread.start();
    }

    private static String readVersion() {
        try (InputStream in = UfpLink.class.getClassLoader().getResourceAsStream("ufplink-version.txt")) {
            return in == null ? "unknown" : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException failure) {
            return "unknown";
        }
    }

    private static void attach() {
        for (int i = 0; i < 2400; i++) {
            try {
                PregenApi api = UltraFastPregenApi.get();
                if (api.apiVersion() < NEEDED_API) {
                    LOGGER.error("[UFP Link] {} needs Ultra Fast Pregen with API version {}; {} has {}.", NAME, NEEDED_API, api.modVersion(),
                            api.apiVersion());
                    return;
                }
                api.worlds().addCommand(MOD_ID, LinkCommand::run);
                listener = new PregenListener() {
                    @Override
                    public void onServerStarted() {
                        serverStarted();
                    }

                    @Override
                    public void onServerStopping() {
                        serverStopping();
                    }
                };
                api.addListener(listener);
                pregen = api;
                LOGGER.info("{} {} loaded.", NAME, version);
                return;
            } catch (IllegalStateException notYet) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        LOGGER.error("[UFP Link] Ultra Fast Pregen did not start; {} does nothing.", NAME);
    }

    static PregenApi pregen() {
        PregenApi api = pregen;
        if (api == null) throw new IllegalStateException("Ultra Fast Pregen is not there.");
        return api;
    }

    static PregenWorlds worlds() {
        return pregen().worlds();
    }

    /** ufplink-network.conf as read when the server started; null before. */
    static NetworkConfig network() {
        return network;
    }

    static String version() {
        return version;
    }

    /** Once per server, on its first tick: its world's fingerprint is written and its network role started. */
    private static void serverStarted() {
        Fingerprint.writeInBackground();
        Path unfinished = PregenJob.unfinishedJobFile();
        if (unfinished != null) {
            LOGGER.info("[UFP Link] A pregen was cut short ({}); /ufplink pregen resume continues it.", unfinished.getParent());
        }
        try {
            network = NetworkConfig.load(configFolder);
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("[UFP Link] Could not read {}: {}", NetworkConfig.FILE_NAME, failure.toString());
            return;
        }
        if (network.role == NetworkConfig.Role.HOST) {
            LinkHost.start(network);
            if (network.localWorker && LinkHost.running() != null) LinkClient.start(network.localClient());
        } else if (network.role == NetworkConfig.Role.CLIENT) {
            LinkClient.start(network);
        }
    }

    /**
     * Makes this server a host, at once, if it is not one, and returns the line other servers join it with.
     *
     * @param address the addresses clients reach it at, with commas between them; null for all of this machine's
     * @throws IllegalStateException with what to tell the person
     */
    static synchronized Invite invite(String address) {
        NetworkConfig current = network;
        if (current == null) throw new IllegalStateException("The server has not started yet.");
        if (current.role != NetworkConfig.Role.HOST || LinkHost.running() == null) {
            LinkClient.stop();
            NetworkConfig host = listening(current);
            network = host;
            try {
                host.save(configFolder);
            } catch (IOException failure) {
                LOGGER.warn("[UFP Link] Could not write {}: {}", NetworkConfig.FILE_NAME, failure.toString());
            }
            if (host.localWorker) LinkClient.start(host.localClient());
        }
        return new Invite(address != null ? address : Invite.localAddresses(), network.port, network.secret);
    }

    /** Listens on the configured port or one of the next few. */
    private static NetworkConfig listening(NetworkConfig current) {
        for (int port = current.port; port < Math.min(current.port + SPARE_PORTS, 65536); port++) {
            NetworkConfig host = current.asHost(port);
            try {
                LinkHost.listen(host);
                return host;
            } catch (BindException taken) {
                LOGGER.info("[UFP Link] Port {} is in use.", port);
            } catch (IOException failure) {
                throw new IllegalStateException("Could not listen on port " + port + ": " + failure.getMessage());
            }
        }
        throw new IllegalStateException("Ports " + current.port + " to " + (current.port + SPARE_PORTS - 1) + " are all in use.");
    }

    /** Joins the host of an invite, at once. Null when it has started connecting, or what to tell the person. */
    static synchronized String join(String code) {
        NetworkConfig current = network;
        if (current == null) return "The server has not started yet.";
        Invite invite = Invite.read(code);
        if (invite == null) return "That is not an invite: copy the whole line /ufplink invite shows on the host.";
        if (current.role == NetworkConfig.Role.HOST && LinkHost.running() != null && Arrays.equals(invite.secret(), current.secret)) {
            return "That is this server's own invite: use it on the other servers.";
        }
        if (HostJob.current() != null) return "A distributed pregen is running on this server: /ufplink cancel first.";
        LinkHost.stop();
        LinkClient.stop();
        NetworkConfig client = current.asClient(invite);
        network = client;
        try {
            client.save(configFolder);
        } catch (IOException failure) {
            LOGGER.warn("[UFP Link] Could not write {}: {}", NetworkConfig.FILE_NAME, failure.toString());
        }
        LinkClient.start(client);
        return null;
    }

    /** Back to no role, at once. Null when done, or what to tell the person. */
    static synchronized String leave() {
        NetworkConfig current = network;
        if (current == null) return "The server has not started yet.";
        if (current.role == NetworkConfig.Role.NONE) return "This server is not part of a network.";
        if (HostJob.current() != null) return "A distributed pregen is running on this server: /ufplink cancel first.";
        LinkHost.stop();
        LinkClient.stop();
        NetworkConfig none = current.asNone();
        network = none;
        try {
            none.save(configFolder);
        } catch (IOException failure) {
            LOGGER.warn("[UFP Link] Could not write {}: {}", NetworkConfig.FILE_NAME, failure.toString());
        }
        return null;
    }

    /** From a Bukkit plugin's disable: Bukkit closes a plugin's jar before it disables the plugins that one depends on. */
    public static void stop() {
        PregenApi api = pregen;
        if (api != null) api.removeListener(listener);
        serverStopping();
    }

    /** A pregen or import stops after its current batch or region file. */
    private static void serverStopping() {
        HostJob distributed = HostJob.current();
        if (distributed != null) distributed.cancel("the server is stopping");
        LinkHost.stop();
        LinkClient.stop();
        PregenJob job = PregenJob.current();
        if (job != null) job.cancel("the server is stopping");
        ImportTask task = ImportTask.current();
        if (task != null) task.cancel("the server is stopping");
    }
}
