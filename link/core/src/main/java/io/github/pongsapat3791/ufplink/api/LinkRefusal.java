package io.github.pongsapat3791.ufplink.api;

/**
 * A connection the host refused.
 *
 * @param name     the name the machine gave, or "?" when it did not get that far
 * @param address  where it came from
 * @param reason   why: the secret, or how its world generates differently
 * @param whenMillis when, as {@link System#currentTimeMillis()}
 */
public record LinkRefusal(String name, String address, String reason, long whenMillis) {
}
