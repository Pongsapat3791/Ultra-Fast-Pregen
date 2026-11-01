package io.github.pongsapat3791.ufpmanager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

/**
 * Draws a region file from above: each block column is the first block the view meets, in the color of its block and
 * biome, with the floor showing through water and a shade from the slope. It reads the chunk format directly, which has
 * been the same since Minecraft 1.18, and only fully generated chunks: at an area's edge the others have no features.
 * A chunk that cannot be read (being written just now, or compressed with LZ4) stays empty.
 */
final class RegionPainter {
    static final int SIDE = 512;
    static final int SMALL = 128;
    private static final int NONE = Short.MIN_VALUE;
    /** In the Nether the view starts below the bedrock roof, in the first open space under this height. */
    private static final int NETHER_START = 112;

    private final int[] colors = new int[SIDE * SIDE];
    private final short[] heights = new short[SIDE * SIDE];
    private final boolean roofed;

    private RegionPainter(boolean roofed) {
        this.roofed = roofed;
        Arrays.fill(heights, (short) NONE);
    }

    /** A picture at one block per pixel and a small one at four blocks per pixel, as PNGs. */
    record Pictures(byte[] full, byte[] small) {}

    static Pictures paint(Path file, String dimension) throws IOException {
        RegionPainter painter = new RegionPainter(dimension.equals("minecraft:the_nether"));
        byte[] region = Files.readAllBytes(file);
        ByteBuffer buffer = ByteBuffer.wrap(region);
        for (int index = 0; index < 1024 && (index + 1) * 4 <= region.length; index++) {
            int location = buffer.getInt(index * 4);
            if (location == 0) continue;
            try {
                byte[] chunk = inflate(region, (location >>> 8) * 4096);
                if (chunk != null) painter.paintChunk(chunk, (index & 31) * 16, (index >> 5) * 16);
            } catch (IOException | RuntimeException | DataFormatException unreadable) {
                // this chunk stays empty
            }
        }
        painter.shade();
        return new Pictures(png(painter.colors, SIDE), png(painter.smaller(), SMALL));
    }

    private static byte[] inflate(byte[] region, int offset) throws IOException, DataFormatException {
        if (offset + 5 > region.length) return null;
        int length = ByteBuffer.wrap(region, offset, 4).getInt();
        int compression = region[offset + 4];
        if (length < 1 || offset + 4 + length > region.length) return null;
        int start = offset + 5;
        int size = length - 1;
        if (compression == 3) return Arrays.copyOfRange(region, start, start + size);
        if (compression == 1) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(region, start, size))) {
                return in.readAllBytes();
            }
        }
        if (compression != 2) return null;
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(region, start, size);
            ByteArrayOutputStream out = new ByteArrayOutputStream(size * 5);
            byte[] block = new byte[64 * 1024];
            while (!inflater.finished()) {
                int count = inflater.inflate(block);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                out.write(block, 0, count);
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    /** One section of a chunk: what the painter needs of it. */
    private static final class Section {
        int y;
        BlockColors.Kind[] palette;
        long[] data;
        String[] biomes;
        long[] biomeData;
        BlockColors.Biome[] biomeKinds;

        boolean empty() {
            return palette == null || palette.length == 1 && palette[0].skip();
        }

        BlockColors.Kind block(int x, int y, int z) {
            if (palette.length == 1 || data == null) return palette[0];
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
            int index = (y & 15) << 8 | z << 4 | x;
            int perLong = 64 / bits;
            int value = (int) (data[index / perLong] >>> (index % perLong * bits) & (1L << bits) - 1);
            return value < palette.length ? palette[value] : palette[0];
        }

        BlockColors.Biome biome(int x, int y, int z) {
            if (biomeKinds == null) {
                if (biomes == null || biomes.length == 0) return BlockColors.biome("plains");
                biomeKinds = new BlockColors.Biome[biomes.length];
                for (int i = 0; i < biomes.length; i++) biomeKinds[i] = BlockColors.biome(biomes[i]);
            }
            if (biomeKinds.length == 1 || biomeData == null) return biomeKinds[0];
            int bits = 32 - Integer.numberOfLeadingZeros(biomeKinds.length - 1);
            int index = ((y & 15) >> 2) << 4 | (z >> 2) << 2 | x >> 2;
            int perLong = 64 / bits;
            int value = (int) (biomeData[index / perLong] >>> (index % perLong * bits) & (1L << bits) - 1);
            return value < biomeKinds.length ? biomeKinds[value] : biomeKinds[0];
        }
    }

    private void paintChunk(byte[] nbt, int left, int top) {
        Nbt reader = new Nbt(nbt);
        if (reader.u1() != 10) return;
        reader.skip(reader.u2());
        Section[] sections = null;
        String status = null;
        int type;
        while ((type = reader.u1()) != 0) {
            String name = reader.string();
            if (type == 9 && name.equals("sections")) {
                sections = reader.sections();
            } else if (type == 8 && name.equals("Status")) {
                status = reader.string();
            } else {
                reader.skipTag(type);
            }
        }
        if (sections == null || status == null || !status.endsWith("full")) return;
        Arrays.sort(sections, (a, b) -> b.y - a.y);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) paintColumn(sections, x, z, left + x + (top + z) * SIDE);
        }
    }

    private void paintColumn(Section[] sections, int x, int z, int pixel) {
        boolean underRoof = roofed;
        BlockColors.Kind rock = null;
        int waterTop = NONE;
        BlockColors.Biome waterBiome = null;
        for (Section section : sections) {
            if (section.y * 16 > (roofed ? NETHER_START : Integer.MAX_VALUE)) continue;
            if (section.empty()) {
                underRoof = false;
                continue;
            }
            for (int local = 15; local >= 0; local--) {
                int y = section.y * 16 + local;
                if (roofed && y > NETHER_START) continue;
                BlockColors.Kind kind = section.block(x, local, z);
                if (underRoof) {
                    if (kind.skip()) underRoof = false;
                    else if (rock == null) rock = kind;
                    continue;
                }
                if (kind.skip()) continue;
                if (kind.water()) {
                    if (waterTop == NONE) {
                        waterTop = y;
                        waterBiome = section.biome(x, local, z);
                    }
                    continue;
                }
                int color = switch (kind.tint()) {
                    case GRASS -> section.biome(x, local, z).grass();
                    case FOLIAGE -> mix(section.biome(x, local, z).foliage(), 0, 0.12);
                    case NONE -> kind.color();
                };
                if (waterTop != NONE) {
                    double depth = Math.min(0.92, 0.5 + (waterTop - y) * 0.035);
                    color = mix(color, waterBiome.water(), depth);
                }
                colors[pixel] = 0xFF000000 | color;
                heights[pixel] = (short) (waterTop != NONE ? waterTop : y);
                return;
            }
        }
        if (waterTop != NONE) {
            colors[pixel] = 0xFF000000 | waterBiome.water();
            heights[pixel] = (short) waterTop;
        } else if (underRoof && rock != null) {
            // Solid rock from the start of the view down to the bottom: the Nether's walls between its caves.
            colors[pixel] = 0xFF000000 | mix(rock.color(), 0, 0.4);
            heights[pixel] = (short) NETHER_START;
        }
    }

    private static int mix(int color, int other, double share) {
        int red = (int) ((color >> 16 & 255) * (1 - share) + (other >> 16 & 255) * share);
        int green = (int) ((color >> 8 & 255) * (1 - share) + (other >> 8 & 255) * share);
        int blue = (int) ((color & 255) * (1 - share) + (other & 255) * share);
        return red << 16 | green << 8 | blue;
    }

    /** Light from the north: a block higher than its north neighbour shows lighter, a lower one darker. */
    private void shade() {
        for (int pixel = SIDE * SIDE - 1; pixel >= SIDE; pixel--) {
            int height = heights[pixel];
            int north = heights[pixel - SIDE];
            if (height == NONE || north == NONE || height == north) continue;
            double factor = 1 + Math.max(-0.28, Math.min(0.22, (height - north) * 0.07));
            int color = colors[pixel];
            int red = Math.min(255, (int) ((color >> 16 & 255) * factor));
            int green = Math.min(255, (int) ((color >> 8 & 255) * factor));
            int blue = Math.min(255, (int) ((color & 255) * factor));
            colors[pixel] = 0xFF000000 | red << 16 | green << 8 | blue;
        }
    }

    private int[] smaller() {
        int step = SIDE / SMALL;
        int[] small = new int[SMALL * SMALL];
        for (int y = 0; y < SMALL; y++) {
            for (int x = 0; x < SMALL; x++) {
                int red = 0;
                int green = 0;
                int blue = 0;
                int count = 0;
                for (int dy = 0; dy < step; dy++) {
                    for (int dx = 0; dx < step; dx++) {
                        int color = colors[x * step + dx + (y * step + dy) * SIDE];
                        if (color >>> 24 == 0) continue;
                        red += color >> 16 & 255;
                        green += color >> 8 & 255;
                        blue += color & 255;
                        count++;
                    }
                }
                if (count * 2 >= step * step) small[x + y * SMALL] = 0xFF000000 | red / count << 16 | green / count << 8 | blue / count;
            }
        }
        return small;
    }

    private static byte[] png(int[] argb, int side) {
        byte[] raw = new byte[(side * 4 + 1) * side];
        int at = 0;
        for (int y = 0; y < side; y++) {
            raw[at++] = 0;
            for (int x = 0; x < side; x++) {
                int color = argb[x + y * side];
                raw[at++] = (byte) (color >> 16);
                raw[at++] = (byte) (color >> 8);
                raw[at++] = (byte) color;
                raw[at++] = (byte) (color >>> 24);
            }
        }
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        ByteArrayOutputStream packed = new ByteArrayOutputStream(raw.length / 3);
        try {
            deflater.setInput(raw);
            deflater.finish();
            byte[] block = new byte[64 * 1024];
            while (!deflater.finished()) packed.write(block, 0, deflater.deflate(block));
        } finally {
            deflater.end();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(packed.size() + 64);
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        chunk(out, "IHDR", ByteBuffer.allocate(13).putInt(side).putInt(side).put((byte) 8).put((byte) 6).array());
        chunk(out, "IDAT", packed.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String tag, byte[] data) {
        byte[] name = tag.getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(name);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    /** Named binary tags, read in place: only sections are kept, and everything else is stepped over. */
    private static final class Nbt {
        private final byte[] data;
        private int at;

        Nbt(byte[] data) {
            this.data = data;
        }

        int u1() {
            return data[at++] & 255;
        }

        int u2() {
            return u1() << 8 | u1();
        }

        int i4() {
            return u2() << 16 | u2();
        }

        void skip(int bytes) {
            at += bytes;
        }

        String string() {
            int length = u2();
            String text = new String(data, at, length, StandardCharsets.UTF_8);
            at += length;
            return text;
        }

        long[] longs() {
            int count = i4();
            long[] values = new long[count];
            ByteBuffer.wrap(data, at, count * 8).asLongBuffer().get(values);
            at += count * 8;
            return values;
        }

        void skipTag(int type) {
            switch (type) {
                case 1 -> at += 1;
                case 2 -> at += 2;
                case 3, 5 -> at += 4;
                case 4, 6 -> at += 8;
                case 7 -> skip(i4());
                case 8 -> skip(u2());
                case 9 -> {
                    int element = u1();
                    int count = i4();
                    for (int i = 0; i < count; i++) skipTag(element);
                }
                case 10 -> {
                    int inner;
                    while ((inner = u1()) != 0) {
                        skip(u2());
                        skipTag(inner);
                    }
                }
                case 11 -> skip(i4() * 4);
                case 12 -> skip(i4() * 8);
                default -> throw new IllegalStateException("bad tag " + type);
            }
        }

        Section[] sections() {
            int element = u1();
            int count = i4();
            if (element != 10) {
                for (int i = 0; i < count; i++) skipTag(element);
                return new Section[0];
            }
            Section[] sections = new Section[count];
            for (int i = 0; i < count; i++) sections[i] = section();
            return sections;
        }

        private Section section() {
            Section section = new Section();
            int type;
            while ((type = u1()) != 0) {
                String name = string();
                if (name.equals("Y") && type == 1) {
                    section.y = data[at++];
                } else if (name.equals("block_states") && type == 10) {
                    blockStates(section);
                } else if (name.equals("biomes") && type == 10) {
                    biomes(section);
                } else {
                    skipTag(type);
                }
            }
            return section;
        }

        private void blockStates(Section section) {
            int type;
            while ((type = u1()) != 0) {
                String name = string();
                if (name.equals("palette") && type == 9) {
                    int element = u1();
                    int count = i4();
                    section.palette = new BlockColors.Kind[count];
                    for (int i = 0; i < count; i++) section.palette[i] = BlockColors.block(blockName());
                    if (count > 0 && element != 10) throw new IllegalStateException("bad palette");
                } else if (name.equals("data") && type == 12) {
                    section.data = longs();
                } else {
                    skipTag(type);
                }
            }
        }

        private String blockName() {
            String name = "air";
            int type;
            while ((type = u1()) != 0) {
                String key = string();
                if (key.equals("Name") && type == 8) name = string();
                else skipTag(type);
            }
            return name;
        }

        private void biomes(Section section) {
            int type;
            while ((type = u1()) != 0) {
                String name = string();
                if (name.equals("palette") && type == 9) {
                    u1();
                    int count = i4();
                    section.biomes = new String[count];
                    for (int i = 0; i < count; i++) section.biomes[i] = string();
                } else if (name.equals("data") && type == 12) {
                    section.biomeData = longs();
                } else {
                    skipTag(type);
                }
            }
        }
    }
}
