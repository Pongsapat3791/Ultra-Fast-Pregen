package io.github.pongsapat3791.ufplink.region;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an Anvil region file (r.X.Z.mca) without changing it: the world it belongs to may still be
 * open in another server, and the import only copies from it. Uses no Minecraft classes, so the
 * offline merge tool shares it. Chunk positions are absolute chunk coordinates.
 */
public final class RegionReader implements AutoCloseable {
    public static final int SECTOR = 4096;
    /** Set on the compression id of a chunk kept in its own c.X.Z.mcc file (more than 1 MiB). */
    public static final int EXTERNAL_FLAG = 128;
    private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private final Path file;
    private final FileChannel channel;
    private final int regionX;
    private final int regionZ;
    private final int[] offsets = new int[1024];

    private RegionReader(Path file, FileChannel channel, int regionX, int regionZ) {
        this.file = file;
        this.channel = channel;
        this.regionX = regionX;
        this.regionZ = regionZ;
    }

    /** Region coordinates from a file name, or null when it is not r.X.Z.mca. */
    public static int[] regionOf(Path file) {
        Matcher matcher = NAME.matcher(file.getFileName().toString());
        return matcher.matches() ? new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))} : null;
    }

    public static RegionReader open(Path file) throws IOException {
        int[] region = regionOf(file);
        if (region == null) throw new IOException("Not a region file name: " + file);
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        RegionReader reader = new RegionReader(file, channel, region[0], region[1]);
        try {
            reader.readHeader();
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
        return reader;
    }

    private void readHeader() throws IOException {
        if (channel.size() < SECTOR) return;  // empty or cut short: no chunks
        ByteBuffer header = ByteBuffer.allocate(SECTOR);
        readFully(header, 0);
        header.flip();
        for (int i = 0; i < 1024; i++) offsets[i] = header.getInt();
    }

    public int regionX() {
        return regionX;
    }

    public int regionZ() {
        return regionZ;
    }

    private static int index(int chunkX, int chunkZ) {
        return (chunkX & 31) + (chunkZ & 31) * 32;
    }

    private boolean inRegion(int chunkX, int chunkZ) {
        return chunkX >> 5 == regionX && chunkZ >> 5 == regionZ;
    }

    /** Whether the header names data for the chunk. */
    public boolean has(int chunkX, int chunkZ) {
        if (!inRegion(chunkX, chunkZ)) return false;
        int packed = offsets[index(chunkX, chunkZ)];
        return packed >>> 8 >= 2 && (packed & 0xFF) > 0;
    }

    /** The chunks the header names, as {chunkX, chunkZ}, in the region's index order. */
    public List<int[]> chunks() {
        List<int[]> chunks = new ArrayList<>();
        for (int i = 0; i < 1024; i++) {
            int chunkX = regionX * 32 + (i & 31);
            int chunkZ = regionZ * 32 + (i >> 5);
            if (has(chunkX, chunkZ)) chunks.add(new int[]{chunkX, chunkZ});
        }
        return chunks;
    }

    /**
     * The chunk as a region frame: [length][compression id][compressed NBT], ready to be written
     * into another region file. A chunk kept in its own .mcc file comes back as an ordinary frame
     * holding that file's data. Null when the header names no data for it.
     */
    public ByteBuffer frame(int chunkX, int chunkZ) throws IOException {
        if (!has(chunkX, chunkZ)) return null;
        int packed = offsets[index(chunkX, chunkZ)];
        long start = (long) (packed >>> 8) * SECTOR;
        int sectors = packed & 0xFF;
        ByteBuffer head = ByteBuffer.allocate(5);
        readFully(head, start);
        head.flip();
        int length = head.getInt();
        int compression = Byte.toUnsignedInt(head.get());
        if (length <= 0 || length > sectors * SECTOR - 4) {
            throw new IOException("Chunk [" + chunkX + ", " + chunkZ + "] in " + file.getFileName() + " has a bad length " + length);
        }
        if ((compression & EXTERNAL_FLAG) != 0) {
            Path external = file.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
            byte[] data = Files.readAllBytes(external);
            ByteBuffer frame = ByteBuffer.allocate(5 + data.length);
            frame.putInt(1 + data.length).put((byte) (compression & ~EXTERNAL_FLAG)).put(data).flip();
            return frame;
        }
        ByteBuffer frame = ByteBuffer.allocate(4 + length);
        readFully(frame, start);
        frame.flip();
        return frame;
    }

    private void readFully(ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) throw new IOException("Unexpected end of " + file.getFileName());
        }
    }

    /** The compression id of a frame (1 gzip, 2 zlib, 3 none, 4 LZ4, 127 custom). */
    public static int compression(ByteBuffer frame) {
        return Byte.toUnsignedInt(frame.get(frame.position() + 4));
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
