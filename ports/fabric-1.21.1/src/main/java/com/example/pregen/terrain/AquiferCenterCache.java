package com.example.pregen.terrain;

import net.minecraft.core.BlockPos;

/**
 * Reuses the twelve aquifer centers for a grid cell, including their decoded coordinates.
 * A chunk spans at most two aquifer grid cells on each horizontal axis, so four slots
 * retain every horizontal cell while terrain generation advances through Y.
 * Owned by a single aquifer, just like vanilla's position and fluid caches.
 */
public final class AquiferCenterCache {
    /**
     * Vanilla's calculatePressure, for a block at least this far above both aquifers' fluid
     * levels, gets q = (higherLevel - y - 0.5) / 2.5 <= -2.2: the barrier noise is skipped
     * and the pressure is negative, so computeSubstance yields the first aquifer's air.
     */
    public static final int AIR_MARGIN_ABOVE_FLUID = 5;

    @FunctionalInterface
    public interface LocationResolver {
        long get(int gridX, int gridY, int gridZ);
    }

    @FunctionalInterface
    public interface FluidLevelResolver {
        /** Fluid level of the aquifer whose center is at this packed position. */
        int get(long centerPosition);
    }

    private static final class Cell {
        private final long[] positions = new long[12];
        private final int[] xs = new int[12];
        private final int[] ys = new int[12];
        private final int[] zs = new int[12];
        private int gridX;
        private int gridY;
        private int gridZ;
        private boolean valid;
        private boolean maxFluidLevelKnown;
        private int maxFluidLevel;

        void fill(int x, int y, int z, LocationResolver resolver) {
            int index = 0;
            // Preserve vanilla traversal order: equal distances prefer the later center.
            for (int dx = 0; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = 0; dz <= 1; dz++) {
                        long position = resolver.get(x + dx, y + dy, z + dz);
                        positions[index] = position;
                        xs[index] = BlockPos.getX(position);
                        ys[index] = BlockPos.getY(position);
                        zs[index] = BlockPos.getZ(position);
                        index++;
                    }
                }
            }
            gridX = x;
            gridY = y;
            gridZ = z;
            valid = true;
            maxFluidLevelKnown = false;
        }
    }

    private final LocationResolver resolver;
    private final FluidLevelResolver fluidLevels;
    private final Cell[] cells = {new Cell(), new Cell(), new Cell(), new Cell()};
    public int firstDistance;
    public int secondDistance;
    public int thirdDistance;
    public long firstPosition;
    public long secondPosition;
    public long thirdPosition;

    public AquiferCenterCache(LocationResolver resolver, FluidLevelResolver fluidLevels) {
        this.resolver = resolver;
        this.fluidLevels = fluidLevels;
    }

    private Cell cellFor(int x, int y, int z) {
        int gridX = Math.floorDiv(x - 5, 16);
        int gridY = Math.floorDiv(y + 1, 12);
        int gridZ = Math.floorDiv(z - 5, 16);
        Cell cell = cells[((gridX & 1) << 1) | (gridZ & 1)];
        if (!cell.valid || cell.gridX != gridX || cell.gridY != gridY || cell.gridZ != gridZ) {
            cell.fill(gridX, gridY, gridZ, resolver);
        }
        return cell;
    }

    /**
     * True when y is at least {@link #AIR_MARGIN_ABOVE_FLUID} above the fluid level of every
     * one of the twelve candidate centers, so whichever three are nearest, vanilla yields air.
     */
    public boolean isAboveAllFluid(int x, int y, int z) {
        Cell cell = cellFor(x, y, z);
        if (!cell.maxFluidLevelKnown) {
            int max = Integer.MIN_VALUE;
            for (int index = 0; index < 12; index++) {
                max = Math.max(max, fluidLevels.get(cell.positions[index]));
            }
            cell.maxFluidLevel = max;
            cell.maxFluidLevelKnown = true;
        }
        return (long) y >= (long) cell.maxFluidLevel + AIR_MARGIN_ABOVE_FLUID;
    }

    public void findNearest(int x, int y, int z) {
        Cell cell = cellFor(x, y, z);

        int first = Integer.MAX_VALUE;
        int second = Integer.MAX_VALUE;
        int third = Integer.MAX_VALUE;
        long firstPos = 0;
        long secondPos = 0;
        long thirdPos = 0;
        for (int index = 0; index < 12; index++) {
            int dx = cell.xs[index] - x;
            int dy = cell.ys[index] - y;
            int dz = cell.zs[index] - z;
            int distance = dx * dx + dy * dy + dz * dz;
            long position = cell.positions[index];
            if (first >= distance) {
                thirdPos = secondPos;
                secondPos = firstPos;
                firstPos = position;
                third = second;
                second = first;
                first = distance;
            } else if (second >= distance) {
                thirdPos = secondPos;
                secondPos = position;
                third = second;
                second = distance;
            } else if (third >= distance) {
                thirdPos = position;
                third = distance;
            }
        }
        firstDistance = first;
        secondDistance = second;
        thirdDistance = third;
        firstPosition = firstPos;
        secondPosition = secondPos;
        thirdPosition = thirdPos;
    }
}
