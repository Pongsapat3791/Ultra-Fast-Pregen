package io.github.pongsapat3791.ufplink;

/** A chunk's position; packed the way Ultra Fast Pregen's API packs it, as Minecraft does. */
final class ChunkPos {
    final int x;
    final int z;

    ChunkPos(int x, int z) {
        this.x = x;
        this.z = z;
    }

    ChunkPos(long packed) {
        this(getX(packed), getZ(packed));
    }

    static long asLong(int x, int z) {
        return (x & 0xFFFFFFFFL) | (z & 0xFFFFFFFFL) << 32;
    }

    static int getX(long packed) {
        return (int) packed;
    }

    static int getZ(long packed) {
        return (int) (packed >>> 32);
    }

    long toLong() {
        return asLong(x, z);
    }

    int getRegionX() {
        return x >> 5;
    }

    int getRegionZ() {
        return z >> 5;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ChunkPos pos && pos.x == x && pos.z == z;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(toLong());
    }

    @Override
    public String toString() {
        return "[" + x + ", " + z + "]";
    }
}
