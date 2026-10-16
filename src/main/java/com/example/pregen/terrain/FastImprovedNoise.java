package com.example.pregen.terrain;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/** Immutable mirrored permutation table; all floating-point operations match vanilla. */
public final class FastImprovedNoise {
    private static final int[][] GRADIENT = VanillaGradients.TABLE;

    /** SimplexNoise.GRADIENT is protected; read it through a subclass so the values cannot drift. */
    private static final class VanillaGradients extends SimplexNoise {
        static final int[][] TABLE = GRADIENT;

        private VanillaGradients() {
            super(null);
        }
    }

    private final int[] permutation = new int[512];
    private final double xo;
    private final double yo;
    private final double zo;

    public FastImprovedNoise(byte[] original, double xo, double yo, double zo) {
        this.xo = xo;
        this.yo = yo;
        this.zo = zo;
        for (int i = 0; i < 256; i++) {
            permutation[i] = permutation[i + 256] = original[i] & 255;
        }
    }

    public double noise(double x, double y, double z) {
        return noise(x, y, z, 0.0, 0.0);
    }

    public double noise(double x, double y, double z, double yScale, double yMax) {
        double shiftedX = x + xo;
        double shiftedY = y + yo;
        double shiftedZ = z + zo;
        int cellX = Mth.floor(shiftedX);
        int cellY = Mth.floor(shiftedY);
        int cellZ = Mth.floor(shiftedZ);
        double localX = shiftedX - cellX;
        double localY = shiftedY - cellY;
        double localZ = shiftedZ - cellZ;
        double yOffset;
        if (yScale != 0.0) {
            double limitedY = yMax >= 0.0 && yMax < localY ? yMax : localY;
            yOffset = Mth.floor(limitedY / yScale + 1.0E-7F) * yScale;
        } else {
            yOffset = 0.0;
        }
        return sample(cellX, cellY, cellZ, localX, localY - yOffset, localZ, localY);
    }

    private double sample(int cellX, int cellY, int cellZ, double x, double y, double z, double originalY) {
        int[] p = permutation;
        int ix = cellX & 255;
        int iy = cellY & 255;
        int iz = cellZ & 255;
        int px0 = p[ix];
        int px1 = p[ix + 1];
        int p00 = p[px0 + iy];
        int p01 = p[px0 + iy + 1];
        int p10 = p[px1 + iy];
        int p11 = p[px1 + iy + 1];
        double v000 = dot(p[p00 + iz], x, y, z);
        double v100 = dot(p[p10 + iz], x - 1.0, y, z);
        double v010 = dot(p[p01 + iz], x, y - 1.0, z);
        double v110 = dot(p[p11 + iz], x - 1.0, y - 1.0, z);
        double v001 = dot(p[p00 + iz + 1], x, y, z - 1.0);
        double v101 = dot(p[p10 + iz + 1], x - 1.0, y, z - 1.0);
        double v011 = dot(p[p01 + iz + 1], x, y - 1.0, z - 1.0);
        double v111 = dot(p[p11 + iz + 1], x - 1.0, y - 1.0, z - 1.0);
        return Mth.lerp3(Mth.smoothstep(x), Mth.smoothstep(originalY), Mth.smoothstep(z),
                v000, v100, v010, v110, v001, v101, v011, v111);
    }

    private static double dot(int hash, double x, double y, double z) {
        int[] gradient = GRADIENT[hash & 15];
        return gradient[0] * x + gradient[1] * y + gradient[2] * z;
    }
}
