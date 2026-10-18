package io.github.pongsapat3791.ultrafastpregen.api;

/**
 * A snapshot of a run: where, how far and how fast. Taken when it is handed out, so it does not
 * change afterwards; ask {@link PregenApi#status()} again for newer numbers.
 *
 * @param request         the area
 * @param state           where the run is
 * @param totalChunks     chunks in the area
 * @param doneChunks      chunks finished so far: generated and saved, or kept because they existed
 * @param keptChunks      of these, chunks kept because they already existed or an earlier run generated them
 * @param progress        0.0 to 1.0, including the share of the batch in progress
 * @param chunksPerSecond the run's average speed over the chunks it generated itself
 * @param elapsedMillis   time since the run started (frozen when it ends)
 * @param etaMillis       estimated time left, or -1 while unknown (first seconds) or once ended
 * @param stage           what the run is doing, for display, for example "Stage 4/6: Features"
 */
public record PregenStatus(PregenRequest request, State state, long totalChunks, long doneChunks, long keptChunks,
                           double progress, double chunksPerSecond, long elapsedMillis, long etaMillis, String stage) {
    public enum State {
        RUNNING,
        /** Every chunk of the area is generated and saved (or was kept). */
        COMPLETED,
        /** Stopped early ({@link PregenApi#stop()}, {@code /pregen stop}, or the server stopping); resumable. */
        STOPPED,
        /** Stopped by an error, see the server log; resumable. */
        FAILED;

        public boolean ended() {
            return this != RUNNING;
        }
    }
}
