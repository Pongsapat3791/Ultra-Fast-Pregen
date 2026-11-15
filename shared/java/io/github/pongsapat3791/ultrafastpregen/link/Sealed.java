package io.github.pongsapat3791.ultrafastpregen.link;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The connection after the handshake: AES-256-GCM records of [int length][ciphertext and tag]. Each
 * direction has its own key, new for every connection, and the record's number is its nonce, so a
 * record changed, left out, repeated or moved on the way fails its tag and ends the connection.
 */
final class Sealed {
    /** Plain bytes per record; a flush seals what there is. */
    static final int RECORD = 1 << 16;
    private static final int TAG_BYTES = 16;

    private Sealed() {}

    static DataInputStream input(DataInputStream raw, byte[] key) throws IOException {
        return new DataInputStream(new Input(raw, key));
    }

    static DataOutputStream output(DataOutputStream raw, byte[] key) throws IOException {
        return new DataOutputStream(new Output(raw, key));
    }

    private static Cipher cipher() throws IOException {
        try {
            return Cipher.getInstance("AES/GCM/NoPadding");
        } catch (GeneralSecurityException failure) {
            throw new IOException("this Java has no AES-GCM", failure);
        }
    }

    private static void start(Cipher cipher, int mode, SecretKeySpec key, long record) throws GeneralSecurityException {
        cipher.init(mode, key, new GCMParameterSpec(TAG_BYTES * 8, ByteBuffer.allocate(12).putLong(4, record).array()));
    }

    private static final class Output extends OutputStream {
        private final DataOutputStream raw;
        private final SecretKeySpec key;
        private final Cipher cipher;
        private final byte[] plain = new byte[RECORD];
        private final byte[] sealed = new byte[RECORD + TAG_BYTES];
        private int used;
        private long record;

        Output(DataOutputStream raw, byte[] key) throws IOException {
            this.raw = raw;
            this.key = new SecretKeySpec(key, "AES");
            this.cipher = cipher();
        }

        @Override
        public void write(int value) throws IOException {
            plain[used++] = (byte) value;
            if (used == RECORD) seal();
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            while (length > 0) {
                int part = Math.min(length, RECORD - used);
                System.arraycopy(bytes, offset, plain, used, part);
                used += part;
                offset += part;
                length -= part;
                if (used == RECORD) seal();
            }
        }

        @Override
        public void flush() throws IOException {
            if (used > 0) seal();
            raw.flush();
        }

        private void seal() throws IOException {
            try {
                start(cipher, Cipher.ENCRYPT_MODE, key, record++);
                int length = cipher.doFinal(plain, 0, used, sealed, 0);
                raw.writeInt(length);
                raw.write(sealed, 0, length);
                used = 0;
            } catch (GeneralSecurityException failure) {
                throw new IOException("could not encrypt a message", failure);
            }
        }
    }

    private static final class Input extends InputStream {
        private final DataInputStream raw;
        private final SecretKeySpec key;
        private final Cipher cipher;
        private final byte[] plain = new byte[RECORD];
        private final byte[] sealed = new byte[RECORD + TAG_BYTES];
        private int position;
        private int limit;
        private long record;

        Input(DataInputStream raw, byte[] key) throws IOException {
            this.raw = raw;
            this.key = new SecretKeySpec(key, "AES");
            this.cipher = cipher();
        }

        @Override
        public int read() throws IOException {
            if (position == limit) open();
            return plain[position++] & 0xFF;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (position == limit) open();
            int part = Math.min(length, limit - position);
            System.arraycopy(plain, position, bytes, offset, part);
            position += part;
            return part;
        }

        private void open() throws IOException {
            int length = raw.readInt();
            if (length <= TAG_BYTES || length > RECORD + TAG_BYTES) throw new IOException("bad record length " + length);
            raw.readFully(sealed, 0, length);
            try {
                start(cipher, Cipher.DECRYPT_MODE, key, record++);
                limit = cipher.doFinal(sealed, 0, length, plain, 0);
                position = 0;
            } catch (AEADBadTagException changed) {
                throw new IOException("the connection was changed on the way");
            } catch (GeneralSecurityException failure) {
                throw new IOException("could not decrypt a message", failure);
            }
        }
    }
}
