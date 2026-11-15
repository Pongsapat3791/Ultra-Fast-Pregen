package com.example.pregen;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

/**
 * Mirrors RegionFile's internal ChunkBuffer framing so a chunk compressed on a worker thread
 * is byte-identical to what RegionFile#getChunkDataOutputStream would have produced:
 * [int length = compressed bytes + 1][compression id][compressed NBT].
 */
final class FramedChunkBuffer extends ByteArrayOutputStream {
    private static final int HEADER_BYTES = 5;
    /**
     * The zlib level of the chunks a run saves, 1 (fastest, largest) to 9; 0 leaves Minecraft's own stream and
     * level (6). A measurement switch (-Dfastpregen.compressionLevel): any level gives the same chunk when read,
     * in the same zlib frame every Minecraft reads.
     */
    static final int LEVEL = Math.max(0, Math.min(9, Integer.getInteger("fastpregen.compressionLevel", 0)));

    /** The stream a chunk's NBT goes through into a zlib frame: Minecraft's own, or one at the level asked for. */
    static OutputStream zlib(OutputStream frame) throws IOException {
        if (LEVEL == 0) return RegionFileVersion.VERSION_DEFLATE.wrap(frame);
        Deflater deflater = new Deflater(LEVEL);
        // As Minecraft's stream is built (a buffer in front of the deflater); a deflater given from outside is
        // not ended by the stream.
        return new BufferedOutputStream(new DeflaterOutputStream(frame, deflater) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    deflater.end();
                }
            }
        });
    }

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
