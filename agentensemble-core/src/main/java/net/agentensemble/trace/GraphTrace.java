package net.agentensemble.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import lombok.Value;

/**
 * Per-graph execution trace, captured for an ensemble that ran a
 * {@link net.agentensemble.workflow.graph.Graph}. Sibling to {@link LoopTrace} on
 * {@link ExecutionTrace}.
 *
 * <p>Records the full path through the state machine: name, start state, terminal reason,
 * and per-step state names in execution order.
 */
@Builder(toBuilder = true)
@Value
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class GraphTrace {

    /** The graph's name from {@code Graph.builder().name(...)}. */
    @NonNull
    String graphName;

    /** Start state name. */
    @NonNull
    String startState;

    /** {@code "terminal"} or {@code "maxSteps"}. */
    @NonNull
    String terminationReason;

    /** Number of steps actually executed. */
    int stepsRun;

    /** Configured cap. */
    int maxSteps;

    /**
     * Per-step trace in execution order. Each entry records the state name and the step
     * number for that visit; revisits to the same state appear as multiple entries with
     * increasing step numbers.
     */
    @Singular("step")
    List<GraphStepTrace> steps;

    /** Compact step record. */
    @Value
    public static class GraphStepTrace {
        /** State name visited. */
        @NonNull
        String stateName;

        /** 1-based step number within the graph execution. */
        int stepNumber;

        /**
         * Name of the state routed to next, or {@code "__END__"} for terminal transitions,
         * or {@code null} if execution stopped here.
         */
        String nextState;
    }
}
