package io.github.pongsapat3791.ufplink.api;

/**
 * One tile of a plan or of a run.
 *
 * @param tileX     the tile's position: its first chunk divided by the tile's side
 * @param tileZ     see tileX
 * @param minChunkX its first chunk
 * @param minChunkZ see minChunkX
 * @param planned   the machine it is planned for, or null for any machine
 * @param state     how far it is
 * @param worker    the machine generating it, or the one that did; null otherwise
 */
public record LinkTile(int tileX, int tileZ, int minChunkX, int minChunkZ, String planned, State state, String worker) {
    public enum State { PENDING, ASSIGNED, DONE }

    public LinkTile withPlanned(String machine) {
        return new LinkTile(tileX, tileZ, minChunkX, minChunkZ, machine, state, worker);
    }
}
