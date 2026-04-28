package net.agentensemble.exception;

/**
 * Thrown when a {@link net.agentensemble.workflow.loop.Loop} reaches its
 * {@code maxIterations} cap without the predicate firing, and
 * {@link net.agentensemble.workflow.loop.MaxIterationsAction#THROW} is configured.
 *
 * <p>Distinct from {@link MaxIterationsExceededException}, which fires for an
 * agent's per-task tool-calling loop. This exception fires for the workflow-level
 * iteration loop.
 */
public class MaxLoopIterationsExceededException extends AgentEnsembleException {

    private static final long serialVersionUID = 1L;

    private final String loopName;
    private final int maxIterations;

    public MaxLoopIterationsExceededException(String loopName, int maxIterations) {
        super("Loop '" + loopName + "' did not converge after " + maxIterations + " iterations");
        this.loopName = loopName;
        this.maxIterations = maxIterations;
    }

    public String getLoopName() {
        return loopName;
    }

    public int getMaxIterations() {
        return maxIterations;
    }
}
