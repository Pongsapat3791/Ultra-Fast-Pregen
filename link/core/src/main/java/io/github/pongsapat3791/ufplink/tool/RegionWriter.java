package io.github.pongsapat3791.ufplink.tool;

import io.github.pongsapat3791.ufplink.region.RegionReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes chunks into a region file of a world no server has open, without Minecraft. Works on a
 * copy (r.X.Z.mca.ufplink-tmp) that replaces the file only in commit, so a merge stopped half-way
 * leaves every region file either as it was or complete. New chunk data is appended after the
 * last used sector; the space of a replaced chunk is left unused (Minecraft reuses it later).
 * Opened with {@link #inspect} it only reads the file, and writes do nothing (for a check).
 */
public final class RegionWriter implements AutoCloseable {
    private static final int SECTOR = RegionReader.SECTOR;

    private final Path file;
    /** The copy being written, or null when only inspecting. */
    private final Path temp;
    /** Null when inspecting a file that does not exist. */
    private final FileChannel channel;
    private final int regionX;
    private final int regionZ;
    private final int[] offsets = new int[1024];
    private final int[] timestamps = new int[1024];
    private final List<Path[]> externalMoves = new ArrayList<>();
    private final List<Path> externalDeletes = new ArrayList<>();
    private long nextSector;
    private boolean changed;
    private boolean done;

    private RegionWriter(Path file, Path temp, FileChannel channel, int regionX, int regionZ) {
        this.file = file;
        this.temp = temp;
        this.channel = channel;
        this.regionX = regionX;
        this.regionZ = regionZ;
    }

    private static int[] region(Path file) throws IOException {
        int[] region = RegionReader.regionOf(file);
        if (region == null) throw new IOException("Not a region file name: " + file);
        return region;
    }

    /** Opens the region file (a new one when missing) for writing through a copy. */
    public static RegionWriter open(Path file) throws IOException {
        int[] region = region(file);
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".ufplink-tmp");
        if (Files.isRegularFile(file)) Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING);
        else Files.deleteIfExists(temp);
        FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        return init(new RegionWriter(file, temp, channel, region[0], region[1]));
    }

    /** Opens the region file only to read it (empty when missing); writes do nothing. */
    static RegionWriter inspect(Path file) throws IOException {
        int[] region = region(file);
        FileChannel channel = Files.isRegularFile(file) ? FileChannel.open(file, StandardOpenOption.READ) : null;
        return init(new RegionWriter(file, null, channel, region[0], region[1]));
    }

    private static RegionWriter init(RegionWriter writer) throws IOException {
        try {
            writer.readHeader();
        } catch (IOException | RuntimeException failure) {
            writer.abort();
            throw failure;
        }
        return writer;
    }

    private void readHeader() throws IOException {
        long size = channel == null ? 0 : channel.size();
        if (size >= 2L * SECTOR) {
            ByteBuffer header = ByteBuffer.allocate(2 * SECTOR);
            read(header, 0);
            header.flip();
            for (int i = 0; i < 1024; i++) offsets[i] = header.getInt();
            for (int i = 0; i < 1024; i++) timestamps[i] = header.getInt();
        }
        long used = 2;
        for (int packed : offsets) {
            if (packed >>> 8 >= 2) used = Math.max(used, (packed >>> 8) + (packed & 0xFF));
        }
        nextSector = Math.max(used, (size + SECTOR - 1) / SECTOR);
    }

    private int index(int chunkX, int chunkZ) {
        if (chunkX >> 5 != regionX || chunkZ >> 5 != regionZ) throw new IllegalArgumentException("chunk outside the region");
        return (chunkX & 31) + (chunkZ & 31) * 32;
    }

    boolean has(int chunkX, int chunkZ) {
        int packed = offsets[index(chunkX, chunkZ)];
        return packed >>> 8 >= 2 && (packed & 0xFF) > 0;
    }

    /** The stored chunk's frame (external data folded in), or null. */
    ByteBuffer frame(int chunkX, int chunkZ) throws IOException {
        if (!has(chunkX, chunkZ)) return null;
        int packed = offsets[index(chunkX, chunkZ)];
        ByteBuffer head = ByteBuffer.allocate(5);
        read(head, (long) (packed >>> 8) * SECTOR);
        head.flip();
        int length = head.getInt();
        int compression = Byte.toUnsignedInt(head.get());
        if (length <= 0 || length > (packed & 0xFF) * SECTOR - 4) throw new IOException("bad stored length " + length);
        if ((compression & RegionReader.EXTERNAL_FLAG) != 0) {
            byte[] data = Files.readAllBytes(externalFile(chunkX, chunkZ));
            ByteBuffer frame = ByteBuffer.allocate(5 + data.length);
            frame.putInt(1 + data.length).put((byte) (compression & ~RegionReader.EXTERNAL_FLAG)).put(data).flip();
            return frame;
        }
        ByteBuffer frame = ByteBuffer.allocate(4 + length);
        read(frame, (long) (packed >>> 8) * SECTOR);
        frame.flip();
        return frame;
    }

    private void read(ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) throw new IOException("unexpected end of " + file.getFileName());
        }
    }

    private Path externalFile(int chunkX, int chunkZ) {
        return file.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
    }

    /** Stores the frame ([length][compression id][payload]) as the chunk, replacing what the copy had there. */
    public void write(int chunkX, int chunkZ, ByteBuffer frame) throws IOException {
        int index = index(chunkX, chunkZ);
        if (temp == null) return;
        ByteBuffer data = frame.duplicate();
        int sectors = (data.remaining() + SECTOR - 1) / SECTOR;
        Path external = externalFile(chunkX, chunkZ);
        if (sectors >= 256) {
            // Like Minecraft: the payload goes to its own file and the region keeps a one-sector stub.
            ByteBuffer payload = data.duplicate();
            payload.position(payload.position() + 4);
            int compression = Byte.toUnsignedInt(payload.get());
            Path externalTemp = external.resolveSibling(external.getFileName() + ".ufplink-tmp");
            try (FileChannel out = FileChannel.open(externalTemp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                while (payload.hasRemaining()) out.write(payload);
                out.force(true);
            }
            externalMoves.add(new Path[]{externalTemp, external});
            externalDeletes.remove(external);
            data = ByteBuffer.allocate(5).putInt(1).put((byte) (compression | RegionReader.EXTERNAL_FLAG)).flip();
            sectors = 1;
        } else if (Files.exists(external)) {
            externalDeletes.add(external);
        }
        long position = nextSector * SECTOR;
        ByteBuffer padded = ByteBuffer.allocate(sectors * SECTOR);
        padded.put(data).position(0);
        while (padded.hasRemaining()) channel.write(padded, position + padded.position());
        offsets[index] = (int) (nextSector << 8) | sectors;
        timestamps[index] = (int) (System.currentTimeMillis() / 1000L);
        nextSector += sectors;
        changed = true;
    }

    /** Removes the chunk from the copy. */
    void remove(int chunkX, int chunkZ) {
        int index = index(chunkX, chunkZ);
        if (temp == null || offsets[index] == 0) return;
        offsets[index] = 0;
        timestamps[index] = 0;
        Path external = externalFile(chunkX, chunkZ);
        if (Files.exists(external)) externalDeletes.add(external);
        changed = true;
    }

    /** Writes the header, forces the copy to disk and puts it in place of the region file. */
    public void commit() throws IOException {
        if (done) return;
        if (temp == null || !changed) {
            abort();
            return;
        }
        done = true;
        ByteBuffer header = ByteBuffer.allocate(2 * SECTOR);
        for (int packed : offsets) header.putInt(packed);
        for (int time : timestamps) header.putInt(time);
        header.flip();
        while (header.hasRemaining()) channel.write(header, header.position());
        channel.force(true);
        channel.close();
        for (Path[] move : externalMoves) Files.move(move[0], move[1], StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        for (Path stale : externalDeletes) Files.deleteIfExists(stale);
    }

    /** Leaves the region file as it was. */
    void abort() throws IOException {
        if (done) return;
        done = true;
        if (channel != null) channel.close();
        if (temp == null) return;
        Files.deleteIfExists(temp);
        for (Path[] move : externalMoves) Files.deleteIfExists(move[0]);
    }

    @Override
    public void close() throws IOException {
        abort();
    }
}
