package io.github.pongsapat3791.ufplink;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import io.github.pongsapat3791.ufplink.api.LinkMachine;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One client connected to the host: the handshake (both sides prove they have the secret, then
 * the client's fingerprint must match the host's), then tiles out and chunks back until it leaves.
 */
final class HostSession implements Runnable {
    private final LinkHost host;
    private final Socket socket;
    private volatile String name = "?";
    private volatile String state = "connecting";
    private volatile long tilesDone;
    private volatile long chunksReceived;
    private volatile long bytesReceived;
    private volatile String version = "?";
    private volatile int threads;
    private volatile long maxHeap;
    private volatile String system = "?";
    private volatile HostJob.Tile tile;
    private volatile double chunksPerSecond;
    private final long connectedAt = System.currentTimeMillis();
    /** Set when the host ends the connection itself (its server stops): then the end is not logged. */
    private volatile boolean closing;

    HostSession(LinkHost host, Socket socket) {
        this.host = host;
        this.socket = socket;
    }

    String describe() {
        return String.format(Locale.ROOT, "%s (%s): %s, %d tiles, %,d chunks", name, socket.getRemoteSocketAddress(), state,
                tilesDone, chunksReceived);
    }

    String name() {
        return name;
    }

    LinkMachine machine() {
        HostJob.Tile current = tile;
        return new LinkMachine(name, String.valueOf(socket.getRemoteSocketAddress()), version, threads, maxHeap, system, state,
                current != null ? current.tileX : 0, current != null ? current.tileZ : 0, current != null, tilesDone,
                chunksReceived, bytesReceived, chunksPerSecond, System.currentTimeMillis() - connectedAt);
    }

    void close() {
        closing = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    @Override
    public void run() {
        try (socket) {
            socket.setSoTimeout(120_000);
            DataInputStream in = Messages.input(socket.getInputStream());
            DataOutputStream out = Messages.output(socket.getOutputStream());
            if (!handshake(in, out)) return;
            host.joined(this);
            while (!socket.isClosed()) {
                Messages.Message message = Messages.read(in);
                if (message.type() != Messages.REQUEST) throw new IOException("unexpected message " + message.type());
                HostJob job = HostJob.current();
                if (job == null || !handOut(job, in, out)) {
                    state = "idle";
                    Messages.Writer idle = new Messages.Writer(Messages.IDLE);
                    idle.out.writeInt(5);
                    idle.out.writeUTF(job == null ? "no distributed pregen is running" : job.idleReason());
                    idle.send(out);
                    if (job != null) job.endIfStopped();
                }
            }
        } catch (IOException | RuntimeException failure) {
            // try-with-resources has closed the socket by now, so only the flag tells the host's own close apart.
            if (!closing) {
                String during = state.startsWith("generating") || state.startsWith("merging")
                        ? " while " + state + "; that tile goes back to the queue" : "";
                String why = failure instanceof java.io.EOFException || failure instanceof java.net.SocketException
                        ? "it closed the connection" : failure.toString();
                UfpLink.LOGGER.info("[UFP Link] Client {} left{}: {}", name, during, why);
            }
        } finally {
            HostJob job = HostJob.current();
            if (job != null) job.release(this);
            host.left(this);
        }
    }

    private boolean handshake(DataInputStream in, DataOutputStream out) throws IOException {
        byte[] challenge = Messages.nonce();
        Messages.Writer hello = new Messages.Writer(Messages.CHALLENGE);
        hello.out.writeInt(Messages.PROTOCOL);
        hello.out.write(challenge);
        hello.send(out);
        Messages.Message auth = Messages.read(in);
        if (auth.type() != Messages.AUTH) throw new IOException("expected AUTH");
        byte[] proof = new byte[32];
        auth.body().readFully(proof);
        byte[] clientChallenge = new byte[32];
        auth.body().readFully(clientChallenge);
        if (!Messages.sameProof(proof, Messages.proof(host.config().secret, "client", challenge))) {
            UfpLink.LOGGER.warn("[UFP Link] A connection from {} did not know the secret", socket.getRemoteSocketAddress());
            host.refused("?", String.valueOf(socket.getRemoteSocketAddress()), "It does not have this host's secret.");
            return false;
        }
        Messages.Writer ok = new Messages.Writer(Messages.AUTH_OK);
        ok.out.write(Messages.proof(host.config().secret, "host", clientChallenge));
        ok.send(out);
        Messages.Message helloMessage = Messages.read(in);
        if (helloMessage.type() != Messages.HELLO) throw new IOException("expected HELLO");
        int protocol = helloMessage.body().readInt();
        name = helloMessage.body().readUTF();
        version = helloMessage.body().readUTF();
        threads = helloMessage.body().readInt();
        maxHeap = helloMessage.body().readLong();
        system = helloMessage.body().readUTF();
        Fingerprint theirs = Fingerprint.fromJson(new String(Messages.readBytes(helloMessage.body()), java.nio.charset.StandardCharsets.UTF_8));
        List<String> problems = new ArrayList<>();
        if (protocol != Messages.PROTOCOL) problems.add("protocol " + protocol + ", the host speaks " + Messages.PROTOCOL);
        Fingerprint.Comparison comparison = Fingerprint.compare(theirs, Fingerprint.compute());
        problems.addAll(comparison.blocking());
        // Another seed or version also gives other terrain everywhere: the rest would say nothing new.
        if (comparison.blocking().isEmpty()) problems.addAll(comparison.differences());
        if (!problems.isEmpty()) {
            String reason = forClient(String.join(" ", problems));
            Messages.Writer reject = new Messages.Writer(Messages.REJECT);
            reject.out.writeUTF(reason);
            reject.send(out);
            UfpLink.LOGGER.warn("[UFP Link] Client {} ({}) refused: {}", name, version, reason);
            host.refused(name, String.valueOf(socket.getRemoteSocketAddress()), reason);
            return false;
        }
        Messages.Writer accept = new Messages.Writer(Messages.ACCEPT);
        accept.out.writeUTF("Accepted by " + UfpLink.NAME + " " + UfpLink.version());
        accept.send(out);
        UfpLink.LOGGER.info("[UFP Link] Client {} ({}, {}) joined", name, socket.getRemoteSocketAddress(), version);
        state = "idle";
        return true;
    }

    /**
     * Gives the client the next tile it can generate and merges what comes back; false when there is
     * none. Tiles the host already has fully generated are marked done on the way, without the client.
     */
    private boolean handOut(HostJob job, DataInputStream in, DataOutputStream out) throws IOException {
        while (true) {
            HostJob.Tile tile = job.assign(this);
            if (tile == null) return false;
            List<HostJob.Context> context;
            try {
                state = "reading the host's chunks for tile [" + tile.tileX + ", " + tile.tileZ + "]";
                context = job.context(tile);
            } catch (IOException | RuntimeException failure) {
                job.release(tile);
                throw failure;
            }
            if (job.alreadyGenerated(tile, context)) {
                job.skip(tile);
                continue;
            }
            generate(job, tile, context, in, out);
            return true;
        }
    }

    /** Sends the tile and its context, takes the chunks back and merges them. */
    private void generate(HostJob job, HostJob.Tile tile, List<HostJob.Context> context, DataInputStream in, DataOutputStream out)
            throws IOException {
        state = "generating tile [" + tile.tileX + ", " + tile.tileZ + "]";
        this.tile = tile;
        long started = System.nanoTime();
        try {
            Messages.Writer header = new Messages.Writer(Messages.TILE);
            header.out.writeLong(tile.unit);
            header.out.writeUTF(job.dimension);
            header.out.writeInt(tile.minChunkX);
            header.out.writeInt(tile.minChunkZ);
            header.out.writeInt(job.tileChunks);
            header.out.writeInt(context.size());
            header.send(out);
            for (HostJob.Context chunk : context) {
                Messages.Writer part = new Messages.Writer(Messages.CONTEXT);
                part.out.writeInt(chunk.pos().x);
                part.out.writeInt(chunk.pos().z);
                part.out.write(chunk.hash());
                byte[] frame = new byte[chunk.frame().remaining()];
                chunk.frame().duplicate().get(frame);
                Messages.writeBytes(part.out, frame);
                part.send(out);
            }
            // Generating a tile takes a while; the client's results then come without pause.
            socket.setSoTimeout(0);
            List<HostJob.Replacement> results = new ArrayList<>();
            while (true) {
                Messages.Message message = Messages.read(in);
                if (message.type() == Messages.RESULT) {
                    DataInputStream body = message.body();
                    if (body.readLong() != tile.unit) throw new IOException("a result for another tile");
                    ChunkPos pos = new ChunkPos(body.readInt(), body.readInt());
                    byte[] base = null;
                    if (body.readBoolean()) {
                        base = new byte[32];
                        body.readFully(base);
                    }
                    byte[] frame = Messages.readBytes(body);
                    results.add(new HostJob.Replacement(pos, ByteBuffer.wrap(frame), base));
                    chunksReceived++;
                    bytesReceived += frame.length;
                } else if (message.type() == Messages.DONE) {
                    if (message.body().readLong() != tile.unit) throw new IOException("DONE for another tile");
                    break;
                } else if (message.type() == Messages.FAILED) {
                    message.body().readLong();
                    String why = message.body().readUTF();
                    UfpLink.LOGGER.warn("[UFP Link] Client {} could not generate tile [{}, {}]: {}", name, tile.tileX, tile.tileZ, why);
                    job.release(tile);
                    return;
                } else {
                    throw new IOException("unexpected message " + message.type());
                }
            }
            socket.setSoTimeout(120_000);
            state = "merging tile [" + tile.tileX + ", " + tile.tileZ + "]";
            job.merge(tile, results);
            tilesDone++;
            chunksPerSecond = job.tileChunks * job.tileChunks / ((System.nanoTime() - started) / 1e9);
            Messages.Writer merged = new Messages.Writer(Messages.MERGED);
            merged.out.writeLong(tile.unit);
            merged.out.writeInt(results.size());
            merged.send(out);
            UfpLink.LOGGER.info("[UFP Link] Tile [{}, {}] from {} merged ({} chunks). {}", tile.tileX, tile.tileZ, name, results.size(),
                    job.describe());
            state = "idle";
        } catch (IOException | RuntimeException failure) {
            job.release(tile);
            throw failure;
        } finally {
            this.tile = null;
        }
    }

    /** Fingerprint.compare speaks of a world being imported; a refused client reads this about itself and the host. */
    private static String forClient(String problems) {
        return problems.replace("the source world", "this client").replace("the source has", "this client has").replace("only in the source", "only on this client")
                .replace("in the source", "on this client").replace("this world", "the host").replace("this server", "the host").replace(" here", " on the host");
    }
}
