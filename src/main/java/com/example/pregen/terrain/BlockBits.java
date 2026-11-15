package com.example.pregen.terrain;

import com.example.pregen.WorldgenCompat;
import com.example.pregen.mixin.LevelChunkSectionAccessor;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.SingleValuePalette;

/**
 * Runs of a section's blocks written at once. While terrain is made a section holds a handful of
 * block states, so Minecraft keeps it as a palette of up to 16 states and 4 bits a block, 16
 * blocks (one row along x) to a long. Two steps write the same block over and over through the
 * section, a block at a time: the terrain fill gives every block of a solid noise cell the
 * default block, and the surface step below the ground turns a column's stone into deepslate
 * (see DeepSurface). Here those runs go into the longs directly: a cell is 32 masked writes
 * instead of 128 block writes.
 * The first block of a run still goes through Minecraft's own write: it grows the palette as
 * Minecraft does, and what it adds to the section's block counters is what every other block of
 * the run adds too. Anything else than a 4-bit palette of Minecraft's own classes is left to the
 * caller's block by block writes.
 */
public final class BlockBits {
    /** Benchmark switch, on unless -Dfastpregen.blockBits=false. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("fastpregen.blockBits", "true"));
    /** Blocks written into the longs by fill and by replace, and runs left to the caller. */
    public static final LongAdder FILLED = new LongAdder();
    public static final LongAdder REPLACED = new LongAdder();
    public static final LongAdder LEFT = new LongAdder();
    private static final long EVERY_NIBBLE = 0x1111111111111111L;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** PalettedContainer's current data, or null where it cannot be read. */
    private static final MethodHandle DATA = dataGetter();

    private BlockBits() {}

    /** PalettedContainer's storage and palette (PalettedDataMixin). */
    public interface Data {
        BitStorage pregen$storage();

        Palette<?> pregen$palette();
    }

    /** The class of a container's data record (not public; PalettedDataMixin gives it the Data interface), or null. */
    public static Class<?> dataClass() {
        try {
            for (Field field : PalettedContainer.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && Data.class.isAssignableFrom(field.getType())) return field.getType();
            }
        } catch (Throwable unreadable) {
            // Not found: the callers do without.
        }
        return null;
    }

    private static MethodHandle dataGetter() {
        try {
            for (Field field : PalettedContainer.class.getDeclaredFields()) {
                // The one field holding the container's data record, whatever the mappings call it.
                if (Modifier.isStatic(field.getModifiers()) || !Data.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                return MethodHandles.lookup().unreflectGetter(field)
                        .asType(MethodType.methodType(Data.class, PalettedContainer.class));
            }
        } catch (Throwable unreadable) {
            // Block by block writes then.
        }
        return null;
    }

    private static boolean usable() {
        return enabled && DATA != null && WorldgenCompat.blockBitsAllowed();
    }

    private static Data data(PalettedContainer<BlockState> states) {
        try {
            return (Data) DATA.invokeExact(states);
        } catch (Throwable unreadable) {
            return null;
        }
    }

    /**
     * Whether two containers are known to hold the same block at every place, told from their storage
     * without reading the places: the same kind of storage with the same bits a block and the same
     * longs, and every id of the first one's palette meaning the same block in the second's. A palette
     * only gains ids while its storage stays; one that was rebuilt has other longs, and the answer is
     * then false, which only means "look block by block". Reads only, so it is not behind the switch.
     */
    public static boolean sameContents(PalettedContainer<BlockState> first, PalettedContainer<BlockState> second) {
        if (DATA == null) return false;
        Data a = data(first);
        Data b = data(second);
        if (a == null || b == null) return false;
        BitStorage storageA = a.pregen$storage();
        BitStorage storageB = b.pregen$storage();
        if (storageA.getClass() != storageB.getClass() || storageA.getBits() != storageB.getBits()
                || storageA.getSize() != storageB.getSize()) {
            return false;
        }
        Palette<?> paletteA = a.pregen$palette();
        Palette<?> paletteB = b.pregen$palette();
        if (paletteA.getClass() != paletteB.getClass()) return false;
        if (!java.util.Arrays.equals(storageA.getRaw(), storageB.getRaw())) return false;
        // The palette of every block state there is gives each id the same block in any container.
        if (paletteA.getClass() == net.minecraft.world.level.chunk.GlobalPalette.class) return true;
        int ids = paletteA.getSize();
        if (ids > paletteB.getSize()) return false;
        for (int id = 0; id < ids; id++) {
            if (paletteA.valueFor(id) != paletteB.valueFor(id)) return false;
        }
        return true;
    }

    /** The 4-bit longs of the data, or null when it is anything else than a linear palette over 4 bits a block. */
    private static long[] nibbles(Data data) {
        if (data == null || data.pregen$palette().getClass() != LinearPalette.class) return null;
        BitStorage storage = data.pregen$storage();
        if (storage.getClass() != SimpleBitStorage.class || storage.getBits() != 4 || storage.getSize() != 4096) return null;
        return storage.getRaw();
    }

    private static int idOf(Palette<?> palette, BlockState state) {
        for (int id = palette.getSize() - 1; id >= 0; id--) {
            if (palette.valueFor(id) == state) return id;
        }
        return -1;
    }

    /**
     * Gives the box of blocks at (x0, y0, z0) of the section, {@code width} along x and z and
     * {@code height} along y, the state, as LevelChunkSection#setBlockState does block by block,
     * when every block of the box is air now. False when nothing was written (the caller writes).
     */
    public static boolean fillAir(LevelChunkSection section, int x0, int y0, int z0, int width, int height, BlockState state) {
        if (!usable() || state == AIR || !state.getFluidState().isEmpty() || width <= 0 || height <= 0
                || x0 < 0 || z0 < 0 || y0 < 0 || x0 + width > 16 || z0 + width > 16 || y0 + height > 16) {
            return false;
        }
        PalettedContainer<BlockState> states = section.getStates();
        Data data = data(states);
        if (data == null) return false;
        long boxMask = (width == 16 ? -1L : (1L << 4 * width) - 1L) << 4 * x0;
        Palette<?> palette = data.pregen$palette();
        if (palette.getClass() == SingleValuePalette.class) {
            if (palette.getSize() != 1 || palette.valueFor(0) != AIR) return false;
        } else {
            long[] raw = nibbles(data);
            int air = raw == null ? -1 : idOf(palette, AIR);
            if (air < 0) {
                LEFT.increment();
                return false;
            }
            long airBits = air * EVERY_NIBBLE & boxMask;
            for (int y = y0; y < y0 + height; y++) {
                for (int z = z0; z < z0 + width; z++) {
                    if ((raw[y << 4 | z] & boxMask) != airBits) {
                        LEFT.increment();
                        return false;
                    }
                }
            }
        }
        // The first block as Minecraft writes it: the palette grows its way, and the counters show what one block adds.
        LevelChunkSectionAccessor counts = (LevelChunkSectionAccessor) section;
        short blocks = counts.pregen$getNonEmptyBlockCount();
        short ticking = counts.pregen$getTickingBlockCount();
        section.setBlockState(x0, y0, z0, state, false);
        int others = width * width * height - 1;
        data = data(states);
        long[] raw = nibbles(data);
        int id = raw == null ? -1 : idOf(data.pregen$palette(), state);
        if (id < 0) {
            // Not a 4-bit palette after all (the 17th state of the section): the rest block by block here.
            for (int y = y0; y < y0 + height; y++) {
                for (int z = z0; z < z0 + width; z++) {
                    for (int x = x0; x < x0 + width; x++) {
                        if (x != x0 || y != y0 || z != z0) section.setBlockState(x, y, z, state, false);
                    }
                }
            }
            LEFT.increment();
            return true;
        }
        long stateBits = id * EVERY_NIBBLE & boxMask;
        for (int y = y0; y < y0 + height; y++) {
            for (int z = z0; z < z0 + width; z++) {
                int at = y << 4 | z;
                raw[at] = raw[at] & ~boxMask | stateBits;
            }
        }
        counts.pregen$setNonEmptyBlockCount((short) (counts.pregen$getNonEmptyBlockCount()
                + (counts.pregen$getNonEmptyBlockCount() - blocks) * others));
        counts.pregen$setTickingBlockCount((short) (counts.pregen$getTickingBlockCount()
                + (counts.pregen$getTickingBlockCount() - ticking) * others));
        FILLED.add(others + 1);
        return true;
    }

    /**
     * Turns every {@code from} block of the column (x, z) of the section between minY and maxY
     * (0 to 15, inclusive) into {@code to}, as LevelChunkSection#setBlockState does block by
     * block. Returns how many blocks changed, or -1 when nothing was written (the caller writes).
     * Neither state may hold a fluid.
     */
    public static int replaceColumn(LevelChunkSection section, int x, int z, int minY, int maxY, BlockState from, BlockState to) {
        if (!usable() || from == to || !from.getFluidState().isEmpty() || !to.getFluidState().isEmpty()) return -1;
        PalettedContainer<BlockState> states = section.getStates();
        Data data = data(states);
        long[] raw = nibbles(data);
        if (raw == null) {
            LEFT.increment();
            return -1;
        }
        int fromId = idOf(data.pregen$palette(), from);
        if (fromId < 0) return 0;
        int shift = x << 2;
        int first = -1;
        for (int y = minY; y <= maxY; y++) {
            if ((int) (raw[y << 4 | z] >>> shift & 15L) == fromId) {
                first = y;
                break;
            }
        }
        if (first < 0) return 0;
        // The first one as Minecraft writes it (see fillAir).
        LevelChunkSectionAccessor counts = (LevelChunkSectionAccessor) section;
        short blocks = counts.pregen$getNonEmptyBlockCount();
        short ticking = counts.pregen$getTickingBlockCount();
        section.setBlockState(x, first, z, to, false);
        int blocksEach = counts.pregen$getNonEmptyBlockCount() - blocks;
        int tickingEach = counts.pregen$getTickingBlockCount() - ticking;
        data = data(states);
        raw = nibbles(data);
        int toId = raw == null ? -1 : idOf(data.pregen$palette(), to);
        fromId = raw == null ? -1 : idOf(data.pregen$palette(), from);
        int changed = 1;
        if (toId < 0 || fromId < 0) {
            for (int y = first + 1; y <= maxY; y++) {
                if (section.getBlockState(x, y, z) == from) {
                    section.setBlockState(x, y, z, to, false);
                    changed++;
                }
            }
            LEFT.increment();
            return changed;
        }
        long clear = ~(15L << shift);
        long toBits = (long) toId << shift;
        for (int y = first + 1; y <= maxY; y++) {
            int at = y << 4 | z;
            long row = raw[at];
            if ((int) (row >>> shift & 15L) == fromId) {
                raw[at] = row & clear | toBits;
                changed++;
            }
        }
        if (changed > 1) {
            counts.pregen$setNonEmptyBlockCount((short) (counts.pregen$getNonEmptyBlockCount() + blocksEach * (changed - 1)));
            counts.pregen$setTickingBlockCount((short) (counts.pregen$getTickingBlockCount() + tickingEach * (changed - 1)));
        }
        REPLACED.add(changed);
        return changed;
    }
}
