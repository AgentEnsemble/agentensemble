package net.agentensemble.workflow.graph;

import lombok.NonNull;
import lombok.Value;
import net.agentensemble.task.TaskOutput;

/**
 * One step in a {@link Graph} execution: which state ran, what it produced, and which
 * step number it was.
 *
 * <p>Records are stored on {@link net.agentensemble.ensemble.EnsembleOutput#getGraphHistory()}
 * in execution order so consumers can reconstruct the path through the state machine
 * (including revisits).
 */
@Value
public class GraphStep {

    /** Name of the state whose Task ran. */
    @NonNull
    String stateName;

    /** 1-based step number within the graph execution. */
    int stepNumber;

    /** Output produced by the state's Task on this visit. */
    @NonNull
    TaskOutput output;

    /**
     * Name of the next state the executor routed to after this step, or
     * {@link Graph#END} if this step terminated the graph normally, or {@code null} if
     * execution stopped here due to {@code maxSteps} or an error.
     */
    String nextState;
}
