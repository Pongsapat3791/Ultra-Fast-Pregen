package io.github.pongsapat3791.ufpmanager;

import java.io.IOException;
import java.io.Reader;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Properties;

/**
 * ufpmanager.conf. The page starts open to this machine only, and with a token of its own: whoever has the token can
 * start and stop pregens, so it is written on the first start, and /ufpmanager gives a code that stands in for it once.
 */
final class ManagerConfig {
    static final String FILE_NAME = "ufpmanager.conf";

    final boolean enabled;
    final String bind;
    final int port;
    final String token;

    private ManagerConfig(boolean enabled, String bind, int port, String token) {
        this.enabled = enabled;
        this.bind = bind;
        this.port = port;
        this.token = token;
    }

    static ManagerConfig load(Path directory) throws IOException {
        Path file = directory.resolve(FILE_NAME);
        Properties values = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                values.load(reader);
            }
        }
        boolean complete = values.containsKey("enabled") && values.containsKey("bind") && values.containsKey("port");
        String token = values.getProperty("token", "").trim();
        if (token.length() < 16) {
            byte[] random = new byte[24];
            new SecureRandom().nextBytes(random);
            token = HexFormat.of().formatHex(random);
            complete = false;
        }
        boolean enabled = !"false".equalsIgnoreCase(values.getProperty("enabled", "true").trim());
        String bind = values.getProperty("bind", "127.0.0.1").trim();
        int port;
        try {
            port = Math.max(1, Math.min(65535, Integer.parseInt(values.getProperty("port", "25580").trim())));
        } catch (NumberFormatException wrong) {
            port = 25580;
        }
        ManagerConfig config = new ManagerConfig(enabled, bind, port, token);
        if (!complete) config.write(file);
        return config;
    }

    private void write(Path file) throws IOException {
        String text = "# Ultra Fast Pregen Manager: the web page of this server. Restart the server after changing this file.\n"
                + "# enabled: false turns the page off.\n"
                + "enabled=" + enabled + "\n"
                + "# bind: the address the page listens on. 127.0.0.1 is this machine only. 0.0.0.0 is every network of this\n"
                + "#   machine: nothing is encrypted, so keep it on a LAN or a VPN, or reach 127.0.0.1 through an SSH tunnel.\n"
                + "bind=" + bind + "\n"
                + "# port: the page's port.\n"
                + "port=" + port + "\n"
                + "# token: whoever has it can start and stop pregens. Keep it private; delete the line for a new one.\n"
                + "token=" + token + "\n";
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    boolean loopbackOnly() {
        try {
            return InetAddress.getByName(bind).isLoopbackAddress();
        } catch (UnknownHostException unknown) {
            return false;
        }
    }

    /** The address for a link: on 0.0.0.0, this machine's on its main network. Connecting a UDP socket sends nothing. */
    String shownAddress() {
        if (!"0.0.0.0".equals(bind)) return bind;
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(InetAddress.getByName("192.0.2.1"), 9);
            InetAddress address = probe.getLocalAddress();
            if (!address.isAnyLocalAddress() && !address.isLoopbackAddress()) return address.getHostAddress();
        } catch (IOException | RuntimeException failure) {
            // no route: say it in words
        }
        return "<this machine's address>";
    }
}
