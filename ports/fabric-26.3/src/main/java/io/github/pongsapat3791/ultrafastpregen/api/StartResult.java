package io.github.pongsapat3791.ultrafastpregen.api;

import java.util.Optional;

/**
 * Whether {@link PregenApi#start} or {@link PregenApi#resume} started a run.
 *
 * @param started whether a run started
 * @param message why not (no server, unknown dimension, another run active, area outside the world,
 *                an unsupported chunk system such as Moonrise, no earlier run to resume), or what started
 * @param status  the new run's status when it started
 */
public record StartResult(boolean started, String message, Optional<PregenStatus> status) {
    public static StartResult refused(String message) {
        return new StartResult(false, message, Optional.empty());
    }

    public static StartResult started(String message, PregenStatus status) {
        return new StartResult(true, message, Optional.of(status));
    }
}
