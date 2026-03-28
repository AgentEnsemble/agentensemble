package net.agentensemble.network;

/**
 * Controls how a caller waits for the result of a cross-ensemble request.
 *
 * <ul>
 *   <li>{@link #AWAIT} -- block until the result arrives (default, existing behavior)</li>
 *   <li>{@link #ASYNC} -- submit and return immediately; result delivered via callback</li>
 *   <li>{@link #AWAIT_WITH_DEADLINE} -- block up to a deadline, then continue</li>
 * </ul>
 *
 * @see NetworkTask
 * @see NetworkTool
 */
public enum RequestMode {
    /** Block until the result arrives. This is the default and matches existing behavior. */
    AWAIT,
    /** Submit and return immediately. The result is delivered via a callback. */
    ASYNC,
    /** Block up to a deadline duration, then apply the configured {@link DeadlineAction}. */
    AWAIT_WITH_DEADLINE
}
