package com.example.pregen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Reading saved data (NBT) on Minecraft 1.21.2 to 1.21.4, where a getter answers a missing key with a zero or an
 * empty value: the copy of the family's McNbt (ports/neoforge-26.1.2) for those versions.
 */
public final class McNbt {
    private McNbt() {}

    public static String string(CompoundTag tag, String key, String or) {
        return tag.contains(key, Tag.TAG_STRING) ? tag.getString(key) : or;
    }

    public static int intOr(CompoundTag tag, String key, int or) {
        return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getInt(key) : or;
    }

    public static long longOr(CompoundTag tag, String key, long or) {
        return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getLong(key) : or;
    }

    public static byte byteOr(CompoundTag tag, String key, byte or) {
        return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getByte(key) : or;
    }

    public static boolean boolOr(CompoundTag tag, String key, boolean or) {
        return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getBoolean(key) : or;
    }

    /** The compound under the key, or an empty one. */
    public static CompoundTag compound(CompoundTag tag, String key) {
        return tag.getCompound(key);
    }

    public static CompoundTag compound(ListTag list, int index) {
        return list.getCompound(index);
    }

    /** The list of compounds under the key, or an empty list. */
    public static ListTag compounds(CompoundTag tag, String key) {
        return tag.getList(key, Tag.TAG_COMPOUND);
    }

    /** The byte array under the key, or null. */
    public static byte[] bytes(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_BYTE_ARRAY) ? tag.getByteArray(key) : null;
    }

    /** The long array under the key, or an empty one. */
    public static long[] longs(CompoundTag tag, String key) {
        return tag.getLongArray(key);
    }
}
