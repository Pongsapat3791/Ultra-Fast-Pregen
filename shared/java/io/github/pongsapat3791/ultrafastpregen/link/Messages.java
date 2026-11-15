package io.github.pongsapat3791.ultrafastpregen.link;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The host-client protocol over TCP: each message is [int length][byte type][payload]. The host
 * sends a challenge; the client sends one of its own and an HMAC of both under the shared secret,
 * which the host answers the same way, so each side knows the other has the secret. Everything after
 * that is encrypted under keys made from the secret and both challenges (see {@link Sealed}): the
 * client sends its fingerprint, asks for tiles and sends the chunks back.
 */
public final class Messages {
    public static final int PROTOCOL = 3;
    /** Larger messages end the connection (the largest chunks are a few MiB). */
    static final int MAX_MESSAGE = 64 * 1024 * 1024;
    /** The same before a side has proved it has the secret, which takes a few short messages. */
    private static final int MAX_HANDSHAKE = 256;

    public static final byte CHALLENGE = 1;
    public static final byte AUTH = 2;
    public static final byte AUTH_OK = 3;
    public static final byte HELLO = 4;
    public static final byte ACCEPT = 5;
    public static final byte REJECT = 6;
    public static final byte REQUEST = 7;
    public static final byte IDLE = 8;
    public static final byte TILE = 9;
    public static final byte CONTEXT = 10;
    public static final byte RESULT = 11;
    public static final byte DONE = 12;
    public static final byte MERGED = 13;
    public static final byte FAILED = 14;

    private Messages() {}

    /** A message read from the stream: its type and a reader over its payload. */
    public record Message(byte type, DataInputStream body) {}

    public static Message read(DataInputStream in) throws IOException {
        return read(in, MAX_MESSAGE);
    }

    /** Reads a message from a side that has not proved it has the secret yet. */
    static Message readHandshake(DataInputStream in) throws IOException {
        return read(in, MAX_HANDSHAKE);
    }

    private static Message read(DataInputStream in, int largest) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > largest) throw new IOException("bad message length " + length);
        byte type = in.readByte();
        byte[] payload = new byte[length - 1];
        in.readFully(payload);
        return new Message(type, new DataInputStream(new java.io.ByteArrayInputStream(payload)));
    }

    /** Builds a message's payload, then writes it in one piece. */
    public static final class Writer {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        public final DataOutputStream out = new DataOutputStream(bytes);
        private final byte type;

        public Writer(byte type) {
            this.type = type;
        }

        public void send(DataOutputStream to) throws IOException {
            byte[] payload = bytes.toByteArray();
            synchronized (to) {
                to.writeInt(payload.length + 1);
                to.writeByte(type);
                to.write(payload);
                to.flush();
            }
        }
    }

    public static void writeBytes(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
    }

    public static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_MESSAGE) throw new IOException("bad length " + length);
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    public static byte[] nonce() {
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        return nonce;
    }

    /** The keys of one connection, one per direction. */
    record Keys(byte[] toHost, byte[] toClient) {}

    static Keys keys(byte[] secret, byte[] hostNonce, byte[] clientNonce) {
        byte[] connection = hmac(secret, "keys", hostNonce, clientNonce);
        return new Keys(hmac(connection, "to host"), hmac(connection, "to client"));
    }

    /** What a side sends to show it has the secret: an HMAC of its role ("client" or "host") and both nonces. */
    public static byte[] proof(byte[] secret, String role, byte[] hostNonce, byte[] clientNonce) {
        return hmac(secret, role, hostNonce, clientNonce);
    }

    /** HMAC-SHA256 of the label and the data, whose lengths are fixed for each label. */
    private static byte[] hmac(byte[] key, String label, byte[]... data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(label.getBytes(StandardCharsets.UTF_8));
            for (byte[] part : data) mac.update(part);
            return mac.doFinal();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static boolean sameProof(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    static DataInputStream input(InputStream stream) {
        return new DataInputStream(new java.io.BufferedInputStream(stream, 1 << 16));
    }

    static DataOutputStream output(OutputStream stream) {
        return new DataOutputStream(new java.io.BufferedOutputStream(stream, 1 << 16));
    }
}
