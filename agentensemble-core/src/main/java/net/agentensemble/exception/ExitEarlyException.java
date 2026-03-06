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
 * {@link net.agentensemble.ensemble.ExitReason#USER_EXIT_EARLY}.
 *
 * <p>This is an unchecked exception so it propagates naturally through the
 * execution stack without requiring every intermediate layer to declare it.
 */
public class ExitEarlyException extends RuntimeException {

    /**
     * Construct an ExitEarlyException with a detail message.
     *
     * @param message a description of the exit context
     */
    public ExitEarlyException(String message) {
        super(message);
    }
}
