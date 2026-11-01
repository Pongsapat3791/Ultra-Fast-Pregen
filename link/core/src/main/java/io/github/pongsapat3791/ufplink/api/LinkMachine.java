package io.github.pongsapat3791.ufplink.api;

/**
 * A client connected to the host.
 *
 * @param name            its {@code name} in config/ufplink-network.conf; the plan goes by this name
 * @param address         where it connects from
 * @param version         its Ultra Fast Pregen Link
 * @param threads         the CPU threads its server sees
 * @param maxHeapBytes    its server's largest heap
 * @param system          its operating system
 * @param state           what it does now, for display
 * @param tileX           the tile it generates, valid while {@code busy}
 * @param tileZ           see tileX
 * @param busy            whether it has a tile
 * @param tilesDone       tiles it finished in this connection
 * @param chunksReceived  chunks the host got from it in this connection
 * @param bytesReceived   their size
 * @param chunksPerSecond a tile's chunks over the time its last tile took, 0 before the first one
 * @param connectedMillis time since it connected
 */
public record LinkMachine(String name, String address, String version, int threads, long maxHeapBytes, String system,
                          String state, int tileX, int tileZ, boolean busy, long tilesDone, long chunksReceived,
                          long bytesReceived, double chunksPerSecond, long connectedMillis) {
}
