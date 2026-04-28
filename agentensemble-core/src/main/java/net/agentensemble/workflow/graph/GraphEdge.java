package net.agentensemble.workflow.graph;

import lombok.NonNull;
import lombok.Value;

/**
 * One transition in a {@link Graph} state machine.
 *
 * <p>An edge has a source state ({@link #from}), a target state name ({@link #to} — either
 * another state or {@link Graph#END}), an optional {@link #condition} predicate, and an
 * optional human-readable {@link #conditionDescription} for visualisation.
 *
 * <p>When {@link #condition} is {@code null}, the edge is treated as unconditional and
 * always matches. Place unconditional edges last among a state's outgoing edges to use
 * them as a fallback.
 *
 * <p>Edges are evaluated in declaration order; the first matching edge wins.
 */
@Value
public class GraphEdge {

    /** Name of the state being routed out of. Must be a valid state in the graph. */
    @NonNull
    String from;

    /** Target state name, or {@link Graph#END} for terminal transitions. */
    @NonNull
    String to;

    /**
     * Predicate evaluated after the {@link #from} state's Task completes.
     * {@code null} means "always match" (unconditional fallback).
     */
    GraphPredicate condition;

    /**
     * Optional human-readable description of the condition, used as the edge label in
     * visualisation. {@code null} renders as either a blank label (for unconditional edges)
     * or "(condition)" for predicate-bearing edges.
     */
    String conditionDescription;
}
