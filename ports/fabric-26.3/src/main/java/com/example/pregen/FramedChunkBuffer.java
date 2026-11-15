package com.example.pregen;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Mirrors RegionFile's internal ChunkBuffer framing so a chunk compressed on a worker thread
 * is byte-identical to what RegionFile#getChunkDataOutputStream would have produced:
 * [int length = compressed bytes + 1][compression id][compressed NBT].
 */
final class FramedChunkBuffer extends ByteArrayOutputStream {
    private static final int HEADER_BYTES = 5;

    FramedChunkBuffer(int compressionId) {
        super(16 * 1024);
        write(0);
        write(0);
        write(0);
        write(0);
        write(compressionId);
    }

    ByteBuffer frame() {
        ByteBuffer buffer = ByteBuffer.wrap(buf, 0, count);
        buffer.putInt(0, count - HEADER_BYTES + 1);
        return buffer;
    }
}
