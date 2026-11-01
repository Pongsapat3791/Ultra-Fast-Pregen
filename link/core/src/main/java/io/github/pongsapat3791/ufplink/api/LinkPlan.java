package io.github.pongsapat3791.ufplink.api;

import java.util.List;

/**
 * Who generates what: an area of sizeK thousand blocks cut into tiles. {@link LinkApi#plan} makes one; a caller may
 * change the machine of any tile with {@link LinkTile#withPlanned} before it starts the plan.
 *
 * @param tileChunks the side of a tile in chunks
 * @param tiles      in the order they are handed out: nearest to the center first
 */
public record LinkPlan(String dimension, int centerX, int centerZ, int sizeK, int tileChunks, List<LinkTile> tiles) {
}
