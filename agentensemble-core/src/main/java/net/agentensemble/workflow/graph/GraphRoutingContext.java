package net.agentensemble.workflow.graph;

import java.util.List;
import java.util.Map;
import net.agentensemble.task.TaskOutput;

/**
 * View of state-machine state passed to a {@link GraphPredicate} when routing edges out of
 * the just-completed state.
 *
 * <p>Predicates see only graph-local state — they cannot reach tasks outside the graph.
 * If a predicate needs cross-graph context, it must read it from {@link #lastOutput} (which
 * carries the just-completed state's full {@code TaskOutput}) or from the ensemble's
 * {@code MemoryStore} (accessed indirectly via state Tasks that read from scopes).
 */
public interface GraphRoutingContext {

    /**
     * Name of the state whose Task just completed (i.e. the state being routed out of).
     */
    String currentState();

    /**
     * Output produced by the just-completed state's Task. Never null.
     */
    TaskOutput lastOutput();

    /**
     * 1-based step counter. The first time the predicate is evaluated (after the start
     * state runs), {@code stepNumber()} returns {@code 1}.
     */
    int stepNumber();

    /**
     * Per-state visit history: state name → ordered list of outputs, one per visit.
     * A state visited 3 times produces a 3-element list. Insertion order is the visit order.
     *
     * <p>Useful for predicates that need to know "have we already tried this twice?" or
     * "what was the prior visit's verdict?".
     */
    Map<String, List<TaskOutput>> stateHistory();
}
