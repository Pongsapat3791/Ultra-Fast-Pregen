package io.github.pongsapat3791.ufplink.api;

/**
 * A distributed pregen, at one moment.
 *
 * @param plan          its area, and each tile's machine and state
 * @param helpOthers    see {@link LinkApi#start}
 * @param running       whether it still runs
 * @param written       chunks the host lacked and got
 * @param replaced      chunks at tile edges joined to the new ones
 * @param kept          chunks left alone as they had changed on the host
 * @param held          chunks left alone as the server holds them
 * @param rejected      chunks refused as damaged
 * @param skipped       tiles the host had fully generated already
 * @param elapsedMillis time since it started, frozen when it ended
 * @param stopReason    why it stops or stopped, or null
 * @param waitingFor    why tiles wait now, for display, or null
 */
public record LinkRun(LinkPlan plan, boolean helpOthers, boolean running, long written, long replaced, long kept,
                      long held, long rejected, long skipped, long elapsedMillis, String stopReason, String waitingFor) {
}
