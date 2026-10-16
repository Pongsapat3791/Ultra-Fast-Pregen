package com.example.pregen;

/**
 * Features write into all eight neighbors, so two chunks decorated at the same time must have
 * disjoint 3x3 write areas: Chebyshev distance of at least 3. Checked across the negative axis.
 */
public final class PhaseSpacingCheck {
    private PhaseSpacingCheck() {}

    public static void run() {
        int spacing = 3;
        int min = -40;
        int max = 40;
        long pairs = 0;
        for (int ax = min; ax <= max; ax++) {
            for (int az = min; az <= max; az++) {
                int phase = InMemoryChunkGrid.phaseOf(ax, az, spacing);
                require(phase >= 0 && phase < spacing * spacing, "Phase out of range at " + ax + ", " + az);
                // Every same-phase chunk within the write-overlap range must be far enough away.
                for (int bx = ax - 2; bx <= ax + 2; bx++) {
                    for (int bz = az - 2; bz <= az + 2; bz++) {
                        if (bx == ax && bz == az) continue;
                        if (InMemoryChunkGrid.phaseOf(bx, bz, spacing) == phase) {
                            throw new AssertionError("Chunks [" + ax + ", " + az + "] and [" + bx + ", " + bz
                                    + "] share phase " + phase + " but their write areas overlap");
                        }
                        pairs++;
                    }
                }
            }
        }
        System.out.println("PHASE_SPACING_CHECK PASS phases=9 neighbor_pairs_checked=" + pairs + " disjoint_write_areas=true");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
