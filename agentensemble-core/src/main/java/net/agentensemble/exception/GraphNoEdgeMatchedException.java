package net.agentensemble.exception;

/**
 * Thrown by {@link net.agentensemble.workflow.graph.GraphExecutor} when a state's Task
 * completes but no outgoing edge's condition matches.
 *
 * <p>This typically indicates a missing fallback edge: every non-{@code END} state should
 * have at least one unconditional or always-true edge so the state machine can never
 * deadlock. The exception message lists the candidate outgoing edges so the user can see
 * which conditions were evaluated.
 */
public class GraphNoEdgeMatchedException extends AgentEnsembleException {

    private static final long serialVersionUID = 1L;

    private final String graphName;
    private final String currentState;
    private final String outputPreview;

    public GraphNoEdgeMatchedException(String graphName, String currentState, String outputPreview, String details) {
        super("Graph '" + graphName + "' state '" + currentState
                + "' has no matching outgoing edge for the produced output. "
                + details + " Output preview: " + truncate(outputPreview, 200));
        this.graphName = graphName;
        this.currentState = currentState;
        this.outputPreview = outputPreview;
    }

    public String getGraphName() {
        return graphName;
    }

    public String getCurrentState() {
        return currentState;
    }

    public String getOutputPreview() {
        return outputPreview;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "(null)";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
