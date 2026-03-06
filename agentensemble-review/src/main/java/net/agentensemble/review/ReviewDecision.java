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
     */
    record ExitEarly() implements ReviewDecision {}

    // ========================
    // Singleton instances (avoids allocating new records every time)
    // ========================

    /** Reusable singleton Continue instance. */
    Continue CONTINUE_INSTANCE = new Continue();

    /** Reusable singleton ExitEarly instance. */
    ExitEarly EXIT_EARLY_INSTANCE = new ExitEarly();

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
     * Return the singleton {@link ExitEarly} instance.
     *
     * @return an ExitEarly decision
     */
    static ExitEarly exitEarly() {
        return EXIT_EARLY_INSTANCE;
    }
}
