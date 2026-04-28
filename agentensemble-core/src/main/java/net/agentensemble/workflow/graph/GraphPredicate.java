package net.agentensemble.workflow.graph;

/**
 * Decides whether an outgoing {@link GraphEdge} should fire after a state's Task completes.
 *
 * <p>Returning {@code true} routes execution along the edge. Edges are evaluated in
 * declaration order; the first matching edge wins.
 *
 * <p>An edge with a {@code null} predicate is treated as unconditional (always matches).
 * Place unconditional edges last among a state's outgoing edges to use them as a fallback.
 */
@FunctionalInterface
public interface GraphPredicate {

    /**
     * @param ctx state of the graph after the just-completed state's Task ran
     * @return {@code true} to route along this edge, {@code false} to skip it
     */
    boolean matches(GraphRoutingContext ctx);
}
