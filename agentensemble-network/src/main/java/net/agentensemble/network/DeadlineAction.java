package net.agentensemble.network;

/**
 * Action to take when an {@link RequestMode#AWAIT_WITH_DEADLINE} request exceeds its deadline.
 *
 * @see NetworkTask
 * @see NetworkTool
 */
public enum DeadlineAction {
    /** Return a timeout error to the calling agent. */
    RETURN_TIMEOUT_ERROR,
    /** Return a partial/placeholder result indicating the task is still running. */
    RETURN_PARTIAL,
    /** Continue processing in the background and deliver the result via the onComplete callback. */
    CONTINUE_IN_BACKGROUND
}
