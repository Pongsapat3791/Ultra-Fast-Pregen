package io.github.pongsapat3791.ufplink;

import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;

/**
 * config/ufplink-network.conf: whether this server is the host of a distributed pregen (it hands out
 * tiles and merges the chunks clients send), a client (it generates tiles for a host), or neither.
 * A secret is generated on the first start; the host and every client need the same one.
 * /ufplink invite, join and leave write it too, so nobody has to edit it.
 */
public final class NetworkConfig {
    public static final String FILE_NAME = "ufplink-network.conf";

    public enum Role { NONE, HOST, CLIENT }

    public final Role role;
    public final int port;
    public final String host;
    public final byte[] secret;
    public final String name;
    public final int tileChunks;
    /** host: also generates tiles itself, as a client of its own (a host without other machines). */
    public final boolean localWorker;

    private NetworkConfig(Role role, int port, String host, byte[] secret, String name, int tileChunks, boolean localWorker) {
        this.role = role;
        this.port = port;
        this.host = host;
        this.secret = secret;
        this.name = name;
        this.tileChunks = tileChunks;
        this.localWorker = localWorker;
    }

    /** The settings of a host's own worker: a client of this server, named "local". */
    NetworkConfig localClient() {
        return new NetworkConfig(Role.CLIENT, port, "127.0.0.1", secret, "local", tileChunks, false);
    }

    NetworkConfig asHost(int listenOn) {
        return new NetworkConfig(Role.HOST, listenOn, host, secret, name, tileChunks, localWorker);
    }

    NetworkConfig asClient(Invite invite) {
        return new NetworkConfig(Role.CLIENT, invite.port(), invite.address(), invite.secret(), name, tileChunks, localWorker);
    }

    NetworkConfig asNone() {
        return new NetworkConfig(Role.NONE, port, host, secret, name, tileChunks, localWorker);
    }

    void save(Path configFolder) throws IOException {
        write(configFolder.resolve(FILE_NAME), HexFormat.of().formatHex(secret));
    }

    /** Reads the file, writing it first (with a new secret) when it is missing or lacks keys. */
    public static NetworkConfig load(Path configFolder) throws IOException {
        Path file = configFolder.resolve(FILE_NAME);
        Properties values = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                values.load(reader);
            }
        }
        boolean complete = values.containsKey("role") && values.containsKey("port") && values.containsKey("host")
                && values.containsKey("secret") && values.containsKey("name") && values.containsKey("tile_chunks")
                && values.containsKey("local_worker");
        String secret = values.getProperty("secret", "").trim();
        if (secret.length() < 32) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            secret = HexFormat.of().formatHex(random);
            complete = false;
        }
        Role role;
        try {
            role = Role.valueOf(values.getProperty("role", "none").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException wrong) {
            UfpLink.LOGGER.warn("[UFP Link] {}: role must be host, client or none; using none", FILE_NAME);
            role = Role.NONE;
        }
        int port = parse(values.getProperty("port"), 25599, 1, 65535);
        String host = values.getProperty("host", "127.0.0.1").trim();
        String name = values.getProperty("name", defaultName()).trim();
        int tileChunks = parse(values.getProperty("tile_chunks"), 64, 16, 256);
        boolean localWorker = Boolean.parseBoolean(values.getProperty("local_worker", "false").trim());
        NetworkConfig config = new NetworkConfig(role, port, host, HexFormat.of().parseHex(secret), name, tileChunks, localWorker);
        if (!complete) config.write(file, secret);
        return config;
    }

    private void write(Path file, String secretHex) throws IOException {
        String text = "# Ultra Fast Pregen Link: a distributed pregen.\n"
                + "# role=host: the server players play on; it hands out tiles and merges the chunks clients send back.\n"
                + "# role=client: a server that generates tiles for a host (it needs the same seed, mods and datapacks).\n"
                + "# role=none: neither. /ufplink invite, join and leave change this file; restart the server after editing it by hand.\n"
                + "role=" + role.name().toLowerCase(Locale.ROOT) + "\n"
                + "# host: the port it listens on; client: the host's port.\n"
                + "port=" + port + "\n"
                + "# client: the host's address; with several, separated by commas, the first that answers.\n"
                + "host=" + host + "\n"
                + "# The same secret on the host and every client; keep it private. Anyone with it can send chunks to the host.\n"
                + "secret=" + secretHex + "\n"
                + "# client: its name in the host's /ufplink host status.\n"
                + "name=" + name + "\n"
                + "# host: the side of a tile in chunks (16 to 256); each tile is generated by one client.\n"
                + "tile_chunks=" + tileChunks + "\n"
                + "# host: true to generate tiles on this server too, as one more client (also works with no other machine).\n"
                + "local_worker=" + localWorker + "\n";
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static int parse(String value, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
        } catch (NullPointerException | NumberFormatException wrong) {
            return fallback;
        }
    }

    private static String defaultName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException failure) {
            return "client";
        }
    }
}
