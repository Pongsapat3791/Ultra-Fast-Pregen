package io.github.pongsapat3791.ufpmanager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A small HTTP/1.1 server on plain sockets, one request per connection. The JDK's own web server is not used: Forge and
 * NeoForge start the game on the module path, where jdk.httpserver is not always there, and Mojang's Java leaves it out.
 *
 * Every /api/ request needs the token in the Authorization header. The page keeps it in the browser and sends no cookie,
 * so another site cannot make a browser call the API on its behalf. Only /api/login goes without: it trades a code from
 * /ufpmanager for the token.
 */
final class WebServer {
    private static final int MAX_HEADER_LINE = 8 * 1024;
    private static final int MAX_HEADERS = 64;
    private static final int MAX_BODY = 8 * 1024 * 1024;
    private static final String SECURITY = "X-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n"
            + "Referrer-Policy: no-referrer\r\nCache-Control: no-store\r\n"
            + "Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self';"
            + " img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n";

    /** A request: path without the query, and the query's and the form body's fields. */
    record Request(String method, String path, Map<String, List<String>> fields) {
        String field(String name) {
            List<String> values = fields.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        List<String> all(String name) {
            return fields.getOrDefault(name, List.of());
        }

        int number(String name) {
            String value = field(name);
            if (value == null) throw new BadRequest("missing " + name);
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException wrong) {
                throw new BadRequest(name + " is not a whole number");
            }
        }
    }

    record Response(int status, String type, byte[] body) {
        static Response json(Object value) {
            return new Response(200, "application/json; charset=utf-8", Json.write(value).getBytes(StandardCharsets.UTF_8));
        }

        static Response error(int status, String message) {
            return new Response(status, "application/json; charset=utf-8",
                    Json.write(Map.of("error", message)).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A request the API cannot use; the message goes back to the page. */
    static final class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }

    interface Routes {
        /** The answer to a request, or null when nothing is at that path. */
        Response handle(Request request) throws IOException;

        /** A file of the page, or null. Needs no token: the page holds no data. */
        Response asset(String path) throws IOException;
    }

    private final ManagerConfig config;
    private final Routes routes;
    private final Consumer<String> log;
    private final byte[] token;
    private final LoginCodes codes;
    private final ThreadPoolExecutor workers;
    private volatile ServerSocket socket;

    WebServer(ManagerConfig config, Routes routes, LoginCodes codes, Consumer<String> log) {
        this.config = config;
        this.codes = codes;
        this.routes = routes;
        this.log = log;
        this.token = ("Bearer " + config.token).getBytes(StandardCharsets.UTF_8);
        this.workers = new ThreadPoolExecutor(0, 24, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "UFP-Manager-Web");
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() throws IOException {
        ServerSocket listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(InetAddress.getByName(config.bind), config.port), 64);
        socket = listener;
        Thread thread = new Thread(this::accept, "UFP-Manager-Accept");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        workers.shutdownNow();
    }

    private void accept() {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                try {
                    workers.execute(() -> serve(client));
                } catch (RejectedExecutionException full) {
                    client.close();
                }
            } catch (SocketException closed) {
                return;
            } catch (IOException failure) {
                log.accept("Accepting a connection failed: " + failure);
            }
        }
    }

    private void serve(Socket client) {
        try (client) {
            client.setSoTimeout(20_000);
            client.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            Response response;
            try {
                response = answer(in);
            } catch (BadRequest wrong) {
                response = Response.error(400, wrong.getMessage());
            } catch (RuntimeException failure) {
                log.accept("A request failed: " + failure);
                response = Response.error(500, failure.toString());
            }
            write(out, response);
        } catch (IOException gone) {
            // the browser left; nothing to answer
        }
    }

    private Response answer(InputStream in) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null) throw new BadRequest("empty request");
        String[] parts = requestLine.split(" ");
        if (parts.length != 3) throw new BadRequest("bad request line");
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; ; i++) {
            if (i >= MAX_HEADERS) throw new BadRequest("too many headers");
            String line = readLine(in);
            if (line == null || line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        byte[] body = readBody(in, headers);
        String target = parts[1];
        int query = target.indexOf('?');
        String path = query < 0 ? target : target.substring(0, query);
        if (!path.startsWith("/api/")) {
            if (!parts[0].equals("GET") && !parts[0].equals("HEAD")) return Response.error(405, "not allowed");
            Response asset = routes.asset(path);
            return asset != null ? asset : Response.error(404, "not found");
        }
        Map<String, List<String>> fields = new HashMap<>();
        if (path.equals("/api/login")) {
            if (!parts[0].equals("POST")) return Response.error(405, "use POST");
            parseForm(new String(body, StandardCharsets.UTF_8), fields);
            List<String> code = fields.get("code");
            if (code != null && codes.use(code.get(0))) return Response.json(Map.of("token", config.token));
            sleep(1000);
            return Response.error(401, "wrong code");
        }
        if (!authorized(headers.get("authorization"))) {
            sleep(250);
            return Response.error(401, "wrong token");
        }
        if (query >= 0) parseForm(target.substring(query + 1), fields);
        if (body.length > 0) parseForm(new String(body, StandardCharsets.UTF_8), fields);
        Response response = routes.handle(new Request(parts[0], path, fields));
        return response != null ? response : Response.error(404, "not found");
    }

    private boolean authorized(String header) {
        return header != null && MessageDigest.isEqual(token, header.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
        String length = headers.get("content-length");
        if (length == null) return new byte[0];
        int size;
        try {
            size = Integer.parseInt(length.trim());
        } catch (NumberFormatException wrong) {
            throw new BadRequest("bad length");
        }
        if (size < 0 || size > MAX_BODY) throw new BadRequest("too large");
        return in.readNBytes(size);
    }

    private static void parseForm(String form, Map<String, List<String>> fields) {
        for (String pair : form.split("&")) {
            if (pair.isEmpty()) continue;
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            try {
                fields.computeIfAbsent(URLDecoder.decode(key, StandardCharsets.UTF_8), name -> new ArrayList<>())
                        .add(URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException wrong) {
                throw new BadRequest("bad form");
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int next = in.read();
            if (next < 0) return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
            if (next == '\n') break;
            if (next != '\r') line.write(next);
            if (line.size() > MAX_HEADER_LINE) throw new BadRequest("line too long");
        }
        return line.toString(StandardCharsets.ISO_8859_1);
    }

    private static void write(OutputStream out, Response response) throws IOException {
        String head = "HTTP/1.1 " + response.status() + " " + (response.status() < 400 ? "OK" : "Error") + "\r\n"
                + "Content-Type: " + response.type() + "\r\n"
                + "Content-Length: " + response.body().length + "\r\n"
                + SECURITY + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(response.body());
        out.flush();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
