package io.github.pongsapat3791.ufplink;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import io.github.pongsapat3791.ufplink.api.LinkRefusal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The host's listener: accepts clients on the configured port, one HostSession thread each. */
final class LinkHost {
    private static volatile LinkHost running;

    private final NetworkConfig config;
    private final ServerSocket socket;
    private final List<HostSession> sessions = new CopyOnWriteArrayList<>();
    private final Deque<LinkRefusal> refusals = new ArrayDeque<>();

    private LinkHost(NetworkConfig config, ServerSocket socket) {
        this.config = config;
        this.socket = socket;
    }

    static LinkHost running() {
        return running;
    }

    static void start(NetworkConfig config) {
        try {
            listen(config);
        } catch (IOException failure) {
            UfpLink.LOGGER.error("[UFP Link] Host: could not listen on port {}", config.port, failure);
        }
    }

    /** Throws when the port is taken. */
    static void listen(NetworkConfig config) throws IOException {
        ServerSocket listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(config.port));
        } catch (IOException failure) {
            listener.close();
            throw failure;
        }
        LinkHost host = new LinkHost(config, listener);
        running = host;
        Thread thread = new Thread(host::accept, "UFP-Link-Host");
        thread.setDaemon(true);
        thread.start();
        UfpLink.LOGGER.info("[UFP Link] Host: listening for clients on port {}", config.port);
    }

    static void stop() {
        LinkHost host = running;
        running = null;
        if (host == null) return;
        try {
            host.socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        host.sessions.forEach(HostSession::close);
    }

    private void accept() {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                client.setTcpNoDelay(true);
                Thread thread = new Thread(new HostSession(this, client), "UFP-Link-Host-Session");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException failure) {
                if (!socket.isClosed()) UfpLink.LOGGER.warn("[UFP Link] Host: accepting a client failed: {}", failure.toString());
            }
        }
    }

    NetworkConfig config() {
        return config;
    }

    void joined(HostSession session) {
        sessions.add(session);
    }

    void left(HostSession session) {
        sessions.remove(session);
    }

    List<HostSession> sessions() {
        return sessions;
    }

    void refused(String name, String address, String reason) {
        synchronized (refusals) {
            refusals.addFirst(new LinkRefusal(name, address, reason, System.currentTimeMillis()));
            while (refusals.size() > 20) refusals.removeLast();
        }
    }

    List<LinkRefusal> refusals() {
        synchronized (refusals) {
            return List.copyOf(refusals);
        }
    }
}
