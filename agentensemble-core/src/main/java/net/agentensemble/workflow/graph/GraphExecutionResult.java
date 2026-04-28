package net.agentensemble.workflow.graph;

import java.util.List;
import java.util.Map;
import lombok.NonNull;
import lombok.Value;
import net.agentensemble.Task;
import net.agentensemble.task.TaskOutput;

/**
 * Result of executing a {@link Graph}.
 *
 * <p>Captures the full execution path through the state machine plus a flat
 * identity-keyed projection of state outputs for the outer ensemble's
 * {@link net.agentensemble.ensemble.EnsembleOutput} to merge.
 */
@Value
public class GraphExecutionResult {

    /** The graph that produced this result. */
    @NonNull
    Graph graph;

    /** 1-based count of steps actually executed. */
    int stepsRun;

    /**
     * Why the graph stopped:
     * <ul>
     *   <li>{@code "terminal"} — an edge routed to {@link Graph#END}.</li>
     *   <li>{@code "maxSteps"} — the cap was hit without reaching END.</li>
     * </ul>
     * The {@code "no-edge-matched"} case is not represented here because it propagates
     * as {@link net.agentensemble.exception.GraphNoEdgeMatchedException} rather than a
     * normal result.
     */
    @NonNull
    String terminationReason;

    /**
     * Per-step record in execution order. Inspect this to reconstruct the path through
     * the state machine; revisits to the same state appear as multiple entries.
     */
    @NonNull
    List<GraphStep> history;

    /**
     * Per-state visit history: state name → ordered list of outputs. A state visited 3
     * times produces a 3-element list. Insertion order matches visit order. Useful for
     * downstream consumers that want "all outputs of state X" without filtering history.
     */
    @NonNull
    Map<String, List<TaskOutput>> stateOutputsByName;

    /**
     * Identity-keyed map of state-Task instances to their last output. The outer
     * scheduler merges this into {@code EnsembleOutput.taskOutputIndex}.
     *
     * <p>Each state-Task's <strong>original</strong> instance is the key (not the per-visit
     * rebuilt instance with revision feedback), matching the contract used by
     * {@code LoopExecutor.projectOutputs}.
     */
    @NonNull
    java.util.IdentityHashMap<Task, TaskOutput> projectedOutputs;

    /** Convenience: did the graph terminate by reaching {@link Graph#END}? */
    public boolean stoppedByTerminal() {
        return "terminal".equals(terminationReason);
    }

    /** Convenience: did the graph hit its {@code maxSteps} cap? */
    public boolean stoppedByMaxSteps() {
        return "maxSteps".equals(terminationReason);
    }

    /** Convenience: the last visited state's output, or empty if no steps ran. */
    public java.util.Optional<TaskOutput> lastOutput() {
        if (history.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(history.get(history.size() - 1).getOutput());
    }
}
