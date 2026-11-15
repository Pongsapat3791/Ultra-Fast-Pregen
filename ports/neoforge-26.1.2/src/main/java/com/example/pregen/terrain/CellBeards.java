package com.example.pregen.terrain;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

/**
 * A chunk's structure pieces by the cells of its noise they reach. Near a structure that shapes
 * the terrain (a village, an ancient city, a stronghold) Minecraft adds, to the density of every
 * block of a cell generated block by block, a term from every piece and junction close to the
 * chunk: dozens of distance sums per block, nearly all of them exactly 0.0, since a piece adds
 * nothing 12 or more blocks away (see DensityBounds.Beard, which uses the same reach to tell
 * cells no piece touches). Here a cell's blocks are computed by Minecraft's own Beardifier over
 * only the pieces and junctions within reach of that cell, in their order: the same sum without
 * its zeros. A cell out of reach of all of them gets zeros without any distance.
 */
public final class CellBeards {
    // A beardifier's pieces and junctions, read the way this version of Minecraft keeps them (lists from 1.21.9,
    // list iterators before): the rest of the code asks here, so it is the same source for every version.

    /** Whether it holds no structure piece and no junction. */
    public static boolean none(com.example.pregen.mixin.BeardifierAccessor beards) {
        return beards.pregen$getPieces().isEmpty() && beards.pregen$getJunctions().isEmpty();
    }

    public static List<Beardifier.Rigid> pieces(com.example.pregen.mixin.BeardifierAccessor beards) {
        return beards.pregen$getPieces();
    }

    public static List<JigsawJunction> junctions(com.example.pregen.mixin.BeardifierAccessor beards) {
        return beards.pregen$getJunctions();
    }

    /** Benchmark switch, on unless -Dfastpregen.cellBeards=false. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("fastpregen.cellBeards", "true"));
    /** Cells filled with zeros, with fewer pieces, and with all of the chunk's pieces. */
    public static final LongAdder NONE_NEAR = new LongAdder();
    public static final LongAdder SOME_NEAR = new LongAdder();
    public static final LongAdder ALL_NEAR = new LongAdder();
    /** Beyond every kernel of Beardifier (12 blocks) with room to spare; DensityBounds.Beard's reach. */
    private static final int REACH = 16;

    private final Beardifier whole;
    private final List<Beardifier.Rigid> pieces;
    private final List<JigsawJunction> junctions;
    private final BoundingBox affectedBox;
    /** Six numbers per piece, then per junction: the box it can change the density in. */
    private final int[] boxes;
    private final Map<BitSet, Beardifier> byItems = new HashMap<>();

    public CellBeards(Beardifier whole, List<Beardifier.Rigid> pieces, List<JigsawJunction> junctions, BoundingBox affectedBox) {
        this.whole = whole;
        this.pieces = pieces;
        this.junctions = junctions;
        this.affectedBox = affectedBox;
        boxes = new int[(pieces.size() + junctions.size()) * 6];
        int at = 0;
        for (Beardifier.Rigid rigid : pieces) {
            BoundingBox box = rigid.box();
            if (rigid.terrainAdjustment() == TerrainAdjustment.NONE) {
                // Adds 0.0 everywhere: a box nothing overlaps.
                boxes[at] = Integer.MAX_VALUE;
                boxes[at + 1] = Integer.MIN_VALUE;
            } else {
                int ground = box.minY() + rigid.groundLevelDelta();
                boxes[at] = box.minX() - REACH;
                boxes[at + 1] = box.maxX() + REACH;
                boxes[at + 2] = Math.min(ground, box.minY()) - REACH;
                boxes[at + 3] = Math.max(ground, box.maxY()) + REACH;
                boxes[at + 4] = box.minZ() - REACH;
                boxes[at + 5] = box.maxZ() + REACH;
            }
            at += 6;
        }
        for (JigsawJunction junction : junctions) {
            boxes[at] = junction.getSourceX() - REACH;
            boxes[at + 1] = junction.getSourceX() + REACH;
            boxes[at + 2] = junction.getSourceGroundY() - REACH;
            boxes[at + 3] = junction.getSourceGroundY() + REACH;
            boxes[at + 4] = junction.getSourceZ() - REACH;
            boxes[at + 5] = junction.getSourceZ() + REACH;
            at += 6;
        }
    }

    /**
     * The Beardifier to compute the cell's blocks with: null when nothing reaches the cell (every
     * block gets 0.0), the chunk's own when everything does, else one over what reaches it.
     */
    public Beardifier forCell(int minX, int minY, int minZ, int width, int height) {
        int maxX = minX + width - 1;
        int maxY = minY + height - 1;
        int maxZ = minZ + width - 1;
        int[] boxes = this.boxes;
        int items = boxes.length / 6;
        BitSet near = null;
        int count = 0;
        for (int i = 0; i < items; i++) {
            int at = i * 6;
            if (maxX >= boxes[at] && minX <= boxes[at + 1] && maxY >= boxes[at + 2] && minY <= boxes[at + 3]
                    && maxZ >= boxes[at + 4] && minZ <= boxes[at + 5]) {
                if (near == null) near = new BitSet(items);
                near.set(i);
                count++;
            }
        }
        if (near == null) {
            NONE_NEAR.increment();
            return null;
        }
        if (count == items) {
            ALL_NEAR.increment();
            return whole;
        }
        SOME_NEAR.increment();
        Beardifier some = byItems.get(near);
        if (some == null) {
            some = over(near);
            byItems.put(near, some);
        }
        return some;
    }

    /** Minecraft's Beardifier over the chosen pieces and junctions, in the chunk's order. */
    private Beardifier over(BitSet near) {
        List<Beardifier.Rigid> somePieces = new ArrayList<>();
        List<JigsawJunction> someJunctions = new ArrayList<>();
        int pieceCount = pieces.size();
        for (int i = near.nextSetBit(0); i >= 0; i = near.nextSetBit(i + 1)) {
            if (i < pieceCount) somePieces.add(pieces.get(i));
            else someJunctions.add(junctions.get(i - pieceCount));
        }
        return new Beardifier(List.copyOf(somePieces), List.copyOf(someJunctions), affectedBox);
    }
}
