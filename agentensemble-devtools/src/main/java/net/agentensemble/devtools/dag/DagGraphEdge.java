package net.agentensemble.devtools.dag;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

/**
 * Representation of a single edge in a {@link net.agentensemble.workflow.graph.Graph}
 * state-machine DAG export (schema 1.3+).
 *
 * <p>Edges connect two state nodes by id, or a state node to the implicit
 * {@code "__END__"} sentinel for terminal transitions. Conditional metadata is exposed
 * through {@link #conditionDescription} (a human-readable label, when supplied at edge
 * declaration time) and the {@link #unconditional} flag.
 *
 * <p>Post-execution, {@link #fired} indicates whether this edge was traversed at least
 * once during the run -- visualisation tools grey out unfired edges to highlight the
 * actual path taken.
 */
@Value
@Builder
public class DagGraphEdge {

    /** Source state id. */
    @NonNull
    String fromStateId;

    /** Target state id, or the implicit {@code "__END__"} for terminal transitions. */
    @NonNull
    String toStateId;

    /**
     * Human-readable description of the condition (used as the rendered edge label).
     * {@code null} for unconditional edges and for conditional edges without a supplied
     * label.
     */
    String conditionDescription;

    /** {@code true} for unconditional fallback edges (no predicate). */
    boolean unconditional;

    /**
     * Whether this edge was traversed at least once during execution. Populated
     * post-execution only via {@code DagExporter.build(Graph, GraphTrace)}; {@code false}
     * pre-execution.
     */
    boolean fired;
}
