package com.example.pregen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * Reading saved data (NBT), which Minecraft 1.21.5 changed: a getter there answers with what is given for a missing
 * key, older versions with a zero or an empty value and a second call to ask whether the key is there. This is the
 * family's copy (1.21.5 to 26.x); ports/neoforge-1.21.4 has the one for 1.21.2 to 1.21.4. See Mc.
 */
public final class McNbt {
    private McNbt() {}

    public static String string(CompoundTag tag, String key, String or) {
        return tag.getStringOr(key, or);
    }

    public static int intOr(CompoundTag tag, String key, int or) {
        return tag.getIntOr(key, or);
    }

    public static long longOr(CompoundTag tag, String key, long or) {
        return tag.getLongOr(key, or);
    }

    public static byte byteOr(CompoundTag tag, String key, byte or) {
        return tag.getByteOr(key, or);
    }

    public static boolean boolOr(CompoundTag tag, String key, boolean or) {
        return tag.getBooleanOr(key, or);
    }

    /** The compound under the key, or an empty one. */
    public static CompoundTag compound(CompoundTag tag, String key) {
        return tag.getCompoundOrEmpty(key);
    }

    public static CompoundTag compound(ListTag list, int index) {
        return list.getCompoundOrEmpty(index);
    }

    /** The list of compounds under the key, or an empty list. */
    public static ListTag compounds(CompoundTag tag, String key) {
        return tag.getListOrEmpty(key);
    }

    /** The byte array under the key, or null. */
    public static byte[] bytes(CompoundTag tag, String key) {
        return tag.getByteArray(key).orElse(null);
    }

    /** The long array under the key, or an empty one. */
    public static long[] longs(CompoundTag tag, String key) {
        return tag.getLongArray(key).orElse(new long[0]);
    }
}
