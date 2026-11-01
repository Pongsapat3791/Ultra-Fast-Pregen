package io.github.pongsapat3791.ufplink.api;

/**
 * A client's connection to its host.
 *
 * @param name       this client's name
 * @param host       the host's address
 * @param port       the host's port
 * @param state      what it does now, for display
 * @param tilesDone  tiles finished since the server started
 * @param chunksSent chunks sent to the host
 */
public record LinkClientStatus(String name, String host, int port, String state, long tilesDone, long chunksSent) {
}
