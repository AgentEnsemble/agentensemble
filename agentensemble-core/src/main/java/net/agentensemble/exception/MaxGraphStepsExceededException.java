package net.agentensemble.exception;

/**
 * Thrown when a {@link net.agentensemble.workflow.graph.Graph} reaches its {@code maxSteps}
 * cap without arriving at {@link net.agentensemble.workflow.graph.Graph#END}, and
 * {@link net.agentensemble.workflow.graph.MaxStepsAction#THROW} is configured.
 *
 * <p>Distinct from {@link net.agentensemble.exception.MaxLoopIterationsExceededException}
 * which fires for {@code Loop} iteration caps.
 */
public class MaxGraphStepsExceededException extends AgentEnsembleException {

    private static final long serialVersionUID = 1L;

    private final String graphName;
    private final int maxSteps;
    private final String lastState;

    public MaxGraphStepsExceededException(String graphName, int maxSteps, String lastState) {
        super("Graph '" + graphName + "' did not reach a terminal state after " + maxSteps + " step(s). Last state: '"
                + lastState + "'.");
        this.graphName = graphName;
        this.maxSteps = maxSteps;
        this.lastState = lastState;
    }

    public String getGraphName() {
        return graphName;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public String getLastState() {
        return lastState;
    }
}
