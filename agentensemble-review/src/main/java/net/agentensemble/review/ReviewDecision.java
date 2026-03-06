package net.agentensemble.review;

/**
 * The decision returned by a {@link ReviewHandler} after reviewing a task.
 *
 * <p>This is a sealed interface with three permitted implementations:
 * <ul>
 *   <li>{@link Continue} -- proceed with the current output unchanged</li>
 *   <li>{@link Edit} -- replace the task output with revised text</li>
 *   <li>{@link ExitEarly} -- stop the pipeline and return partial results</li>
 * </ul>
 *
 * <p>Use the static factory methods for concise construction:
 * <pre>
 * ReviewDecision.continueExecution();
 * ReviewDecision.edit("Revised output text");
 * ReviewDecision.exitEarly();
 * ReviewDecision.exitEarlyTimeout();  // when a review gate timeout caused the exit
 * </pre>
 */
public sealed interface ReviewDecision permits ReviewDecision.Continue, ReviewDecision.Edit, ReviewDecision.ExitEarly {

    /**
     * Continue execution with the current output unchanged.
     *
     * <p>Before-execution: proceed with task execution.
     * After-execution: pass the output forward unchanged.
     * During-execution: continue the ReAct loop with an empty acknowledgement.
     */
    record Continue() implements ReviewDecision {}

    /**
     * Replace the task output with revised text.
     *
     * <p>Only meaningful for after-execution and during-execution review gates.
     * Before-execution: treated as {@link Continue} (no output exists yet to edit).
     *
     * @param revisedOutput the replacement text; must not be null
     */
    record Edit(String revisedOutput) implements ReviewDecision {

        /**
         * Compact constructor to validate that revisedOutput is not null.
         */
        public Edit {
            if (revisedOutput == null) {
                throw new IllegalArgumentException("revisedOutput must not be null");
            }
        }
    }

    /**
     * Stop the pipeline and return partial results.
     *
     * <p>Before-execution: the current task does not execute; all previously
     * completed tasks are included in the returned {@code EnsembleOutput}.
     * After-execution: the current task is included in the output, then the
     * pipeline stops.
     * During-execution (via {@code HumanInputTool}): the agent loop is aborted
     * and the pipeline stops with completed tasks so far.
     *
     * <p>When {@code timedOut} is {@code true}, the exit was caused by a review gate
     * timeout expiring with {@link OnTimeoutAction#EXIT_EARLY}, and the ensemble will
     * record {@code ExitReason.TIMEOUT} instead of {@code ExitReason.USER_EXIT_EARLY}.
     *
     * @param timedOut {@code true} when the exit was triggered by a timeout rather
     *                 than an explicit human choice
     */
    record ExitEarly(boolean timedOut) implements ReviewDecision {}

    // ========================
    // Singleton instances (avoids allocating new records every time)
    // ========================

    /** Reusable singleton Continue instance. */
    Continue CONTINUE_INSTANCE = new Continue();

    /** Reusable singleton user-initiated ExitEarly instance (timedOut = false). */
    ExitEarly EXIT_EARLY_INSTANCE = new ExitEarly(false);

    /** Reusable singleton timeout-triggered ExitEarly instance (timedOut = true). */
    ExitEarly EXIT_EARLY_TIMEOUT_INSTANCE = new ExitEarly(true);

    // ========================
    // Static factory methods
    // ========================

    /**
     * Return the singleton {@link Continue} instance.
     *
     * @return a Continue decision
     */
    static Continue continueExecution() {
        return CONTINUE_INSTANCE;
    }

    /**
     * Create an {@link Edit} decision with the given revised output.
     *
     * @param revisedOutput the replacement text; must not be null
     * @return an Edit decision
     */
    static Edit edit(String revisedOutput) {
        return new Edit(revisedOutput);
    }

    /**
     * Return the singleton user-initiated {@link ExitEarly} instance ({@code timedOut = false}).
     *
     * <p>Use this when a human reviewer explicitly chooses to stop the pipeline.
     * The ensemble will record {@code ExitReason.USER_EXIT_EARLY}.
     *
     * @return an ExitEarly decision with timedOut = false
     */
    static ExitEarly exitEarly() {
        return EXIT_EARLY_INSTANCE;
    }

    /**
     * Return the singleton timeout-triggered {@link ExitEarly} instance ({@code timedOut = true}).
     *
     * <p>Use this when a review gate timeout expires with
     * {@link OnTimeoutAction#EXIT_EARLY}. The ensemble will record {@code ExitReason.TIMEOUT}.
     *
     * @return an ExitEarly decision with timedOut = true
     */
    static ExitEarly exitEarlyTimeout() {
        return EXIT_EARLY_TIMEOUT_INSTANCE;
    }
}
