package net.agentensemble.exception;

/**
 * Thrown when a human reviewer requests early pipeline termination via a
 * {@link net.agentensemble.review.ReviewDecision.ExitEarly} decision.
 *
 * <p>This exception is thrown by {@code HumanInputTool} when the reviewer
 * chooses to exit early during agent execution. It propagates through
 * {@link net.agentensemble.tool.AbstractAgentTool},
 * {@link net.agentensemble.agent.AgentExecutor}, and is caught by the
 * workflow executor (e.g., {@link net.agentensemble.workflow.SequentialWorkflowExecutor})
 * which assembles partial results and returns an
 * {@link net.agentensemble.ensemble.EnsembleOutput} with
 * {@link net.agentensemble.ensemble.ExitReason#USER_EXIT_EARLY} or
 * {@link net.agentensemble.ensemble.ExitReason#TIMEOUT}.
 *
 * <p>The {@link #isTimedOut()} flag distinguishes a timeout-triggered exit
 * (review gate expired with {@code OnTimeoutAction.EXIT_EARLY}) from an
 * explicit human choice to stop the pipeline.
 *
 * <p>This is an unchecked exception so it propagates naturally through the
 * execution stack without requiring every intermediate layer to declare it.
 */
public class ExitEarlyException extends RuntimeException {

    private final boolean timedOut;

    /**
     * Construct an ExitEarlyException with a detail message.
     *
     * <p>The exception is treated as a user-initiated exit ({@code timedOut = false}).
     *
     * @param message the detail message
     */
    public ExitEarlyException(String message) {
        super(message);
        this.timedOut = false;
    }

    /**
     * Construct an ExitEarlyException indicating whether the exit was triggered by a
     * review gate timeout.
     *
     * @param message  the detail message
     * @param timedOut {@code true} when the exit was caused by a timeout expiring with
     *                 {@code OnTimeoutAction.EXIT_EARLY}; {@code false} for a human choice
     */
    public ExitEarlyException(String message, boolean timedOut) {
        super(message);
        this.timedOut = timedOut;
    }

    /**
     * Returns {@code true} when this exit was triggered by a review gate timeout, not
     * an explicit human choice.
     *
     * <p>When {@code true}, the workflow executor records
     * {@link net.agentensemble.ensemble.ExitReason#TIMEOUT}. When {@code false}, it
     * records {@link net.agentensemble.ensemble.ExitReason#USER_EXIT_EARLY}.
     *
     * @return true if this exit was triggered by a timeout
     */
    public boolean isTimedOut() {
        return timedOut;
    }
}
