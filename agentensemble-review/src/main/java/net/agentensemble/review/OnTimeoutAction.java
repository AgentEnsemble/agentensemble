package net.agentensemble.review;

/**
 * The action to take when a {@link ReviewHandler} review gate times out with no
 * human response.
 *
 * <p>Configure via {@link Review.ReviewBuilder#onTimeout(OnTimeoutAction)}:
 * <pre>
 * Review.builder()
 *     .timeout(Duration.ofMinutes(2))
 *     .onTimeout(OnTimeoutAction.CONTINUE)
 *     .build();
 * </pre>
 */
public enum OnTimeoutAction {

    /**
     * Treat a timeout as a {@link ReviewDecision.Continue}.
     *
     * <p>Execution proceeds as if the human approved the output unchanged.
     * Use this when unattended pipelines should continue rather than stall.
     */
    CONTINUE,

    /**
     * Treat a timeout as a {@link ReviewDecision.ExitEarly}.
     *
     * <p>The pipeline stops with completed tasks so far. Use this when safety
     * requires that unreviewed output must not propagate downstream.
     *
     * <p>This is the default when no explicit {@code OnTimeoutAction} is set.
     */
    EXIT_EARLY,

    /**
     * Throw a {@link ReviewTimeoutException} when the timeout expires.
     *
     * <p>The exception propagates as a task execution failure. Use this when a
     * timeout must be treated as an error that requires explicit handling by the
     * caller.
     */
    FAIL
}
